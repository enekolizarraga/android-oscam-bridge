// chipset/include/AmlogicAdapter.h
//
// Implementación de IChipsetAdapter para chipsets Amlogic (S905, S905X, S928X).
//
// DEVICE-SPECIFIC:
// Los SoCs Amlogic Meson exponen demuxes y descramblers a través de nodos DVB
// estándar (/dev/dvb0.ca0, /dev/dvb0.demux0) o nodos propietarios de AmLogic (/dev/amstream_demux).
// Para descifrado DVB-CSA plano en userspace, utilizan las llamadas ioctl CA_SET_DESCR de Linux DVB v3.
// En caso de modo seguro activado (DRM/SVP), requieren desvío hacia Amlogic Secure OS / OP-TEE vía SMC.
//
// Autor: android-oscam-bridge (Fase 2)

#pragma once

#include "IChipsetAdapter.h"
#include <string>
#include <atomic>

namespace oscam::chipset {

class AmlogicAdapter : public IChipsetAdapter {
public:
    explicit AmlogicAdapter(std::string customDevicePath = "");
    ~AmlogicAdapter() override;

    ChipsetType getChipsetType() const noexcept override {
        return ChipsetType::kAmlogic;
    }

    std::string getChipsetName() const noexcept override {
        return "Amlogic Meson (S905/S905X/S928X)";
    }

    bool registerCasSystemId(uint16_t caSystemId) override;
    int32_t getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) override;
    bool injectControlWord(const KeyInjectionParams& params) override;
    std::string getDeviceNodePath() const noexcept override;
    std::string getVendorPropertyPath() const noexcept override;
    bool initialize() override;
    void release() override;

private:
    std::string devicePath_;
    int caDeviceFd_{-1};
    int demuxDeviceFd_{-1};
    std::atomic<bool> initialized_{false};
    uint16_t registeredCaid_{0};
};

} // namespace oscam::chipset

