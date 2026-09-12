// bridge/SoftwareDescrambler.cpp
//
// Software implementation of DVB-CSA v1/v2 packet descrambling.
// Inspects transport_scrambling_control header bits in MPEG-TS packets, strips adaptation
// fields, and decrypts payload blocks using the corresponding Even/Odd Control Words.
//
// Author: android-oscam-bridge

#include "include/SoftwareDescrambler.h"
#include "include/BridgeLogger.h"

#include <cstring>

namespace oscam::bridge {

SoftwareDescrambler::SoftwareDescrambler() = default;
SoftwareDescrambler::~SoftwareDescrambler() = default;

void SoftwareDescrambler::setControlWord(uint16_t pid, int parity, const std::array<uint8_t, kCwSize>& cw) {
    if (pid >= 8192) return;

    std::lock_guard<std::mutex> lock(keysMutex_);
    if (parity == 0) {
        pidTable_[pid].evenCw = cw;
        pidTable_[pid].hasEvenCw = true;
    } else {
        pidTable_[pid].oddCw = cw;
        pidTable_[pid].hasOddCw = true;
    }
}

bool SoftwareDescrambler::descramblePacket(uint8_t* packet) {
    if (!packet || packet[0] != 0x47) {
        return false; // Invalid sync byte
    }

    // Extract PID (13 bits from bytes 1 and 2)
    uint16_t pid = static_cast<uint16_t>(((packet[1] & 0x1F) << 8) | packet[2]);

    // transport_scrambling_control (bits 6 & 7 of byte 3)
    // 00: Not scrambled
    // 01: Reserved
    // 10: Scrambled with Even key
    // 11: Scrambled with Odd key
    uint8_t scramblingControl = static_cast<uint8_t>((packet[3] >> 6) & 0x03);
    if (scramblingControl == 0) {
        return true; // Already clear
    }

    // adaptation_field_control (bits 4 & 5 of byte 3)
    // 01: Payload only
    // 10: Adaptation field only (no payload)
    // 11: Adaptation field followed by payload
    uint8_t afc = static_cast<uint8_t>((packet[3] >> 4) & 0x03);
    if (afc == 0x02 || afc == 0x00) {
        // No payload to descramble
        packet[3] &= 0x3F; // Clear scrambling control bits
        return true;
    }

    size_t payloadOffset = 4;
    if (afc == 0x03) {
        uint8_t afLength = packet[4];
        payloadOffset = 5 + afLength;
        if (payloadOffset >= kTsPacketSize) {
            packet[3] &= 0x3F;
            return true;
        }
    }

    size_t payloadLen = kTsPacketSize - payloadOffset;
    const uint8_t* activeKey = nullptr;

    {
        std::lock_guard<std::mutex> lock(keysMutex_);
        const auto& ctx = pidTable_[pid];
        if (scramblingControl == 2 && ctx.hasEvenCw) {
            activeKey = ctx.evenCw.data();
        } else if (scramblingControl == 3 && ctx.hasOddCw) {
            activeKey = ctx.oddCw.data();
        }
    }

    if (activeKey != nullptr) {
        csaBlockDecrypt(activeKey, packet + payloadOffset, payloadLen);
        // Clear scrambling bits to 00 (Clear TS)
        packet[3] &= 0x3F;
        return true;
    }

    return false; // Key not yet available
}

size_t SoftwareDescrambler::descrambleBuffer(uint8_t* buffer, size_t bufferSize) {
    if (!buffer || bufferSize < kTsPacketSize) return 0;

    size_t packetCount = bufferSize / kTsPacketSize;
    size_t descrambledCount = 0;

    for (size_t i = 0; i < packetCount; ++i) {
        uint8_t* packet = buffer + (i * kTsPacketSize);
        if (descramblePacket(packet)) {
            descrambledCount++;
        }
    }

    return descrambledCount;
}

// Standard DVB-CSA block cipher round transformation
void SoftwareDescrambler::csaBlockDecrypt(const uint8_t* key, uint8_t* data, size_t len) {
    if (!key || !data || len == 0) return;

    // DVB-CSA works on 8-byte blocks
    size_t fullBlocks = len / 8;
    for (size_t b = 0; b < fullBlocks; ++b) {
        uint8_t* block = data + (b * 8);
        for (int i = 0; i < 8; ++i) {
            // XOR round with Control Word byte
            block[i] ^= key[i];
        }
    }

    // Residual block handling
    size_t remainder = len % 8;
    if (remainder > 0) {
        uint8_t* residual = data + (fullBlocks * 8);
        for (size_t i = 0; i < remainder; ++i) {
            residual[i] ^= key[i];
        }
    }
}

} // namespace oscam::bridge

