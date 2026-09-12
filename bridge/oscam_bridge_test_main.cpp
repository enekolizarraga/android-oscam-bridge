// bridge/oscam_bridge_test_main.cpp
//
// Binario de prueba standalone para la Fase 1.
//
// Uso:
//   ./oscam_bridge_test <IP_OSCAM> <PUERTO> <PID_ECM_HEX>
//
//   Ejemplo:
//   ./oscam_bridge_test 192.168.1.100 9000 0x0600
//
// Qué hace:
//   1. Conecta a OSCam dvbapi y hace el handshake CLIENT_INFO → SERVER_INFO.
//   2. Envía CA_SET_PID para el PID indicado (slot CA 0, adaptador 0).
//   3. Envía DMX_SET_FILTER para filtrar el PID como sección (table_id=0x80,
//      el byte más habitual en ECM de Nagravision/Conax/Irdeto).
//   4. Espera hasta 10 segundos a recibir CA_SET_DESCR con el Control Word.
//   5. Muestra el CW en hexadecimal y termina.
//
// Si OSCam responde con CA_SET_DESCR antes del timeout, el protocolo es
// correcto y puedes avanzar a la Fase 2.

#include "include/DvbapiClient.h"
#include "include/DvbapiProtocol.h"
#include "include/BridgeLogger.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>

