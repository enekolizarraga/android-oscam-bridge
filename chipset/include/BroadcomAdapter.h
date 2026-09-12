// chipset/include/BroadcomAdapter.h
//
// Hardware adapter for Broadcom Android TV SoCs (BCM7252, BCM72604, BCM72180, BCM7444).
// Widely used in Technicolor, Humax, Bouygues, and Swisscom set-top boxes.
//
// Author: android-oscam-bridge

#pragma once

#include "IChipsetAdapter.h"
#include <mutex>

namespace oscam::chipset {

class BroadcomAdapter : public IChipsetAdapter {
public:
    BroadcomAdapter();
    ~BroadcomAdapter() override;

    ChipsetType getChipsetType() const noexcept override { return ChipsetType::kBroadcom; }
    std::string getChipsetName() const noexcept override { return "Broadcom BCM7xxx"; }

    bool registerCasSystemId(uint16_t caSystemId) override;
    int32_t getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) override;
    bool injectControlWord(const KeyInjectionParams& params) override;
    std::string getDeviceNodePath() const noexcept override;
    std::string getVendorPropertyPath() const noexcept override;
    bool initialize() override;
    void release() override;

private:
    int bcmCaFd_{-1};
    int bcmDmxFd_{-1};
    std::mutex adapterMutex_;
    bool isInitialized_{false};
};

} // namespace oscam::chipset

