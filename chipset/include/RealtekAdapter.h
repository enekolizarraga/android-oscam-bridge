// chipset/include/RealtekAdapter.h
//
// Implementación de IChipsetAdapter para chipsets Realtek (RTD2851, RTD1319).
//
// DEVICE-SPECIFIC:
// Los SoCs Realtek RTD2851 y RTD1319 son empleados en Android TV de marcas
// como Xiaomi, Realme y gamas de entrada de TCL. Realtek organiza el pipeline
// DVB alrededor de sus controladores DTV específicos (/dev/rtk_demux, /dev/rtk_ca).
//
// Autor: android-oscam-bridge (Fase 2)

#pragma once

#include "IChipsetAdapter.h"
#include <string>
#include <atomic>

namespace oscam::chipset {

class RealtekAdapter : public IChipsetAdapter {
public:
    explicit RealtekAdapter(std::string customDevicePath = "");
    ~RealtekAdapter() override;

    ChipsetType getChipsetType() const noexcept override {
        return ChipsetType::kRealtek;
    }

    std::string getChipsetName() const noexcept override {
        return "Realtek RTD (RTD2851/RTD1319)";
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
    int rtkCaFd_{-1};
    std::atomic<bool> initialized_{false};
    uint16_t registeredCaid_{0};
};

} // namespace oscam::chipset

