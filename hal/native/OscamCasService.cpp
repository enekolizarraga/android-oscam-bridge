// hal/native/OscamCasService.cpp
//
// Implementación del servicio HAL CAS.

#include "include/OscamCasService.h"
#include "include/ConfigWatcher.h"
#include "../../../chipset/include/ChipsetDetector.h"
#include "../../../bridge/include/BridgeLogger.h"
#include "../../../bridge/include/NewcamdClient.h"
#include "../../../bridge/include/CCcamClient.h"
#include <cstring>

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
    if (newcamdClient_) {
        newcamdClient_->stop();
    }
    if (cccamClient_) {
        cccamClient_->stop();
    }
    if (chipsetAdapter_) {
        chipsetAdapter_->release();
    }
}

void OscamCasService::startNetworkClient() {
    if (dvbapiClient_) {
        dvbapiClient_->stop();
        dvbapiClient_.reset();
    }
    if (newcamdClient_) {
        newcamdClient_->stop();
        newcamdClient_.reset();
    }
    if (cccamClient_) {
        cccamClient_->stop();
        cccamClient_.reset();
    }

    ServerConfig activeSrv;
    if (!config_.servers.empty()) {
        activeSrv = config_.servers.front();
        for (const auto& s : config_.servers) {
            if (s.isPrimary && s.enabled) {
                activeSrv = s;
                break;
            }
        }
    } else {
        activeSrv.host = config_.oscamHost;
        activeSrv.port = config_.oscamPort;
        activeSrv.protocol = "DVBAPI";
    }

    BLOG_I("[OscamCasService] Conectando cliente protocolo '%s' -> %s:%u (user='%s', timeout=%ds, reconnect=%dms)",
           activeSrv.protocol.c_str(), activeSrv.host.c_str(), activeSrv.port,
           activeSrv.user.c_str(), activeSrv.connectTimeoutSec, activeSrv.reconnectIntervalMs);

    if (activeSrv.protocol == "CCCAM") {
        cccam::CCcamConfig cccamCfg;
        cccamCfg.host = activeSrv.host;
        cccamCfg.port = activeSrv.port;
        cccamCfg.user = activeSrv.user;
        cccamCfg.password = activeSrv.password;
        cccamCfg.caid = activeSrv.caid;
        cccamCfg.connectTimeoutSec = activeSrv.connectTimeoutSec;
        cccamCfg.recvTimeoutSec = activeSrv.recvTimeoutSec;
        cccamCfg.reconnectIntervalMs = activeSrv.reconnectIntervalMs;

        cccam::CCcamCallbacks cbs;
        cbs.onControlWord = [this](uint16_t sid, uint8_t parity, const uint8_t* cw, size_t len) {
            std::lock_guard<std::mutex> lk(serviceMutex_);
            dvbapi::CaDescr descr;
            descr.index = 0;
            descr.parity = parity;
            std::memcpy(descr.cw, cw, std::min<size_t>(len, 8));
            for (auto& plugin : activePlugins_) {
                plugin->handleControlWord(descr);
            }
        };
        cbs.onConnectionChanged = [](bool connected) {
            BLOG_I("[OscamCasService] CCcam connection: %s", connected ? "CONECTADO" : "DESCONECTADO");
        };

        cccamClient_ = std::make_shared<cccam::CCcamClient>(cccamCfg, std::move(cbs));
        cccamClient_->start();
    } else if (activeSrv.protocol == "NEWCAMD") {
        newcamd::NewcamdConfig ncfg;
        ncfg.host = activeSrv.host;
        ncfg.port = activeSrv.port;
        ncfg.user = activeSrv.user;
        ncfg.password = activeSrv.password;
        ncfg.desKey = newcamd::NewcamdClient::parseDesKeyHex(activeSrv.desKey);
        ncfg.caid = activeSrv.caid;
        ncfg.connectTimeoutSec = activeSrv.connectTimeoutSec;
        ncfg.recvTimeoutSec = activeSrv.recvTimeoutSec;
        ncfg.reconnectIntervalMs = activeSrv.reconnectIntervalMs;

        newcamd::NewcamdCallbacks cbs;
        cbs.onControlWord = [this](uint16_t sid, uint8_t parity, const uint8_t* cw, size_t len) {
            std::lock_guard<std::mutex> lk(serviceMutex_);
            dvbapi::CaDescr descr;
            descr.index = 0;
            descr.parity = parity;
            std::memcpy(descr.cw, cw, std::min<size_t>(len, 8));
            for (auto& plugin : activePlugins_) {
                plugin->handleControlWord(descr);
            }
        };
        cbs.onConnectionChanged = [](bool connected) {
            BLOG_I("[OscamCasService] Newcamd connection: %s", connected ? "CONECTADO" : "DESCONECTADO");
        };

        newcamdClient_ = std::make_shared<newcamd::NewcamdClient>(ncfg, std::move(cbs));
        newcamdClient_->start();
    } else {
        // OSCam DVBAPI (default)
        dvbapi::ConnectionConfig connCfg;
        connCfg.host = activeSrv.host;
        connCfg.port = activeSrv.port;
        connCfg.connectTimeoutSec = activeSrv.connectTimeoutSec;
        connCfg.recvTimeoutSec = activeSrv.recvTimeoutSec;
        connCfg.maxReconnectAttempts = 0;
        connCfg.initialBackoffMs = 500;
        connCfg.maxBackoffMs = activeSrv.reconnectIntervalMs > 0 ? activeSrv.reconnectIntervalMs : 30000;

        dvbapi::DvbapiCallbacks callbacks;
        callbacks.OnCaSetDescr = [this](const dvbapi::CaDescr& descr) {
            std::lock_guard<std::mutex> lock(serviceMutex_);
            for (auto& plugin : activePlugins_) {
                plugin->handleControlWord(descr);
            }
        };
        callbacks.OnConnectionChanged = [](bool connected) {
            BLOG_I("[OscamCasService] DVBAPI connection: %s", connected ? "CONECTADO" : "DESCONECTADO");
        };
        callbacks.OnFatalError = [](const std::string& reason) {
            BLOG_E("[OscamCasService] Error fatal en conexion dvbapi: %s", reason.c_str());
        };

        dvbapiClient_ = std::make_shared<dvbapi::DvbapiClient>(connCfg, std::move(callbacks));
        dvbapiClient_->start();
    }
}

bool OscamCasService::initialize() {
    BLOG_I("[OscamCasService] Inicializando HAL OSCam CAS...");

    // 1. Detectar el chipset del dispositivo de forma automatica
    chipsetAdapter_ = chipset::ChipsetDetector::detect();
    if (!chipsetAdapter_) {
        BLOG_E("[OscamCasService] Fallo critico: Dispositivo/SoC no soportado");
        return false;
    }

    BLOG_I("[OscamCasService] Adaptador de hardware activo: %s",
           chipsetAdapter_->getChipsetName().c_str());

    if (!chipsetAdapter_->initialize()) {
        BLOG_E("[OscamCasService] Fallo al inicializar adaptador de hardware");
        return false;
    }

    // 2. Iniciar cliente de red activo segun configuracion dinamica
    startNetworkClient();

    // 3. Iniciar monitor de recarga en caliente de configuracion sin recompilar
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
    BLOG_I("[OscamCasService] Recargando configuracion de servidor en caliente (sin recompilar)");

    config_ = newConfig;
    startNetworkClient();
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

