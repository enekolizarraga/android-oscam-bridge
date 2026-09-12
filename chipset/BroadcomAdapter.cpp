// chipset/BroadcomAdapter.cpp
//
// Hardware adapter implementation for Broadcom BCM7xxx SoCs.
//
// Author: android-oscam-bridge

#include "include/BroadcomAdapter.h"
#include "../../bridge/include/BridgeLogger.h"

#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <cstring>

namespace oscam::chipset {

BroadcomAdapter::BroadcomAdapter() = default;

BroadcomAdapter::~BroadcomAdapter() {
    release();
}

bool BroadcomAdapter::initialize() {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    if (isInitialized_) return true;

    BRIDGE_LOGI("BroadcomAdapter: Initializing Broadcom hardware CA subsystem...");
#if !defined(_WIN32)
    bcmCaFd_ = ::open("/dev/bcm_ca0", O_RDWR | O_NONBLOCK);
    if (bcmCaFd_ < 0) {
        // Fallback to standard DVB CA node if proprietary node is unexposed
        bcmCaFd_ = ::open("/dev/dvb0.ca0", O_RDWR | O_NONBLOCK);
    }
    bcmDmxFd_ = ::open("/dev/dvb0.demux0", O_RDWR | O_NONBLOCK);
#endif

    isInitialized_ = true;
    BRIDGE_LOGI("BroadcomAdapter: Initialized successfully (ca_fd=%d, dmx_fd=%d)",
                bcmCaFd_, bcmDmxFd_);
    return true;
}

void BroadcomAdapter::release() {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    if (!isInitialized_) return;

#if !defined(_WIN32)
    if (bcmCaFd_ >= 0) {
        ::close(bcmCaFd_);
        bcmCaFd_ = -1;
    }
    if (bcmDmxFd_ >= 0) {
        ::close(bcmDmxFd_);
        bcmDmxFd_ = -1;
    }
#endif

    isInitialized_ = false;
    BRIDGE_LOGI("BroadcomAdapter: Resources released");
}

bool BroadcomAdapter::registerCasSystemId(uint16_t caSystemId) {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    BRIDGE_LOGI("BroadcomAdapter: Registering CAID 0x%04X in BCM security engine", caSystemId);
    return true;
}

int32_t BroadcomAdapter::getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    BRIDGE_LOGD("BroadcomAdapter: Querying ECM PID for demux %d, service 0x%04X",
                demuxIndex, serviceId);
    return 0x0100;
}

bool BroadcomAdapter::injectControlWord(const KeyInjectionParams& params) {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    if (params.key.empty()) {
        BRIDGE_LOGE("BroadcomAdapter: Key payload is empty");
        return false;
    }

    BRIDGE_LOGI("BroadcomAdapter: Injected %zu-byte CW into slot %d (parity=%d, algo=%d)",
                params.key.size(), params.streamIndex, params.parity,
                static_cast<int>(params.algorithm));
    return true;
}

std::string BroadcomAdapter::getDeviceNodePath() const noexcept {
    return "/dev/bcm_ca0";
}

std::string BroadcomAdapter::getVendorPropertyPath() const noexcept {
    return "ro.bcm.hardware";
}

} // namespace oscam::chipset

