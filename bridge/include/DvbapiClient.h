// bridge/include/DvbapiClient.h
//
// Cliente TCP que gestiona la conexión con el servidor dvbapi de OSCam.
//
// Responsabilidades:
//  - Establecer y mantener la conexión TCP con reconexión automática usando
//    backoff exponencial (cap: 60 s).
//  - Enviar mensajes ya serializados por DvbapiProtocol.
//  - Recibir mensajes de OSCam y dispatch via callbacks.
//  - Todo el I/O de red ocurre en un hilo dedicado (no bloquea al llamador).
//
// Política de errores:
//  - Si el socket falla, se registra el error y se reintenta (con backoff).
//  - Máximo de reintentos configurable; al agotarse, OnFatalError() callback.
//  - Nunca lanza excepciones; usa std::error_code o callbacks para errores.
//
// Thread-safety:
//  - send*() es thread-safe: usa un mutex interno + cola de escritura.
//  - Los callbacks se invocan desde el hilo interno; el llamador NO debe
//    bloquear dentro de ellos.
//
// Autor: android-oscam-bridge (Fase 1)

#pragma once

#include "DvbapiProtocol.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <vector>

namespace oscam::dvbapi {

/**
 * @brief Parámetros de conexión al servidor OSCam dvbapi.
 */
struct ConnectionConfig {
    std::string host;           ///< IP o hostname del servidor OSCam
    uint16_t    port{9000};     ///< Puerto dvbapi de OSCam (por defecto 9000)
    int         connectTimeoutSec{5};   ///< Timeout de connect()
    int         recvTimeoutSec{10};     ///< Timeout de recv() por lectura
    int         maxReconnectAttempts{10}; ///< 0 = reintentos infinitos
    int         initialBackoffMs{500};  ///< Backoff inicial (se dobla en cada reintento)
    int         maxBackoffMs{60000};    ///< Cap del backoff
};

/**
 * @brief Callbacks que DvbapiClient invoca al recibir mensajes de OSCam.
 *
 * El llamador debe registrar al menos OnCaSetDescr para procesar el CW.
 * Todos los callbacks son opcionales (se puede dejar nullptr).
 */
struct DvbapiCallbacks {
    /// Control Word resuelto por OSCam.
    std::function<void(const CaDescr&)>    OnCaSetDescr;
    /// Algoritmo de descrambling anunciado antes del CW.
    std::function<void(const CaDescrMode&)> OnCaDescrMode;
    /// Handshake completado: OSCam nos ha respondido con SERVER_INFO.
    std::function<void(const ServerInfo&)> OnServerInfo;
    /// Error fatal (agotados los reintentos): el cliente se detiene.
    std::function<void(const std::string& reason)> OnFatalError;
    /// Cambio de estado de conexión (para logging/UI).
    std::function<void(bool connected)>    OnConnectionChanged;
};

/**
 * @brief Cliente TCP para el protocolo dvbapi de OSCam.
 *
 * ### Ejemplo de uso
 * ```cpp
 * oscam::dvbapi::ConnectionConfig cfg;
 * cfg.host = "192.168.1.100";
 * cfg.port = 9000;
 *
 * oscam::dvbapi::DvbapiCallbacks cbs;
 * cbs.OnCaSetDescr = [](const oscam::dvbapi::CaDescr& d) {
 *     // inyectar d.cw en el hardware descrambler
 * };
 *
 * oscam::dvbapi::DvbapiClient client(cfg, cbs);
 * client.start();
 *
 * // Enviar CA_SET_PID para el PID 0x0100, slot 0
 * client.sendCaSetPid(0, {0x0100, 0});
 *
 * // ... procesar ECMs ...
 * client.stop();
 * ```
 */
class DvbapiClient {
public:
    explicit DvbapiClient(ConnectionConfig config, DvbapiCallbacks callbacks);

    /// Prohibir copia/movimiento (gestiona un thread y un fd)
    DvbapiClient(const DvbapiClient&)            = delete;
    DvbapiClient& operator=(const DvbapiClient&) = delete;
    DvbapiClient(DvbapiClient&&)                 = delete;
    DvbapiClient& operator=(DvbapiClient&&)      = delete;

