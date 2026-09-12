// chipset/include/SynapticsAdapter.h
//
// Hardware adapter for Synaptics / Marvell VideoSmart SoCs (VS680, Berlin BG4CT).
//
// Author: android-oscam-bridge

#pragma once

#include "IChipsetAdapter.h"
#include <mutex>

namespace oscam::chipset {

class SynapticsAdapter : public IChipsetAdapter {
public:
    SynapticsAdapter();
    ~SynapticsAdapter() override;

    ChipsetType getChipsetType() const noexcept override { return ChipsetType::kSynaptics; }
    std::string getChipsetName() const noexcept override { return "Synaptics VideoSmart (VS680/Berlin)"; }

    bool registerCasSystemId(uint16_t caSystemId) override;
    int32_t getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) override;
    bool injectControlWord(const KeyInjectionParams& params) override;
    std::string getDeviceNodePath() const noexcept override;
    std::string getVendorPropertyPath() const noexcept override;
    bool initialize() override;
    void release() override;

private:
    int synaDmxFd_{-1};
    int synaCaFd_{-1};
    std::mutex adapterMutex_;
    bool isInitialized_{false};
};

} // namespace oscam::chipset

