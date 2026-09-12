// hal/native/OscamCasService.cpp
//
// Implementación del servicio HAL CAS.

#include "include/OscamCasService.h"
#include "include/ConfigWatcher.h"
#include "../../../chipset/include/ChipsetDetector.h"
#include "../../../bridge/include/BridgeLogger.h"

namespace oscam::hal {

OscamCasService::OscamCasService(ServiceConfig config)
    : config_(std::move(config)) {
    for (uint16_t caid : config_.supportedCaids) {
        supportedCaids_.insert(caid);
    }
}

OscamCasService::~OscamCasService() {
    if (configWatcher_) {
        configWatcher_->stop();
    }
    if (dvbapiClient_) {
        dvbapiClient_->stop();
    }
    if (chipsetAdapter_) {
        chipsetAdapter_->release();
    }
}

bool OscamCasService::initialize() {
    BLOG_I("[OscamCasService] Inicializando HAL OSCam CAS...");

    // 1. Detectar el chipset del dispositivo de forma automática
    chipsetAdapter_ = chipset::ChipsetDetector::detect();
    if (!chipsetAdapter_) {
        BLOG_E("[OscamCasService] Fallo crítico: Dispositivo/SoC no soportado");
        return false;
    }

    BLOG_I("[OscamCasService] Adaptador de hardware activo: %s",
           chipsetAdapter_->getChipsetName().c_str());

    if (!chipsetAdapter_->initialize()) {
        BLOG_E("[OscamCasService] Fallo al inicializar adaptador de hardware");
        return false;
    }

    // 2. Configurar cliente dvbapi hacia el servidor OSCam
    dvbapi::ConnectionConfig connCfg;
    connCfg.host = config_.oscamHost;
    connCfg.port = config_.oscamPort;
    connCfg.connectTimeoutSec = 5;
    connCfg.recvTimeoutSec = 10;
    connCfg.maxReconnectAttempts = 0; // Reconexión continua en servicio daemon
    connCfg.initialBackoffMs = 500;
    connCfg.maxBackoffMs = 30000;

    dvbapi::DvbapiCallbacks callbacks;
    callbacks.OnCaSetDescr = [this](const dvbapi::CaDescr& descr) {
        std::lock_guard<std::mutex> lock(serviceMutex_);
        for (auto& plugin : activePlugins_) {
            plugin->handleControlWord(descr);
        }
    };

    callbacks.OnConnectionChanged = [](bool connected) {
        BLOG_I("[OscamCasService] Estado de conexión dvbapi: %s",
               connected ? "CONECTADO" : "DESCONECTADO");
    };

    callbacks.OnFatalError = [](const std::string& reason) {
        BLOG_E("[OscamCasService] Error fatal en conexión dvbapi: %s", reason.c_str());
    };

    dvbapiClient_ = std::make_shared<dvbapi::DvbapiClient>(connCfg, std::move(callbacks));
    dvbapiClient_->start();

    // 3. Iniciar monitor de recarga en caliente de configuración sin recompilar
    configWatcher_ = std::make_shared<ConfigWatcher>(
        "/data/vendor/oscam/config.json",
        [this](const ServiceConfig& newConfig) {
            reloadConfig(newConfig);
        }
    );
    configWatcher_->start();

    return true;
}

bool OscamCasService::reloadConfig(const ServiceConfig& newConfig) {
    std::lock_guard<std::mutex> lock(serviceMutex_);
    BLOG_I("[OscamCasService] Recargando servidor OSCam a %s:%u (sin recompilar)",
           newConfig.oscamHost.c_str(), newConfig.oscamPort);

    config_.oscamHost = newConfig.oscamHost;
    config_.oscamPort = newConfig.oscamPort;

    if (dvbapiClient_) {
        dvbapiClient_->stop();
    }

    dvbapi::ConnectionConfig connCfg;
    connCfg.host = config_.oscamHost;
    connCfg.port = config_.oscamPort;
    connCfg.connectTimeoutSec = 5;
    connCfg.recvTimeoutSec = 10;
    connCfg.maxReconnectAttempts = 0;
    connCfg.initialBackoffMs = 500;
    connCfg.maxBackoffMs = 30000;

    dvbapi::DvbapiCallbacks callbacks;
    callbacks.OnCaSetDescr = [this](const dvbapi::CaDescr& descr) {
        std::lock_guard<std::mutex> lk(serviceMutex_);
        for (auto& plugin : activePlugins_) {
            plugin->handleControlWord(descr);
        }
    };

    callbacks.OnConnectionChanged = [](bool connected) {
        BLOG_I("[OscamCasService] Estado dvbapi tras recarga en caliente: %s",
               connected ? "CONECTADO" : "DESCONECTADO");
    };

    callbacks.OnFatalError = [](const std::string& reason) {
        BLOG_E("[OscamCasService] Error en cliente recargado: %s", reason.c_str());
    };

    dvbapiClient_ = std::make_shared<dvbapi::DvbapiClient>(connCfg, std::move(callbacks));
    dvbapiClient_->start();

    return true;
}

bool OscamCasService::isSystemIdSupported(int32_t caSystemId) const noexcept {
    std::lock_guard<std::mutex> lock(serviceMutex_);
    const auto uCaid = static_cast<uint16_t>(caSystemId & 0xFFFF);
    return supportedCaids_.find(uCaid) != supportedCaids_.end();
}

std::shared_ptr<OscamCasPlugin> OscamCasService::createPlugin(
    int32_t caSystemId,
    std::shared_ptr<IOscamPluginListener> listener) {

    const auto uCaid = static_cast<uint16_t>(caSystemId & 0xFFFF);
    if (!isSystemIdSupported(caSystemId)) {
        BLOG_W("[OscamCasService] Solicitud de plugin para CAID no soportado: 0x%04X", uCaid);
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(serviceMutex_);
    auto plugin = std::make_shared<OscamCasPlugin>(
        uCaid,
        std::move(listener),
        chipsetAdapter_,
        dvbapiClient_);

    activePlugins_.push_back(plugin);
    return plugin;
}

void OscamCasService::addSupportedCaid(uint16_t caid) {
    std::lock_guard<std::mutex> lock(serviceMutex_);
    supportedCaids_.insert(caid);
    BLOG_I("[OscamCasService] CAID 0x%04X añadido a la lista soportada", caid);
}

} // namespace oscam::hal