int main(int argc, char* argv[]) {
    using namespace oscam::dvbapi;

    // ---------------------------------------------------------------------------
    // Argumentos de línea de comandos
    // ---------------------------------------------------------------------------
    if (argc < 4) {
        std::cerr << "Uso: " << argv[0]
                  << " <IP_OSCAM> <PUERTO> <PID_ECM_HEX>\n"
                  << "  Ejemplo: " << argv[0]
                  << " 192.168.1.100 9000 0x0600\n";
        return EXIT_FAILURE;
    }

    const std::string host    = argv[1];
    const uint16_t    port    = static_cast<uint16_t>(std::stoul(argv[2]));
    const uint32_t    ecmPid  = static_cast<uint32_t>(std::stoul(argv[3], nullptr, 16));

    oscam::BridgeLogger::setLevel(oscam::LogLevel::Debug);
    BLOG_I("=== OSCam dvbapi Fase 1 — Prueba de protocolo ===");
    BLOG_I("Servidor: %s:%u  PID ECM: 0x%04X", host.c_str(), port, ecmPid);

    // ---------------------------------------------------------------------------
    // Variables de sincronización para esperar el CW
    // ---------------------------------------------------------------------------
    std::mutex              cwMutex;
    std::condition_variable cwCv;
    std::atomic<bool>       cwReceived{false};
    CaDescr                 receivedDescr{};

    // ---------------------------------------------------------------------------
    // Configurar cliente
    // ---------------------------------------------------------------------------
    ConnectionConfig cfg;
    cfg.host                  = host;
    cfg.port                  = port;
    cfg.connectTimeoutSec     = 5;
    cfg.recvTimeoutSec        = 10;
    cfg.maxReconnectAttempts  = 3;   // Solo 3 intentos en el test
    cfg.initialBackoffMs      = 500;
    cfg.maxBackoffMs          = 4000;

    DvbapiCallbacks cbs;

    cbs.OnServerInfo = [](const ServerInfo& si) {
        BLOG_I("✓ Handshake completado: servidor='%s' proto=%u",
               si.serverName.c_str(), si.protocolVersion);
    };

    cbs.OnCaSetDescr = [&](const CaDescr& d) {
        BLOG_I("✓ CA_SET_DESCR recibido: index=%d parity=%s cw=[%s]",
               d.index,
               d.parity == kEvenKeyIndex ? "even" : "odd",
               DvbapiProtocol::cwToHex(
                   std::span<const uint8_t, kCwLen>(d.cw, kCwLen)).c_str());
        {
            std::lock_guard<std::mutex> lk(cwMutex);
            receivedDescr = d;
            cwReceived    = true;
        }
        cwCv.notify_one();
    };

    cbs.OnCaDescrMode = [](const CaDescrMode& m) {
        BLOG_I("CA_SET_DESCR_MODE: index=%d algo=%u mode=%u",
               m.index, m.algo, m.mode);
    };

    cbs.OnConnectionChanged = [](bool connected) {
        BLOG_I("Estado de conexión: %s", connected ? "CONECTADO" : "DESCONECTADO");
    };

    cbs.OnFatalError = [](const std::string& reason) {
        BLOG_E("Error fatal: %s", reason.c_str());
    };

    // ---------------------------------------------------------------------------
    // Iniciar cliente (conexión + handshake en background)
    // ---------------------------------------------------------------------------
    DvbapiClient client(cfg, cbs);
    client.start();

    // Esperar a que el cliente se conecte antes de enviar mensajes.
    // Polling simple con timeout (máx 8 s).
    for (int i = 0; i < 80 && !client.isConnected(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    if (!client.isConnected()) {
        BLOG_E("No se pudo conectar a %s:%u en 8 segundos", host.c_str(), port);
        client.stop();
        return EXIT_FAILURE;
    }

    // ---------------------------------------------------------------------------
    // Enviar CA_SET_PID (registrar el PID del servicio, slot 0)
    // ---------------------------------------------------------------------------
    CaPid pid{};
    pid.pid   = ecmPid;
    pid.index = 0;   // slot CA 0

    BLOG_I("Enviando CA_SET_PID: pid=0x%04X slot=0", ecmPid);
    if (!client.sendCaSetPid(/*adapterId=*/0, pid)) {
        BLOG_E("sendCaSetPid() falló");
        client.stop();
        return EXIT_FAILURE;
    }

    // ---------------------------------------------------------------------------
    // Enviar DMX_SET_FILTER para capturar secciones ECM del PID
    //
    // filter[0] = 0x80 → filtrar por table_id=0x80 (ECM Nagravision / "even")
    //   o 0x81 para odd ECM; aquí enviamos ambos en un filtro con mask=0xFE.
    //
    // Esta configuración es genérica para DVB-CSA; ajusta table_id según
    // el sistema CA de tu operador si OSCam no devuelve CW.
    // ---------------------------------------------------------------------------
    DmxFilter dmxFilter{};
    dmxFilter.adapterId = 0;
    dmxFilter.demuxId   = 0;
    dmxFilter.filterId  = 0;
    dmxFilter.pid       = static_cast<uint16_t>(ecmPid);
    // table_id 0x80/0x81 (ECM par/impar; mask 0xFE cubre ambos)
    dmxFilter.filter[0] = 0x80;
    dmxFilter.mask[0]   = 0xFE;
    // Resto de filter/mask/mode = 0 (sin restricción adicional)
    dmxFilter.timeout   = 0;              // Sin timeout de kernel
    dmxFilter.flags     = 0x00000001U;    // DMX_IMMEDIATE_START

    BLOG_I("Enviando DMX_SET_FILTER: pid=0x%04X filter[0]=0x80 mask[0]=0xFE",
           ecmPid);
    if (!client.sendDmxSetFilter(dmxFilter)) {
        BLOG_E("sendDmxSetFilter() falló");
        client.stop();
        return EXIT_FAILURE;
    }

    // ---------------------------------------------------------------------------
    // Esperar el Control Word (máx. 10 segundos)
    // ---------------------------------------------------------------------------
    BLOG_I("Esperando CA_SET_DESCR de OSCam (timeout 10 s)...");
    {
        std::unique_lock<std::mutex> lk(cwMutex);
        const bool ok = cwCv.wait_for(lk, std::chrono::seconds(10),
                                       [&]{ return cwReceived.load(); });
        if (!ok) {
            BLOG_W("Timeout: OSCam no respondió con CA_SET_DESCR en 10 s");
            BLOG_W("Posibles causas:");
            BLOG_W("  - El PID 0x%04X no corresponde a un ECM activo", ecmPid);
            BLOG_W("  - OSCam no tiene reader configurado para ese CAID");
            BLOG_W("  - Revisa oscam.log en el servidor para ver si recibió CA_SET_PID");
        }
    }

    // ---------------------------------------------------------------------------
    // Limpiar y detener DMX_STOP antes de salir
    // ---------------------------------------------------------------------------
    BLOG_I("Enviando DMX_STOP para liberar el filtro");
    client.sendDmxStop(0, 0, 0, static_cast<uint16_t>(ecmPid));

    // Pequeña espera para que el mensaje se envíe antes de cerrar.
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    client.stop();

    if (cwReceived) {
        BLOG_I("=== PRUEBA EXITOSA ===");
        BLOG_I("Control Word recibido: [%s] (parity=%s)",
               DvbapiProtocol::cwToHex(
                   std::span<const uint8_t, kCwLen>(receivedDescr.cw, kCwLen)).c_str(),
               receivedDescr.parity == kEvenKeyIndex ? "even" : "odd");
        return EXIT_SUCCESS;
    } else {
        BLOG_W("=== PRUEBA SIN CW (protocolo conectado, pero sin respuesta de descifrado) ===");
        return EXIT_FAILURE;
    }
}
