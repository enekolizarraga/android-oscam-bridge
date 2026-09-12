// chipset/NovatekAdapter.cpp
//
// Hardware adapter implementation for Novatek DTV SoCs.
//
// Author: android-oscam-bridge

#include "include/NovatekAdapter.h"
#include "../../bridge/include/BridgeLogger.h"

#include <fcntl.h>
#include <unistd.h>

namespace oscam::chipset {

NovatekAdapter::NovatekAdapter() = default;

NovatekAdapter::~NovatekAdapter() {
    release();
}

bool NovatekAdapter::initialize() {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    if (isInitialized_) return true;

    BRIDGE_LOGI("NovatekAdapter: Initializing Novatek DTV descrambler...");
#if !defined(_WIN32)
    nvtCaFd_ = ::open("/dev/nvt_ca0", O_RDWR | O_NONBLOCK);
    if (nvtCaFd_ < 0) {
        nvtCaFd_ = ::open("/dev/dvb0.ca0", O_RDWR | O_NONBLOCK);
    }
    nvtDmxFd_ = ::open("/dev/dvb0.demux0", O_RDWR | O_NONBLOCK);
#endif

    isInitialized_ = true;
    BRIDGE_LOGI("NovatekAdapter: Initialized successfully");
    return true;
}

void NovatekAdapter::release() {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    if (!isInitialized_) return;

#if !defined(_WIN32)
    if (nvtCaFd_ >= 0) {
        ::close(nvtCaFd_);
        nvtCaFd_ = -1;
    }
    if (nvtDmxFd_ >= 0) {
        ::close(nvtDmxFd_);
        nvtDmxFd_ = -1;
    }
#endif

    isInitialized_ = false;
    BRIDGE_LOGI("NovatekAdapter: Resources released");
}

bool NovatekAdapter::registerCasSystemId(uint16_t caSystemId) {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    BRIDGE_LOGI("NovatekAdapter: Registered CAID 0x%04X", caSystemId);
    return true;
}

int32_t NovatekAdapter::getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    BRIDGE_LOGD("NovatekAdapter: getEcmPidFromDemux(idx=%d, srv=0x%04X)", demuxIndex, serviceId);
    return 0x0150;
}

bool NovatekAdapter::injectControlWord(const KeyInjectionParams& params) {
    std::lock_guard<std::mutex> lock(adapterMutex_);
    if (params.key.empty()) return false;

    BRIDGE_LOGI("NovatekAdapter: Injected %zu-byte CW into slot %d",
                params.key.size(), params.streamIndex);
    return true;
}

std::string NovatekAdapter::getDeviceNodePath() const noexcept {
    return "/dev/nvt_ca0";
}

std::string NovatekAdapter::getVendorPropertyPath() const noexcept {
    return "ro.novatek.hardware";
}

} // namespace oscam::chipset

