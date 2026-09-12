// bridge/include/DvbapiProtocol.h
//
// Serialización y deserialización del protocolo dvbapi de OSCam.
//
// Referencia de protocolo:
//   https://github.com/oscam-emu/oscam-patched/blob/master/oscam-dvbapi.c
//   https://github.com/tvheadend/tvheadend  (src/descrambler/dvbcsa.c)
//
// Todos los campos multi-byte van en network byte order (big-endian).
// No hay longitud de mensaje explícita en la cabecera: el opcode determina el
// tamaño total de cada datagrama.
//
// Autor: android-oscam-bridge (Fase 1)

#pragma once

#include <cstdint>
#include <vector>
#include <optional>
#include <string>
#include <span>

namespace oscam::dvbapi {

// ---------------------------------------------------------------------------
// Opcodes (uint32_t en big-endian sobre el wire)
// ---------------------------------------------------------------------------

/// Enviado por el bridge → OSCam para registrar un PID de servicio/ECM.
constexpr uint32_t DVBAPI_CA_SET_PID       = 0x40086f87U;
/// Enviado por OSCam → bridge con el Control Word descifrado.
constexpr uint32_t DVBAPI_CA_SET_DESCR     = 0x40106f86U;
/// Enviado por el bridge → OSCam para programar un filtro de sección DMX.
constexpr uint32_t DVBAPI_DMX_SET_FILTER   = 0x403c6f2bU;
/// Enviado por el bridge → OSCam para detener un filtro DMX activo.
constexpr uint32_t DVBAPI_DMX_STOP         = 0x00006f2aU;
/// Enviado por OSCam → bridge antes del CW: comunica el algoritmo de cifrado.
constexpr uint32_t DVBAPI_CA_SET_DESCR_MODE = 0x400c6f88U;
/// Enviado por OSCam → bridge para comunicar información de CA_INFO (CAID list).
constexpr uint32_t DVBAPI_CA_INFO          = 0x40086f87U;   // Nota: mismo valor que SET_PID;
                                                              // se distingue por contexto.
/// Handshake inicial (PMT update o reset de sesión).
constexpr uint32_t DVBAPI_CLIENT_INFO      = 0xFFFF0000U;
/// Respuesta de OSCam al CLIENT_INFO con lista de CAIDs soportados.
constexpr uint32_t DVBAPI_SERVER_INFO      = 0xFFFF0001U;
/// Señal de fin de PMT (OSCam deja de procesar el servicio actual).
constexpr uint32_t DVBAPI_ECM_INFO         = 0x9F803E00U;

// ---------------------------------------------------------------------------
// Constantes de relleno y límites
// ---------------------------------------------------------------------------

constexpr int  kDmxFilterLen  = 16;   ///< Longitud de filter/mask/mode en DMX_SET_FILTER
constexpr int  kCwLen         = 8;    ///< Longitud de un Control Word (DVB-CSA)
constexpr int  kEvenKeyIndex  = 0;
constexpr int  kOddKeyIndex   = 1;
constexpr uint8_t kProtocolVersion = 3;  ///< Versión dvbapi que anunciamos al servidor

// ---------------------------------------------------------------------------
// Structs en memoria (campos en host byte order; serializar con los helpers)
// ---------------------------------------------------------------------------

/**
 * @brief Identifica un PID de descrambling dentro de un adaptador.
 *
 * Corresponde al ca_pid_t de Linux DVB API v3.
 */
struct CaPid {
    uint32_t pid;       ///< PID del flujo elemental que lleva el ECM/servicio
    int32_t  index;     ///< Índice del slot de CA (-1 = desactivar, ≥0 = activar)
};

/**
 * @brief Control Word recibido de OSCam (CA_SET_DESCR).
 *
 * Un canal DVB-CSA necesita dos CW de 8 bytes: par (even) e impar (odd).
 * El campo @p index indica cuál de los dos lleva este mensaje.
 */
struct CaDescr {
    int32_t  index;     ///< Índice del slot CA donde inyectar el CW
    int32_t  parity;    ///< 0 = even key, 1 = odd key
    uint8_t  cw[kCwLen];///< Control Word de 8 bytes
};

/**
 * @brief Parámetros de un filtro de sección DMX (DMX_SET_FILTER).
 *
 * filter/mask/mode tienen kDmxFilterLen bytes cada uno, igual que
 * el struct dmx_sct_filter_params del kernel Linux.
 */
struct DmxFilter {
    uint8_t  adapterId;
    uint8_t  demuxId;
    uint8_t  filterId;
    uint16_t pid;
    uint8_t  filter[kDmxFilterLen];
    uint8_t  mask[kDmxFilterLen];
    uint8_t  mode[kDmxFilterLen];
    uint32_t timeout;   ///< Timeout en ms (0 = sin timeout)
    uint32_t flags;     ///< DMX_IMMEDIATE_START | DMX_CHECK_CRC
};

/**
 * @brief Algoritmo de descrambling anunciado por CA_SET_DESCR_MODE.
 */
struct CaDescrMode {
    int32_t  index;
    uint32_t algo;      ///< 0=DVB-CSA, 1=AES128, 2=AES-ECB ...
    uint32_t mode;      ///< Flags del modo (e.g. key-laddering)
};

/**
 * @brief Payload del handshake CLIENT_INFO enviado al conectar.
 */
struct ClientInfo {
    uint16_t protocolVersion;  ///< Debe ser kProtocolVersion
    std::string clientName;    ///< Nombre del cliente (≤ 255 bytes)
};

/**
 * @brief Respuesta del servidor al handshake.
 */
struct ServerInfo {
    uint16_t protocolVersion;
    std::string serverName;
};

// ---------------------------------------------------------------------------
// Clase principal: serialización y deserialización
// ---------------------------------------------------------------------------

/**
 * @brief Empaqueta y desempaqueta mensajes del protocolo dvbapi de OSCam.
 *
 * Todos los métodos de serialización devuelven un vector de bytes listo para
 * enviar por el socket TCP.  Los de deserialización reciben el buffer recibido
 * (puede ser parcial) y devuelven std::nullopt si los datos son insuficientes,
 * o el struct parseado si el mensaje está completo.
 *
 * La clase es stateless; no posee el socket.
 */
class DvbapiProtocol {
public:
    // -----------------------------------------------------------------------
    // Serialización (bridge → OSCam)
    // -----------------------------------------------------------------------

