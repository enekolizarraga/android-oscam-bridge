// bridge/include/SoftwareDescrambler.h
//
// Software DVB-CSA (Common Scrambling Algorithm) descrambler for MPEG-TS streams.
// Enables real-time decryption and playback of encrypted transport streams (.ts recordings,
// SAT>IP streams, network IPTV) on Android TV without requiring native TV tuner hardware.
//
// Author: android-oscam-bridge

#pragma once

#include <cstdint>
#include <vector>
#include <array>
#include <memory>
#include <mutex>

namespace oscam::bridge {

constexpr size_t kTsPacketSize = 188;
constexpr size_t kCwSize = 8;

/**
 * @brief Thread-safe software DVB-CSA v1/v2 descrambler for MPEG-TS packets.
 */
class SoftwareDescrambler {
public:
    SoftwareDescrambler();
    ~SoftwareDescrambler();

    /**
     * @brief Updates the active Even or Odd Control Word for a specific elementary PID.
     * @param pid Elementary stream PID (e.g. video or audio PID).
     * @param parity 0 for Even CW, 1 for Odd CW.
     * @param cw 8-byte Control Word resolved by OSCam.
     */
    void setControlWord(uint16_t pid, int parity, const std::array<uint8_t, kCwSize>& cw);

    /**
     * @brief Descrambles a single 188-byte MPEG-TS packet in-place.
     * @param packet Pointer to 188-byte TS packet.
     * @return true if packet was successfully descrambled or already in clear; false on error.
     */
    bool descramblePacket(uint8_t* packet);

    /**
     * @brief Descrambles a buffer containing multiple MPEG-TS packets in-place.
     * @param buffer Pointer to buffer.
     * @param bufferSize Size of buffer in bytes (must be multiple of 188).
     * @return Number of TS packets processed.
     */
    size_t descrambleBuffer(uint8_t* buffer, size_t bufferSize);

private:
    struct PidKeyContext {
        std::array<uint8_t, kCwSize> evenCw{0};
        std::array<uint8_t, kCwSize> oddCw{0};
        bool hasEvenCw{false};
        bool hasOddCw{false};
    };

    // DVB-CSA v1/v2 standard core transformation
    static void csaBlockDecrypt(const uint8_t* key, uint8_t* data, size_t len);

    mutable std::mutex keysMutex_;
    std::array<PidKeyContext, 8192> pidTable_;
};

} // namespace oscam::bridge

