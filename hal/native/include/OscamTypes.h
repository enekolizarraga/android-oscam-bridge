// hal/native/include/OscamTypes.h
//
// Shared data types, structures, and status codes for the CAS HAL.
//
// Author: android-oscam-bridge

#pragma once

#include <cstdint>
#include <vector>
#include <array>
#include <string>

namespace oscam::hal {

constexpr int32_t kInvalidSessionHandle = -1;
constexpr size_t  kControlWordSize = 8;
constexpr size_t  kMaxEcmSize = 1024;
constexpr size_t  kMaxEmmSize = 4096;

/**
 * @brief Error status codes for CAS sessions.
 */
enum class CasStatus : int32_t {
    kOk = 0,
    kErrorGeneric = -1,
    kErrorSessionNotFound = -2,
    kErrorServerUnavailable = -3,
    kErrorTimeout = -4,
    kErrorDecryptionFailed = -5,
    kErrorUnsupportedChipset = -6,
    kErrorInvalidParameter = -7
};

/**
 * @brief Active session context maintained inside the plugin.
 */
struct CasSession {
    int32_t handle{kInvalidSessionHandle};
    uint16_t caSystemId{0};
    uint32_t ecmPid{0};
    int32_t demuxIndex{0};
    std::array<uint8_t, kControlWordSize> lastEvenCw{};
    std::array<uint8_t, kControlWordSize> lastOddCw{};
    bool hasEvenCw{false};
    bool hasOddCw{false};
};

} // namespace oscam::hal