    /**
     * @brief Construye el mensaje CLIENT_INFO de handshake inicial.
     * @param info  Nombre del cliente y versión del protocolo.
     * @return Bytes listos para enviar.
     */
    static std::vector<uint8_t> buildClientInfo(const ClientInfo& info);

    /**
     * @brief Construye un mensaje CA_SET_PID.
     * @param adapterId  Índice del adaptador DVB (suele ser 0).
     * @param pid        Struct CaPid con el PID y el slot CA.
     * @return Bytes listos para enviar.
     */
    static std::vector<uint8_t> buildCaSetPid(uint8_t adapterId, const CaPid& pid);

    /**
     * @brief Construye un mensaje DMX_SET_FILTER para enviar a OSCam.
     * @param f  Todos los parámetros del filtro DMX.
     * @return Bytes listos para enviar.
     */
    static std::vector<uint8_t> buildDmxSetFilter(const DmxFilter& f);

    /**
     * @brief Construye un mensaje DMX_STOP para cancelar un filtro activo.
     * @param adapterId  Índice del adaptador DVB.
     * @param demuxId    Índice del demultiplexor.
     * @param filterId   Índice del filtro a detener.
     * @param pid        PID asociado al filtro.
     * @return Bytes listos para enviar.
     */
    static std::vector<uint8_t> buildDmxStop(uint8_t adapterId,
                                              uint8_t demuxId,
                                              uint8_t filterId,
                                              uint16_t pid);

    // -----------------------------------------------------------------------
    // Deserialización (OSCam → bridge)
    // -----------------------------------------------------------------------

    /**
     * @brief Intenta parsear el opcode de 4 bytes del inicio de un mensaje.
     *
     * @param buf  Buffer con al menos 4 bytes disponibles.
     * @return El opcode en host byte order, o std::nullopt si hay < 4 bytes.
     */
    static std::optional<uint32_t> peekOpcode(std::span<const uint8_t> buf);

    /**
     * @brief Devuelve el tamaño total esperado del mensaje para un opcode dado.
     *
     * Útil para saber cuántos bytes leer del socket antes de parsear.
     * @return Tamaño en bytes (incluyendo los 4 del opcode), o 0 si desconocido.
     */
    static size_t expectedMessageSize(uint32_t opcode);

    /**
     * @brief Parsea un mensaje CA_SET_DESCR completo.
     * @param buf  Buffer con exactamente expectedMessageSize(DVBAPI_CA_SET_DESCR) bytes.
     * @return Struct CaDescr, o std::nullopt si el buffer es demasiado corto o inválido.
     */
    static std::optional<CaDescr> parseCaSetDescr(std::span<const uint8_t> buf);

    /**
     * @brief Parsea un mensaje CA_SET_DESCR_MODE completo.
     */
    static std::optional<CaDescrMode> parseCaDescrMode(std::span<const uint8_t> buf);

    /**
     * @brief Parsea el handshake SERVER_INFO devuelto por OSCam.
     */
    static std::optional<ServerInfo> parseServerInfo(std::span<const uint8_t> buf);

    // -----------------------------------------------------------------------
    // Utilidades de diagnóstico
    // -----------------------------------------------------------------------

    /**
     * @brief Devuelve el nombre legible de un opcode para logging.
     */
    static std::string opcodeToString(uint32_t opcode);

    /**
     * @brief Formatea un Control Word de 8 bytes como cadena hex "XX XX XX ...".
     */
    static std::string cwToHex(std::span<const uint8_t, kCwLen> cw);

private:
    // Helpers internos de serialización big-endian
    static void appendU8 (std::vector<uint8_t>& buf, uint8_t  v);
    static void appendU16(std::vector<uint8_t>& buf, uint16_t v);
    static void appendU32(std::vector<uint8_t>& buf, uint32_t v);
    static void appendI32(std::vector<uint8_t>& buf, int32_t  v);

    static uint16_t readU16(std::span<const uint8_t> buf, size_t offset);
    static uint32_t readU32(std::span<const uint8_t> buf, size_t offset);
    static int32_t  readI32(std::span<const uint8_t> buf, size_t offset);
};

} // namespace oscam::dvbapi