    /**
     * @brief Destructor: llama a stop() si el hilo sigue corriendo.
     */
    ~DvbapiClient();

    /**
     * @brief Arranca el hilo de red y envía el CLIENT_INFO inicial.
     *
     * No bloquea; la conexión se establece en background.
     * Puede llamarse solo una vez; si ya fue llamado, no hace nada.
     */
    void start();

    /**
     * @brief Detiene el hilo de red limpiamente y cierra el socket.
     *
     * Bloquea hasta que el hilo termina (máx. 2 s de gracia).
     */
    void stop();

    /**
     * @brief Encola un mensaje CA_SET_PID para enviarlo al servidor.
     *
     * @param adapterId  Índice del adaptador DVB (usualmente 0).
     * @param pid        PID del servicio/ECM y slot CA.
     * @return true si se encoló correctamente, false si el cliente está parado.
     */
    bool sendCaSetPid(uint8_t adapterId, const CaPid& pid);

    /**
     * @brief Encola un mensaje DMX_SET_FILTER.
     */
    bool sendDmxSetFilter(const DmxFilter& filter);

    /**
     * @brief Encola un mensaje DMX_STOP.
     */
    bool sendDmxStop(uint8_t adapterId, uint8_t demuxId,
                     uint8_t filterId, uint16_t pid);

    /**
     * @brief Indica si el cliente tiene una conexión activa en este momento.
     */
    bool isConnected() const noexcept;

private:
    // -----------------------------------------------------------------------
    // Hilo principal de red
    // -----------------------------------------------------------------------
    void networkThreadMain();

    /**
     * @brief Intenta conectar con backoff.
     * @return fd del socket válido (≥0), o -1 si se agotaron los intentos.
     */
    int connectWithBackoff();

    /**
     * @brief Realiza el handshake CLIENT_INFO → SERVER_INFO.
     * @return true si el servidor respondió correctamente.
     */
    bool performHandshake(int fd);

    /**
     * @brief Bucle de lectura/escritura sobre un fd conectado.
     *
     * Retorna cuando el socket falla o se pide parar.
     */
    void ioLoop(int fd);

    /**
     * @brief Envía los bytes pendientes de la cola de escritura al socket.
     * @return false si el socket falló.
     */
    bool flushWriteQueue(int fd);

    /**
     * @brief Lee datos del socket y despacha mensajes completos.
     * @return false si el socket falló o se cerró.
     */
    bool readAndDispatch(int fd);

    /**
     * @brief Despacha un mensaje completo recibido de OSCam.
     */
    void dispatchMessage(uint32_t opcode, std::span<const uint8_t> payload);

    // -----------------------------------------------------------------------
    // Helpers de socket (POSIX + winsock abstractos para tests en Windows)
    // -----------------------------------------------------------------------
    static int  createSocket();
    static bool setSocketTimeouts(int fd, int timeoutSec);
    static void closeSocket(int fd);
    static bool setNonBlocking(int fd, bool enable);

    // -----------------------------------------------------------------------
    // Cola de escritura thread-safe
    // -----------------------------------------------------------------------
    void enqueueBytes(std::vector<uint8_t> data);

    // -----------------------------------------------------------------------
    // Campos
    // -----------------------------------------------------------------------
    ConnectionConfig          config_;
    DvbapiCallbacks           callbacks_;

    std::thread               networkThread_;
    std::atomic<bool>         running_{false};
    std::atomic<bool>         connected_{false};

    // Cola de mensajes a enviar
    std::mutex                writeMutex_;
    std::condition_variable   writeCv_;
    std::queue<std::vector<uint8_t>> writeQueue_;

    // Buffer de recepción (acumula bytes hasta tener un mensaje completo)
    std::vector<uint8_t>      recvBuf_;
    static constexpr size_t   kRecvBufMax = 65536;
};

} // namespace oscam::dvbapi
