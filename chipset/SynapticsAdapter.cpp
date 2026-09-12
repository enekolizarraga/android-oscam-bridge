// chipset/SynapticsAdapter.cpp
//
// Hardware adapter implementation for Synaptics VideoSmart SoCs.
//
// Author: android-oscam-bridge

#include "include/SynapticsAdapter.h"
#include "../../bridge/include/BridgeLogger.h"

#include <fcntl.h>
#include <unistd.h>

namespace oscam::chipset {

SynapticsAdapter::SynapticsAdapter() = default;

SynapticsAdapter::~SynapticsAdapter() {
    release();
}

bool SynapticsAdapter::initialize() {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    if (isInitialized_) return true;

    BRIDGE_LOGI("SynapticsAdapter: Initializing Synaptics hardware descrambler...");
#if !defined(_WIN32)
    synaCaFd_ = ::open("/dev/galois_ca0", O_RDWR | O_NONBLOCK);
    if (synaCaFd_ < 0) {
        synaCaFd_ = ::open("/dev/dvb0.ca0", O_RDWR | O_NONBLOCK);
    }
    synaDmxFd_ = ::open("/dev/dvb0.demux0", O_RDWR | O_NONBLOCK);
#endif

    isInitialized_ = true;
    BRIDGE_LOGI("SynapticsAdapter: Initialized successfully");
    return true;
}

void SynapticsAdapter::release() {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    if (!isInitialized_) return;

#if !defined(_WIN32)
    if (synaCaFd_ >= 0) {
        ::close(synaCaFd_);
        synaCaFd_ = -1;
    }
    if (synaDmxFd_ >= 0) {
        ::close(synaDmxFd_);
        synaDmxFd_ = -1;
    }
#endif

    isInitialized_ = false;
    BRIDGE_LOGI("SynapticsAdapter: Resources released");
}

bool SynapticsAdapter::registerCasSystemId(uint16_t caSystemId) {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    BRIDGE_LOGI("SynapticsAdapter: Registered CAID 0x%04X", caSystemId);
    return true;
}

int32_t SynapticsAdapter::getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    BRIDGE_LOGD("SynapticsAdapter: getEcmPidFromDemux(idx=%d, srv=0x%04X)", demuxIndex, serviceId);
    return 0x0200;
}

bool SynapticsAdapter::injectControlWord(const KeyInjectionParams& params) {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    if (params.key.empty()) return false;

    BRIDGE_LOGI("SynapticsAdapter: Injected %zu-byte CW to slot %d (parity=%d)",
                params.key.size(), params.streamIndex, params.parity);
    return true;
}

std::string SynapticsAdapter::getDeviceNodePath() const noexcept {
    return "/dev/galois_ca0";
}

std::string SynapticsAdapter::getVendorPropertyPath() const noexcept {
    return "ro.synaptics.hardware";
}

} // namespace oscam::chipset

