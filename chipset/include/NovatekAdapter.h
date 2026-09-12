// chipset/include/NovatekAdapter.h
//
// Hardware adapter for Novatek DTV SoCs (NT72671, NT72688, NT72690).
// Common in Hisense, Skyworth, Toshiba, and Sharp Android TVs.
//
// Author: android-oscam-bridge

#pragma once

#include "IChipsetAdapter.h"
#include <mutex>

namespace oscam::chipset {

class NovatekAdapter : public IChipsetAdapter {
public:
    NovatekAdapter();
    ~NovatekAdapter() override;

    ChipsetType getChipsetType() const noexcept override { return ChipsetType::kNovatek; }
    std::string getChipsetName() const noexcept override { return "Novatek DTV (NT72xxx)"; }

    bool registerCasSystemId(uint16_t caSystemId) override;
    int32_t getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) override;
    bool injectControlWord(const KeyInjectionParams& params) override;
    std::string getDeviceNodePath() const noexcept override;
    std::string getVendorPropertyPath() const noexcept override;
    bool initialize() override;
    void release() override;

private:
    int nvtCaFd_{-1};
    int nvtDmxFd_{-1};
    std::mutex adapterMutex_;
    bool isInitialized_{false};
};

} // namespace oscam::chipset

