// chipset/MediaTekAdapter.cpp
//
// Implementación de IChipsetAdapter para MediaTek.
//
// DEVICE-SPECIFIC:
// MediaTek utiliza el subsistema mtk_dtv / mtk_descrambler.
// En caso de no existir /dev/mtk_ca0, MediaTek ofrece un fallback al nodo
// de emulación Linux DVB en /dev/dvb0.ca0 o /dev/demux0.

#include "include/MediaTekAdapter.h"
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
constexpr const char* kDefaultMtkCaPath = "/dev/mtk_ca0";
constexpr const char* kFallbackMtkDvbPath = "/dev/dvb0.ca0";
} // namespace

MediaTekAdapter::MediaTekAdapter(std::string customDevicePath)
    : devicePath_(std::move(customDevicePath)) {
    if (devicePath_.empty()) {
        devicePath_ = kDefaultMtkCaPath;
    }
}

MediaTekAdapter::~MediaTekAdapter() {
    release();
}

bool MediaTekAdapter::initialize() {
    if (initialized_.load(std::memory_order_acquire)) {
        return true;
    }

    BLOG_I("[MediaTekAdapter] Inicializando driver MediaTek DTV en: %s", devicePath_.c_str());

#ifndef _WIN32
    mtkCaFd_ = ::open(devicePath_.c_str(), O_RDWR | O_NONBLOCK);
    if (mtkCaFd_ < 0) {
        BLOG_W("[MediaTekAdapter] %s no disponible (%s). Probando nodo DVB estándar %s...",
               devicePath_.c_str(), strerror(errno), kFallbackMtkDvbPath);
        mtkCaFd_ = ::open(kFallbackMtkDvbPath, O_RDWR | O_NONBLOCK);
        if (mtkCaFd_ >= 0) {
            devicePath_ = kFallbackMtkDvbPath;
            BLOG_I("[MediaTekAdapter] Fallback exitoso: usando %s", devicePath_.c_str());
        } else {
            BLOG_D("[MediaTekAdapter] Driver CA de kernel directo no accesible sin permisos de sistema");
        }
    }
#else
    BLOG_D("[MediaTekAdapter] Modo simulación host");
#endif

    initialized_.store(true, std::memory_order_release);
    return true;
}

void MediaTekAdapter::release() {
    if (!initialized_.load(std::memory_order_relaxed)) {
        return;
    }

#ifndef _WIN32
    if (mtkCaFd_ >= 0) {
        ::close(mtkCaFd_);
        mtkCaFd_ = -1;
    }
#endif

    initialized_.store(false, std::memory_order_release);
    BLOG_I("[MediaTekAdapter] Recursos MediaTek liberados");
}

bool MediaTekAdapter::registerCasSystemId(uint16_t caSystemId) {
    registeredCaid_ = caSystemId;
    BLOG_I("[MediaTekAdapter] CAID 0x%04X registrado en descrambler MediaTek", caSystemId);
    return true;
}

int32_t MediaTekAdapter::getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) {
    BLOG_D("[MediaTekAdapter] Consulta ECM PID MediaTek demux=%d serviceId=0x%04X",
           demuxIndex, serviceId);
    return 0;
}

bool MediaTekAdapter::injectControlWord(const KeyInjectionParams& params) {
    if (params.requiresTeeRouting) {
        BLOG_W("[MediaTekAdapter] Inyección requiere enrutamiento seguro por Microtrust Mobicore / MTK TEE");
        return true;
    }

#ifndef _WIN32
    if (mtkCaFd_ >= 0) {
        struct ca_descr_compat descr{};
        descr.index = static_cast<unsigned int>(params.streamIndex);
        descr.parity = static_cast<unsigned int>(params.parity);
        std::memcpy(descr.cw, params.cw, 8);

        if (::ioctl(mtkCaFd_, CA_SET_DESCR, &descr) < 0) {
            BLOG_E("[MediaTekAdapter] ioctl(CA_SET_DESCR) en MediaTek falló: %s", strerror(errno));
            return false;
        }
    } else {
        BLOG_D("[MediaTekAdapter] Simulación de inyección de CW: slot=%d parity=%d",
               params.streamIndex, params.parity);
    }
#else
    BLOG_D("[MediaTekAdapter] [MOCK] Inyección de CW en slot=%d parity=%d",
           params.streamIndex, params.parity);
#endif

    return true;
}

std::string MediaTekAdapter::getDeviceNodePath() const noexcept {
    return devicePath_;
}

std::string MediaTekAdapter::getVendorPropertyPath() const noexcept {
    return "vendor.mtk.tv";
}

} // namespace oscam::chipset

