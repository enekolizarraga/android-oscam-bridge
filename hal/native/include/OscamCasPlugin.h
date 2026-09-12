// hal/native/include/OscamCasPlugin.h
//
// Implementación agnóstica de la lógica CAS y orquestación entre Tuner HAL,
// IChipsetAdapter y DvbapiClient.
//
// Autor: android-oscam-bridge (Fase 3)

#pragma once

#include "OscamTypes.h"
#include "../../../chipset/include/IChipsetAdapter.h"
#include "../../../bridge/include/DvbapiClient.h"

#include <memory>
#include <mutex>
#include <unordered_map>
#include <atomic>
#include <functional>
#include <vector>

namespace oscam::hal {

class IOscamPluginListener {
public:
    virtual ~IOscamPluginListener() = default;
    virtual void onControlWordReady(int32_t sessionHandle, const std::vector<uint8_t>& cw) = 0;
    virtual void onSessionError(int32_t sessionHandle, CasStatus error) = 0;
};

/**
 * @brief Plugin CAS agnóstico del SoC. Solo interactúa con IChipsetAdapter
 * y DvbapiClient.
 */
class OscamCasPlugin {
public:
    OscamCasPlugin(uint16_t caSystemId,
                   std::shared_ptr<IOscamPluginListener> listener,
                   std::shared_ptr<chipset::IChipsetAdapter> chipsetAdapter,
                   std::shared_ptr<dvbapi::DvbapiClient> dvbapiClient);

    ~OscamCasPlugin();

    uint16_t getCaSystemId() const noexcept { return caSystemId_; }

    int32_t openSession();
    bool closeSession(int32_t sessionHandle);
    bool processEcm(int32_t sessionHandle, const std::vector<uint8_t>& ecmData);
    bool processEmm(const std::vector<uint8_t>& emmData);
    std::vector<uint8_t> getControlWord(int32_t sessionHandle);

    /**
     * @brief Invocado por DvbapiClient cuando OSCam devuelve un CW resuelto.
     */
    void handleControlWord(const dvbapi::CaDescr& descr);

private:
    uint16_t caSystemId_{0};
    std::shared_ptr<IOscamPluginListener> listener_;
    std::shared_ptr<chipset::IChipsetAdapter> chipsetAdapter_;
    std::shared_ptr<dvbapi::DvbapiClient> dvbapiClient_;

    std::mutex sessionsMutex_;
    std::unordered_map<int32_t, CasSession> sessions_;
    std::atomic<int32_t> nextSessionHandle_{1};
};

} // namespace oscam::hal

