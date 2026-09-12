// hal/native/OscamCasPlugin.cpp
//
// Implementación agnóstica de OscamCasPlugin.
// Conecta la recepción de ECMs y sesiones del framework con DvbapiClient
// y delega la inyección del Control Word en IChipsetAdapter.

#include "include/OscamCasPlugin.h"
#include "../../bridge/include/BridgeLogger.h"

#include <cstring>
#include <algorithm>

namespace oscam::hal {

OscamCasPlugin::OscamCasPlugin(
    uint16_t caSystemId,
    std::shared_ptr<IOscamPluginListener> listener,
    std::shared_ptr<chipset::IChipsetAdapter> chipsetAdapter,
    std::shared_ptr<dvbapi::DvbapiClient> dvbapiClient)
    : caSystemId_(caSystemId)
    , listener_(std::move(listener))
    , chipsetAdapter_(std::move(chipsetAdapter))
    , dvbapiClient_(std::move(dvbapiClient)) {

    BLOG_I("[OscamCasPlugin] Creando plugin para CAID 0x%04X", caSystemId_);

    if (chipsetAdapter_) {
        chipsetAdapter_->initialize();
        chipsetAdapter_->registerCasSystemId(caSystemId_);
    } else {
        BLOG_E("[OscamCasPlugin] ChipsetAdapter es nulo");
    }
}

OscamCasPlugin::~OscamCasPlugin() {
    BLOG_I("[OscamCasPlugin] Destruyendo plugin para CAID 0x%04X", caSystemId_);
    std::lock_guard<std::mutex> lock(sessionsMutex_);
    for (auto& [handle, session] : sessions_) {
        if (dvbapiClient_) {
            dvbapiClient_->sendDmxStop(0, 0, static_cast<uint8_t>(session.demuxIndex),
                                       static_cast<uint16_t>(session.ecmPid));
        }
    }
    sessions_.clear();
}

int32_t OscamCasPlugin::openSession() {
    const int32_t handle = nextSessionHandle_.fetch_add(1, std::memory_order_relaxed);

    CasSession session{};
    session.handle = handle;
    session.caSystemId = caSystemId_;
    session.demuxIndex = 0;

    // Consultar al chipset adapter si conoce el ECM PID activo
    if (chipsetAdapter_) {
        const int32_t ecmPid = chipsetAdapter_->getEcmPidFromDemux(session.demuxIndex, 0);
        session.ecmPid = (ecmPid > 0) ? static_cast<uint32_t>(ecmPid) : 0x0600;
    } else {
        session.ecmPid = 0x0600;
    }

    {
        std::lock_guard<std::mutex> lock(sessionsMutex_);
        sessions_[handle] = session;
    }

    BLOG_I("[OscamCasPlugin] Sesión abierta handle=%d CAID=0x%04X ECM_PID=0x%04X",
           handle, caSystemId_, session.ecmPid);

    // Notificar a OSCam mediante dvbapi
    if (dvbapiClient_) {
        dvbapi::CaPid caPid{};
        caPid.pid = session.ecmPid;
        caPid.index = handle; // Mapear slot a handle de sesión
        dvbapiClient_->sendCaSetPid(0, caPid);

        // Instalar filtro DMX para capturar ECMs
        dvbapi::DmxFilter filter{};
        filter.adapterId = 0;
        filter.demuxId = static_cast<uint8_t>(session.demuxIndex);
        filter.filterId = static_cast<uint8_t>(handle & 0xFF);
        filter.pid = static_cast<uint16_t>(session.ecmPid);
        filter.filter[0] = 0x80;
        filter.mask[0] = 0xFE; // Capturar 0x80 y 0x81
        filter.flags = 1;      // Inmediato
        dvbapiClient_->sendDmxSetFilter(filter);
    }

    return handle;
}

bool OscamCasPlugin::closeSession(int32_t sessionHandle) {
    std::lock_guard<std::mutex> lock(sessionsMutex_);
    auto it = sessions_.find(sessionHandle);
    if (it == sessions_.end()) {
        BLOG_W("[OscamCasPlugin] Intento de cerrar sesión inexistente handle=%d", sessionHandle);
        return false;
    }

    if (dvbapiClient_) {
        dvbapiClient_->sendDmxStop(0, 0,
                                   static_cast<uint8_t>(it->second.demuxIndex),
                                   static_cast<uint16_t>(it->second.ecmPid));

        // Desregistrar PID en OSCam
        dvbapi::CaPid caPid{};
        caPid.pid = it->second.ecmPid;
        caPid.index = -1; // -1 desregistra
        dvbapiClient_->sendCaSetPid(0, caPid);
    }

    sessions_.erase(it);
    BLOG_I("[OscamCasPlugin] Sesión cerrada handle=%d", sessionHandle);
    return true;
}

bool OscamCasPlugin::processEcm(int32_t sessionHandle, const std::vector<uint8_t>& ecmData) {
    if (ecmData.empty() || ecmData.size() > kMaxEcmSize) {
        BLOG_E("[OscamCasPlugin] ECM de tamaño inválido: %zu bytes", ecmData.size());
        if (listener_) {
            listener_->onSessionError(sessionHandle, CasStatus::kErrorInvalidParameter);
        }
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(sessionsMutex_);
        if (sessions_.find(sessionHandle) == sessions_.end()) {
            BLOG_E("[OscamCasPlugin] Sesión handle=%d no encontrada", sessionHandle);
            return false;
        }
    }

    BLOG_D("[OscamCasPlugin] Procesando ECM (%zu bytes) para sesión=%d",
           ecmData.size(), sessionHandle);

    // En protocolo dvbapi, OSCam procesa los paquetes que recibe a través del filtro
    // demux configurado o por socket.
    return true;
}

bool OscamCasPlugin::processEmm(const std::vector<uint8_t>& emmData) {
    if (emmData.empty() || emmData.size() > kMaxEmmSize) {
        BLOG_E("[OscamCasPlugin] EMM de tamaño inválido: %zu", emmData.size());
        return false;
    }

    BLOG_D("[OscamCasPlugin] EMM recibido (%zu bytes) - Enrutando actualización de suscripción",
           emmData.size());
    return true;
}

std::vector<uint8_t> OscamCasPlugin::getControlWord(int32_t sessionHandle) {
    std::lock_guard<std::mutex> lock(sessionsMutex_);
    auto it = sessions_.find(sessionHandle);
    if (it == sessions_.end()) {
        return {};
    }

    std::vector<uint8_t> cw;
    cw.reserve(16);
    if (it->second.hasEvenCw) {
        cw.insert(cw.end(), it->second.lastEvenCw.begin(), it->second.lastEvenCw.end());
    }
    if (it->second.hasOddCw) {
        cw.insert(cw.end(), it->second.lastOddCw.begin(), it->second.lastOddCw.end());
    }
    return cw;
}

void OscamCasPlugin::handleControlWord(const dvbapi::CaDescr& descr) {
    std::vector<uint8_t> cwVec(descr.cw, descr.cw + 8);
    int32_t sessionHandle = descr.index;

    BLOG_I("[OscamCasPlugin] CW recibido de OSCam para sessionHandle/slot=%d, parity=%d",
           sessionHandle, descr.parity);

    {
        std::lock_guard<std::mutex> lock(sessionsMutex_);
        auto it = sessions_.find(sessionHandle);
        if (it != sessions_.end()) {
            if (descr.parity == 0) {
                std::memcpy(it->second.lastEvenCw.data(), descr.cw, 8);
                it->second.hasEvenCw = true;
            } else {
                std::memcpy(it->second.lastOddCw.data(), descr.cw, 8);
                it->second.hasOddCw = true;
            }
        }
    }

    // 1. Inyectar CW en el hardware a través del IChipsetAdapter (¡Agnóstico del SoC!)
    if (chipsetAdapter_) {
        chipset::KeyInjectionParams params{};
        params.sessionHandle = sessionHandle;
        params.streamIndex = sessionHandle;
        params.parity = descr.parity;
        std::memcpy(params.cw, descr.cw, 8);
        params.requiresTeeRouting = false;

        const bool injected = chipsetAdapter_->injectControlWord(params);
        if (!injected) {
            BLOG_E("[OscamCasPlugin] Fallo al inyectar CW en chipset adapter");
            if (listener_) {
                listener_->onSessionError(sessionHandle, CasStatus::kErrorDecryptionFailed);
            }
            return;
        }
        BLOG_D("[OscamCasPlugin] CW inyectado exitosamente en descrambler hardware");
    }

    // 2. Notificar al listener de Android CAS framework
    if (listener_) {
        listener_->onControlWordReady(sessionHandle, cwVec);
    }
}

} // namespace oscam::hal

