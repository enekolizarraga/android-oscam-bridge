// chipset/include/MediaTekAdapter.h
//
// Implementación de IChipsetAdapter para chipsets MediaTek (MT5895, MT9632, etc.).
//
// DEVICE-SPECIFIC:
// Los televisores Philips, Sony y TCL con chips MediaTek utilizan habitualmente
// la capa de drivers MTK DTV (/dev/mtk_demux, /dev/dtv_ca o /dev/mtk_sec).
// El descrambler de MediaTek gestiona múltiples slots de descifrado compartidos
// entre sintonizador satélite (DVB-S2) y cable/TDT (DVB-C/T2).
//
// Autor: android-oscam-bridge (Fase 2)

#pragma once

#include "IChipsetAdapter.h"
#include <string>
#include <atomic>

namespace oscam::chipset {

class MediaTekAdapter : public IChipsetAdapter {
public:
    explicit MediaTekAdapter(std::string customDevicePath = "");
    ~MediaTekAdapter() override;

    ChipsetType getChipsetType() const noexcept override {
        return ChipsetType::kMediaTek;
    }

    std::string getChipsetName() const noexcept override {
        return "MediaTek DTV (MT5895/MT9632/Pentonic)";
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
    int mtkCaFd_{-1};
    std::atomic<bool> initialized_{false};
    uint16_t registeredCaid_{0};
};

} // namespace oscam::chipset

