// chipset/ChipsetDetector.cpp
//
// Automatic SoC detector implementation for Android TV platforms.
// Identifies hardware architectures: Amlogic, MediaTek, Realtek, Broadcom, Synaptics, Novatek.
//
// Author: android-oscam-bridge

#include "include/ChipsetDetector.h"
#include "include/AmlogicAdapter.h"
#include "include/MediaTekAdapter.h"
#include "include/RealtekAdapter.h"
#include "include/BroadcomAdapter.h"
#include "include/SynapticsAdapter.h"
#include "include/NovatekAdapter.h"
#include "../../bridge/include/BridgeLogger.h"

#include <algorithm>
#include <cctype>

#if defined(__ANDROID__)
#  include <sys/system_properties.h>
#else
#  include <cstdlib>
#endif

namespace oscam::chipset {

namespace {

std::string toLower(std::string str) {
    std::transform(str.begin(), str.end(), str.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return str;
}

bool contains(const std::string& haystack, const std::string& needle) {
    return haystack.find(needle) != std::string::npos;
}

} // namespace

std::string ChipsetDetector::getSystemProperty(const std::string& key) {
#if defined(__ANDROID__)
    char value[PROP_VALUE_MAX] = {0};
    int len = __system_property_get(key.c_str(), value);
    if (len > 0) {
        return std::string(value, static_cast<size_t>(len));
    }
    return "";
#else
    // Environment fallback for host unit testing
    std::string envKey = key;
    std::transform(envKey.begin(), envKey.end(), envKey.begin(),
                   [](char c) { return c == '.' ? '_' : static_cast<char>(std::toupper(c)); });
    const char* envVal = std::getenv(envKey.c_str());
    if (envVal != nullptr) {
        return std::string(envVal);
    }
    return "";
#endif
}

ChipsetType ChipsetDetector::identify(const std::string& platformIn,
                                      const std::string& hardwareIn,
                                      const std::string& boardIn) {
    const std::string platform = toLower(platformIn);
    const std::string hardware = toLower(hardwareIn);
    const std::string board    = toLower(boardIn);

    BRIDGE_LOGD("ChipsetDetector: Analyzing platform='%s' hardware='%s' board='%s'",
                platform.c_str(), hardware.c_str(), board.c_str());

    // 1. Amlogic: meson*, s905*, s928*, amlogic*, gxl*, g12*, sm1*
    if (contains(platform, "meson") || contains(platform, "amlogic") ||
        contains(hardware, "amlogic") || contains(hardware, "meson") ||
        contains(platform, "s905") || contains(platform, "s928") ||
        contains(board, "meson") || contains(board, "amlogic")) {
        return ChipsetType::kAmlogic;
    }

    // 2. MediaTek: mt5895, mt9632, mt5896, mt9602, mtk*, mt58*, mt96*, pentonic*
    if (contains(platform, "mt58") || contains(platform, "mt96") ||
        contains(platform, "mtk") || contains(platform, "mediatek") ||
        contains(platform, "pentonic") ||
        contains(hardware, "mt58") || contains(hardware, "mt96") ||
        contains(hardware, "mtk") || contains(hardware, "mediatek") ||
        contains(board, "mtk") || contains(board, "mediatek")) {
        return ChipsetType::kMediaTek;
    }

    // 3. Realtek: rtd2851, rtd1319, rtd*, realtek*
    if (contains(platform, "rtd") || contains(platform, "realtek") ||
        contains(hardware, "rtd") || contains(hardware, "realtek") ||
        contains(board, "rtd") || contains(board, "realtek")) {
        return ChipsetType::kRealtek;
    }

    // 4. Broadcom: bcm7xxx, brcm*, broadcom*
    if (contains(platform, "bcm") || contains(platform, "brcm") ||
        contains(platform, "broadcom") || contains(hardware, "bcm") ||
        contains(hardware, "brcm") || contains(board, "bcm")) {
        return ChipsetType::kBroadcom;
    }

    // 5. Synaptics / Marvell: vs680*, berlin*, galois*, synaptics*
    if (contains(platform, "vs680") || contains(platform, "berlin") ||
        contains(platform, "synaptics") || contains(hardware, "galois") ||
        contains(hardware, "synaptics") || contains(board, "berlin")) {
        return ChipsetType::kSynaptics;
    }

    // 6. Novatek: nt72xxx, nvt*, novatek*
    if (contains(platform, "nt72") || contains(platform, "nvt") ||
        contains(platform, "novatek") || contains(hardware, "novatek") ||
        contains(board, "nvt")) {
        return ChipsetType::kNovatek;
    }

    // Fallback: explicit unsupported type rather than guessing
    return ChipsetType::kUnknown;
}

std::unique_ptr<IChipsetAdapter> ChipsetDetector::detect(
    const SystemPropertyGetter& customGetter) {

    auto getter = customGetter ? customGetter : getSystemProperty;

    const std::string platform = getter("ro.board.platform");
    const std::string hardware = getter("ro.hardware");
    const std::string board    = getter("ro.product.board");

    const ChipsetType type = identify(platform, hardware, board);

    switch (type) {
        case ChipsetType::kAmlogic:
            BRIDGE_LOGI("ChipsetDetector: Detected Amlogic SoC (platform=%s, hardware=%s)",
                        platform.c_str(), hardware.c_str());
            return std::make_unique<AmlogicAdapter>();

        case ChipsetType::kMediaTek:
            BRIDGE_LOGI("ChipsetDetector: Detected MediaTek SoC (platform=%s, hardware=%s)",
                        platform.c_str(), hardware.c_str());
            return std::make_unique<MediaTekAdapter>();

        case ChipsetType::kRealtek:
            BRIDGE_LOGI("ChipsetDetector: Detected Realtek SoC (platform=%s, hardware=%s)",
                        platform.c_str(), hardware.c_str());
            return std::make_unique<RealtekAdapter>();

        case ChipsetType::kBroadcom:
            BRIDGE_LOGI("ChipsetDetector: Detected Broadcom SoC (platform=%s, hardware=%s)",
                        platform.c_str(), hardware.c_str());
            return std::make_unique<BroadcomAdapter>();

        case ChipsetType::kSynaptics:
            BRIDGE_LOGI("ChipsetDetector: Detected Synaptics SoC (platform=%s, hardware=%s)",
                        platform.c_str(), hardware.c_str());
            return std::make_unique<SynapticsAdapter>();

        case ChipsetType::kNovatek:
            BRIDGE_LOGI("ChipsetDetector: Detected Novatek SoC (platform=%s, hardware=%s)",
                        platform.c_str(), hardware.c_str());
            return std::make_unique<NovatekAdapter>();

        case ChipsetType::kUnknown:
        default:
            BRIDGE_LOGE("ChipsetDetector: Unsupported or unrecognised SoC: platform='%s', hardware='%s', board='%s'",
                        platform.c_str(), hardware.c_str(), board.c_str());
            return nullptr;
    }
}

} // namespace oscam::chipset
