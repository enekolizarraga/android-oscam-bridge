// chipset/RealtekAdapter.cpp
//
// Implementación de IChipsetAdapter para Realtek.
//
// DEVICE-SPECIFIC:
// Realtek usa nodos /dev/rtk_ca o la emulación de Linux DVB /dev/dvb0.ca0.

#include "include/RealtekAdapter.h"
#include "../bridge/include/BridgeLogger.h"

#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>

#ifndef _WIN32
#  include <sys/ioctl.h>
#  ifndef CA_SET_DESCR
     struct ca_descr_compat {
         unsigned int index;
         unsigned int parity;
         unsigned char cw[8];
     };
#    define CA_SET_DESCR _IOW('o', 134, struct ca_descr_compat)
#  endif
#endif

namespace oscam::chipset {

namespace {
constexpr const char* kDefaultRtkCaPath = "/dev/rtk_ca";
constexpr const char* kFallbackRtkDvbPath = "/dev/dvb0.ca0";
} // namespace

RealtekAdapter::RealtekAdapter(std::string customDevicePath)
    : devicePath_(std::move(customDevicePath)) {
    if (devicePath_.empty()) {
        devicePath_ = kDefaultRtkCaPath;
    }
}

RealtekAdapter::~RealtekAdapter() {
    release();
}

bool RealtekAdapter::initialize() {
    if (initialized_.load(std::memory_order_acquire)) {
        return true;
    }

    BLOG_I("[RealtekAdapter] Inicializando driver Realtek RTD en: %s", devicePath_.c_str());

#ifndef _WIN32
    rtkCaFd_ = ::open(devicePath_.c_str(), O_RDWR | O_NONBLOCK);
    if (rtkCaFd_ < 0) {
        BLOG_W("[RealtekAdapter] %s no disponible (%s). Probando nodo DVB estándar %s...",
               devicePath_.c_str(), strerror(errno), kFallbackRtkDvbPath);
        rtkCaFd_ = ::open(kFallbackRtkDvbPath, O_RDWR | O_NONBLOCK);
        if (rtkCaFd_ >= 0) {
            devicePath_ = kFallbackRtkDvbPath;
            BLOG_I("[RealtekAdapter] Fallback exitoso: usando %s", devicePath_.c_str());
        }
    }
#else
    BLOG_D("[RealtekAdapter] Modo simulación host");
#endif

    initialized_.store(true, std::memory_order_release);
    return true;
}

void RealtekAdapter::release() {
    if (!initialized_.load(std::memory_order_relaxed)) {
        return;
    }

#ifndef _WIN32
    if (rtkCaFd_ >= 0) {
        ::close(rtkCaFd_);
        rtkCaFd_ = -1;
    }
#endif

    initialized_.store(false, std::memory_order_release);
    BLOG_I("[RealtekAdapter] Recursos Realtek liberados");
}

bool RealtekAdapter::registerCasSystemId(uint16_t caSystemId) {
    registeredCaid_ = caSystemId;
    BLOG_I("[RealtekAdapter] CAID 0x%04X registrado en descrambler Realtek", caSystemId);
    return true;
}

int32_t RealtekAdapter::getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) {
    BLOG_D("[RealtekAdapter] Consulta ECM PID Realtek demux=%d serviceId=0x%04X",
           demuxIndex, serviceId);
    return 0;
}

bool RealtekAdapter::injectControlWord(const KeyInjectionParams& params) {
    if (params.requiresTeeRouting) {
        BLOG_W("[RealtekAdapter] Inyección requiere enrutamiento por Realtek Secure OS");
        return true;
    }

#ifndef _WIN32
    if (rtkCaFd_ >= 0) {
        struct ca_descr_compat descr{};
        descr.index = static_cast<unsigned int>(params.streamIndex);
        descr.parity = static_cast<unsigned int>(params.parity);
        std::memcpy(descr.cw, params.cw, 8);

        if (::ioctl(rtkCaFd_, CA_SET_DESCR, &descr) < 0) {
            BLOG_E("[RealtekAdapter] ioctl(CA_SET_DESCR) en Realtek falló: %s", strerror(errno));
            return false;
        }
    } else {
        BLOG_D("[RealtekAdapter] Simulación inyección CW slot=%d parity=%d",
               params.streamIndex, params.parity);
    }
#else
    BLOG_D("[RealtekAdapter] [MOCK] Inyección de CW en slot=%d parity=%d",
           params.streamIndex, params.parity);
#endif

    return true;
}

std::string RealtekAdapter::getDeviceNodePath() const noexcept {
    return devicePath_;
}

std::string RealtekAdapter::getVendorPropertyPath() const noexcept {
    return "vendor.realtek.tv";
}

} // namespace oscam::chipset

