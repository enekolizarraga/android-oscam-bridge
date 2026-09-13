// bridge/NewcamdClient.cpp
//
// Newcamd (v5.25) Protocol Client implementation for Android TV CAS Bridge.
// Author: android-oscam-bridge

#include "NewcamdClient.h"
#include "BridgeLogger.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <sstream>
#include <iomanip>

#ifdef _WIN32
#   include <winsock2.h>
#   include <ws2tcpip.h>
#else
#   include <arpa/inet.h>
#   include <fcntl.h>
#   include <netdb.h>
#   include <netinet/in.h>
#   include <netinet/tcp.h>
#   include <sys/socket.h>
#   include <sys/types.h>
#   include <unistd.h>
#endif

namespace oscam::newcamd {

namespace {

// Standard DES Tables
static const uint8_t PC1[] = {
    57, 49, 41, 33, 25, 17,  9,  1, 58, 50, 42, 34, 26, 18,
    10,  2, 59, 51, 43, 35, 27, 19, 11,  3, 60, 52, 44, 36,
    63, 55, 47, 39, 31, 23, 15,  7, 62, 54, 46, 38, 30, 22,
    14,  6, 61, 53, 45, 37, 29, 21, 13,  5, 28, 20, 12,  4
};

static const uint8_t PC2[] = {
    14, 17, 11, 24,  1,  5,  3, 28, 15,  6, 21, 10,
    23, 19, 12,  4, 26,  8, 16,  7, 27, 20, 13,  2,
    41, 52, 31, 37, 47, 55, 30, 40, 51, 45, 33, 48,
    44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32
};

static const uint8_t ITERATION_SHIFTS[] = {
    1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1
};

static const uint8_t IP_TABLE[] = {
    58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4,
    62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
    57, 49, 41, 33, 25, 17,  9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
    61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7
};

static const uint8_t FP_TABLE[] = {
    40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31,
    38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
    36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27,
    34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41,  9, 49, 17, 57, 25
};

static const uint8_t S_BOXES[8][64] = {
    { 14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
       0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
       4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
      15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13 },
    { 15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
       3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
       0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
      13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9 },
    { 10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
      13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
      13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
       1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12 },
    {  7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
      13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
      10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
       3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14 },
    {  2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
      14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
       4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
      11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3 },
    { 12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
      10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
       9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
       4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13 },
    {  4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
      13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
       1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
       6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12 },
    { 13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
       1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
       7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
       2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11 }
};

static const uint8_t P_TABLE[] = {
    16,  7, 20, 21, 29, 12, 28, 17,  1, 15, 23, 26,  5, 18, 31, 10,
     2,  8, 24, 14, 32, 27,  3,  9, 19, 13, 30,  6, 22, 11,  4, 25
};

static uint64_t permute(uint64_t in, const uint8_t* table, int n) {
    uint64_t res = 0;
    for (int i = 0; i < n; ++i) {
        if ((in >> (64 - table[i])) & 1) {
            res |= (1ULL << (n - 1 - i));
        }
    }
    return res;
}

// Single block DES structure
struct DesContext {
    uint64_t subkeys[16]{0};

    void keySchedule(uint64_t key) {
        uint64_t permKey = permute(key, PC1, 56);
        uint32_t c = (permKey >> 28) & 0x0FFFFFFF;
        uint32_t d = permKey & 0x0FFFFFFF;

        for (int i = 0; i < 16; ++i) {
            int shifts = ITERATION_SHIFTS[i];
            c = ((c << shifts) | (c >> (28 - shifts))) & 0x0FFFFFFF;
            d = ((d << shifts) | (d >> (28 - shifts))) & 0x0FFFFFFF;
            uint64_t cd = (((uint64_t)c) << 28) | d;
            subkeys[i] = permute(cd << 8, PC2, 48);
        }
    }

    uint64_t crypt(uint64_t block, bool decrypt) {
        uint64_t ip = permute(block, IP_TABLE, 64);
        uint32_t l = (ip >> 32) & 0xFFFFFFFF;
        uint32_t r = ip & 0xFFFFFFFF;

        for (int round = 0; round < 16; ++round) {
            int subkeyIdx = decrypt ? (15 - round) : round;
            uint64_t sk = subkeys[subkeyIdx];

            // Expansion
            static const uint8_t E_TABLE[] = {
                32,  1,  2,  3,  4,  5,  4,  5,  6,  7,  8,  9,
                 8,  9, 10, 11, 12, 13, 12, 13, 14, 15, 16, 17,
                16, 17, 18, 19, 20, 21, 20, 21, 22, 23, 24, 25,
                24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32,  1
            };
            uint64_t er = permute(((uint64_t)r) << 32, E_TABLE, 48);
            uint64_t x = er ^ sk;

            // S-box substitution
            uint32_t sOutput = 0;
            for (int s = 0; s < 8; ++s) {
                uint8_t sixBits = (x >> (42 - s * 6)) & 0x3F;
                uint8_t row = ((sixBits & 0x20) >> 4) | (sixBits & 0x01);
                uint8_t col = (sixBits >> 1) & 0x0F;
                uint8_t val = S_BOXES[s][(row << 4) | col];
                sOutput = (sOutput << 4) | val;
            }

            uint32_t f = (uint32_t)permute(((uint64_t)sOutput) << 32, P_TABLE, 32);
            uint32_t nextR = l ^ f;
            l = r;
            r = nextR;
        }

        uint64_t combined = (((uint64_t)r) << 32) | l;
        return permute(combined, FP_TABLE, 64);
    }
};

static uint64_t bytesToUint64(const uint8_t* b) {
    uint64_t val = 0;
    for (int i = 0; i < 8; ++i) {
        val = (val << 8) | b[i];
    }
    return val;
}

static void uint64ToBytes(uint64_t val, uint8_t* b) {
    for (int i = 7; i >= 0; --i) {
        b[i] = val & 0xFF;
        val >>= 8;
    }
}

} // namespace

// Triple-DES EDE2 helper (16-byte key: K1, K2)
void NewcamdClient::des3Crypt(const uint8_t* in, uint8_t* out, const uint8_t* key16, bool decrypt) {
    DesContext d1, d2;
    d1.keySchedule(bytesToUint64(key16));
    d2.keySchedule(bytesToUint64(key16 + 8));

    uint64_t b = bytesToUint64(in);
    if (!decrypt) {
        b = d1.crypt(b, false); // Encrypt with K1
        b = d2.crypt(b, true);  // Decrypt with K2
        b = d1.crypt(b, false); // Encrypt with K1
    } else {
        b = d1.crypt(b, true);  // Decrypt with K1
        b = d2.crypt(b, false); // Decrypt with K2
        b = d1.crypt(b, true);  // Decrypt with K1
    }
    uint64ToBytes(b, out);
}

NewcamdClient::NewcamdClient(NewcamdConfig config, NewcamdCallbacks callbacks)
    : config_(std::move(config)), callbacks_(std::move(callbacks)) {}

NewcamdClient::~NewcamdClient() {
    stop();
}

bool NewcamdClient::start() {
    if (running_.exchange(true)) {
        return true;
    }

    workerThread_ = std::thread(&NewcamdClient::workerLoop, this);
    BRIDGE_LOGI("NewcamdClient started for %s:%u", config_.host.c_str(), config_.port);
    return true;
}

void NewcamdClient::stop() {
    if (!running_.exchange(false)) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(socketMutex_);
        if (activeSocketFd_ >= 0) {
#ifdef _WIN32
            closesocket(activeSocketFd_);
#else
            close(activeSocketFd_);
#endif
            activeSocketFd_ = -1;
        }
    }

    if (workerThread_.joinable()) {
        workerThread_.join();
    }

    connected_ = false;
    if (callbacks_.onConnectionChanged) {
        callbacks_.onConnectionChanged(false);
    }
    BRIDGE_LOGI("NewcamdClient stopped");
}

bool NewcamdClient::isConnected() const {
    return connected_.load();
}

std::vector<uint8_t> NewcamdClient::parseDesKeyHex(const std::string& hexStr) {
    std::string clean;
    for (char c : hexStr) {
        if (std::isxdigit(c)) clean.push_back(c);
    }
    std::vector<uint8_t> key;
    for (size_t i = 0; i + 1 < clean.size() && key.size() < 14; i += 2) {
        uint8_t byte = (uint8_t)std::strtoul(clean.substr(i, 2).c_str(), nullptr, 16);
        key.push_back(byte);
    }
    while (key.size() < 14) {
        key.push_back(0);
    }
    return key;
}

bool NewcamdClient::sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId, const uint8_t* ecmData, size_t length) {
    if (!connected_.load() || length == 0 || length > 1024) {
        return false;
    }

    std::lock_guard<std::mutex> lock(socketMutex_);
    if (activeSocketFd_ < 0) return false;

    // Newcamd ECM packet:
    // Header (3 bytes): [Length MSB, Length LSB, Opcode: 0x80 (even) or 0x81 (odd)]
    // Payload: ECM bytes padded to multiple of 8
    uint8_t opcode = (ecmData[0] == 0x81) ? 0x81 : 0x80;
    size_t payloadLen = length;
    size_t paddedLen = (payloadLen + 7) & ~7;

    std::vector<uint8_t> packet(3 + paddedLen, 0);
    packet[0] = (uint8_t)((paddedLen + 1) >> 8);
    packet[1] = (uint8_t)((paddedLen + 1) & 0xFF);
    packet[2] = opcode;

    std::memcpy(&packet[3], ecmData, length);

    // Encrypt payload with 3DES session key
    for (size_t offset = 3; offset < packet.size(); offset += 8) {
        des3Crypt(&packet[offset], &packet[offset], sessionKey_, false);
    }

    return writeFull(activeSocketFd_, packet.data(), packet.size());
}

bool NewcamdClient::readFull(int socketFd, uint8_t* buffer, size_t count) {
    size_t total = 0;
    while (total < count) {
#ifdef _WIN32
        int bytes = recv(socketFd, (char*)buffer + total, (int)(count - total), 0);
#else
        ssize_t bytes = read(socketFd, buffer + total, count - total);
#endif
        if (bytes <= 0) {
            return false;
        }
        total += bytes;
    }
    return true;
}

bool NewcamdClient::writeFull(int socketFd, const uint8_t* buffer, size_t count) {
    size_t total = 0;
    while (total < count) {
#ifdef _WIN32
        int bytes = send(socketFd, (const char*)buffer + total, (int)(count - total), 0);
#else
        ssize_t bytes = write(socketFd, buffer + total, count - total);
#endif
        if (bytes <= 0) {
            return false;
        }
        total += bytes;
    }
    return true;
}

bool NewcamdClient::connectAndLogin(int& socketFd) {
    struct addrinfo hints{}, *res = nullptr;
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    std::string portStr = std::to_string(config_.port);
    if (getaddrinfo(config_.host.c_str(), portStr.c_str(), &hints, &res) != 0 || !res) {
        BRIDGE_LOGE("Newcamd: Could not resolve host %s", config_.host.c_str());
        return false;
    }

    socketFd = (int)socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (socketFd < 0) {
        freeaddrinfo(res);
        return false;
    }

    // Set non-blocking for connect timeout
#ifndef _WIN32
    struct timeval tv{};
    tv.tv_sec = config_.connectTimeoutSec;
    setsockopt(socketFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(socketFd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
#endif

    if (connect(socketFd, res->ai_addr, (socklen_t)res->ai_addrlen) != 0) {
        freeaddrinfo(res);
#ifdef _WIN32
        closesocket(socketFd);
#else
        close(socketFd);
#endif
        socketFd = -1;
        return false;
    }
    freeaddrinfo(res);

    int nodelay = 1;
    setsockopt(socketFd, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay), sizeof(nodelay));
    int keepalive = 1;
    setsockopt(socketFd, SOL_SOCKET, SO_KEEPALIVE, reinterpret_cast<const char*>(&keepalive), sizeof(keepalive));

    // Step 1: Read 14-byte random key initialization vector from Newcamd server
    uint8_t serverRandomKey[14];
    if (!readFull(socketFd, serverRandomKey, 14)) {
        BRIDGE_LOGE("Newcamd: Handshake failed: could not read server key");
        return false;
    }

    // Step 2: Derive 16-byte session key from client DES key and server random key
    for (int i = 0; i < 14; ++i) {
        sessionKey_[i] = config_.desKey[i] ^ serverRandomKey[i];
    }
    sessionKey_[14] = sessionKey_[0];
    sessionKey_[15] = sessionKey_[1];

    // Step 3: Transmit MSG_CLIENT_2_SERVER_LOGIN (0xE0)
    std::string user = config_.user;
    std::string pass = config_.password;

    std::vector<uint8_t> loginPayload;
    loginPayload.insert(loginPayload.end(), user.begin(), user.end());
    loginPayload.push_back(0); // Null terminator
    loginPayload.insert(loginPayload.end(), pass.begin(), pass.end());
    loginPayload.push_back(0); // Null terminator

    size_t padLen = (loginPayload.size() + 7) & ~7;
    loginPayload.resize(padLen, 0);

    std::vector<uint8_t> loginPacket(3 + padLen, 0);
    loginPacket[0] = (uint8_t)((padLen + 1) >> 8);
    loginPacket[1] = (uint8_t)((padLen + 1) & 0xFF);
    loginPacket[2] = 0xE0; // LOGIN
    std::memcpy(&loginPacket[3], loginPayload.data(), loginPayload.size());

    // Encrypt login packet with session key
    for (size_t offset = 3; offset < loginPacket.size(); offset += 8) {
        des3Crypt(&loginPacket[offset], &loginPacket[offset], sessionKey_, false);
    }

    if (!writeFull(socketFd, loginPacket.data(), loginPacket.size())) {
        BRIDGE_LOGE("Newcamd: Failed to transmit login packet");
        return false;
    }

    // Step 4: Receive Login ACK response (0xE1)
    uint8_t ackHeader[3];
    if (!readFull(socketFd, ackHeader, 3)) {
        BRIDGE_LOGE("Newcamd: Failed to read login response header");
        return false;
    }

    size_t ackLen = (((size_t)ackHeader[0]) << 8) | ackHeader[1];
    if (ackLen < 1 || ackLen > 512) {
        BRIDGE_LOGE("Newcamd: Invalid login ACK length %zu", ackLen);
        return false;
    }

    std::vector<uint8_t> ackPayload(ackLen - 1);
    if (!readFull(socketFd, ackPayload.data(), ackPayload.size())) {
        return false;
    }

    // Decrypt login ACK
    for (size_t offset = 0; offset + 8 <= ackPayload.size(); offset += 8) {
        des3Crypt(&ackPayload[offset], &ackPayload[offset], sessionKey_, true);
    }

    uint8_t op = ackHeader[2];
    if (op == 0xE1) { // LOGIN_ACK
        uint16_t caid = config_.caid;
        if (ackPayload.size() >= 4) {
            caid = (((uint16_t)ackPayload[2]) << 8) | ackPayload[3];
        }
        BRIDGE_LOGI("Newcamd: Successfully authenticated! CAID: 0x%04X", caid);
        if (callbacks_.onLoginSuccess) {
            callbacks_.onLoginSuccess(caid, {});
        }
        return true;
    } else {
        BRIDGE_LOGE("Newcamd: Login rejected by server (Opcode 0x%02X)", op);
        return false;
    }
}

void NewcamdClient::workerLoop() {
    while (running_.load()) {
        int socketFd = -1;
        BRIDGE_LOGI("Newcamd: Connecting to %s:%u...", config_.host.c_str(), config_.port);

        if (!connectAndLogin(socketFd)) {
            if (socketFd >= 0) {
#ifdef _WIN32
                closesocket(socketFd);
#else
                close(socketFd);
#endif
            }
            if (callbacks_.onError) {
                callbacks_.onError("Connection or authentication failed");
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(config_.reconnectIntervalMs));
            continue;
        }

        {
            std::lock_guard<std::mutex> lock(socketMutex_);
            activeSocketFd_ = socketFd;
            connected_ = true;
        }

        if (callbacks_.onConnectionChanged) {
            callbacks_.onConnectionChanged(true);
        }

        // Receive loop
        auto lastKeepalive = std::chrono::steady_clock::now();
        while (running_.load() && connected_.load()) {
            uint8_t header[3];
            if (!readFull(socketFd, header, 3)) {
                BRIDGE_LOGW("Newcamd: Socket connection lost");
                break;
            }

            size_t payloadLen = (((size_t)header[0]) << 8) | header[1];
            if (payloadLen == 0 || payloadLen > 1024) {
                BRIDGE_LOGW("Newcamd: Malformed packet length %zu", payloadLen);
                break;
            }

            std::vector<uint8_t> payload(payloadLen - 1);
            if (!readFull(socketFd, payload.data(), payload.size())) {
                break;
            }

            // Decrypt payload
            for (size_t offset = 0; offset + 8 <= payload.size(); offset += 8) {
                des3Crypt(&payload[offset], &payload[offset], sessionKey_, true);
            }

            uint8_t opcode = header[2];
            if ((opcode == 0x80 || opcode == 0x81) && payload.size() >= 16) {
                // Control Word received!
                // 16 bytes: 8 bytes Even CW, 8 bytes Odd CW
                const uint8_t* evenCw = payload.data();
                const uint8_t* oddCw = payload.data() + 8;

                if (callbacks_.onControlWord) {
                    callbacks_.onControlWord(0, 0, evenCw, 8); // Even
                    callbacks_.onControlWord(0, 1, oddCw, 8);  // Odd
                }
            }

            // Keepalive every 50 seconds
            // Keepalive every 25 seconds to maintain router NAT state
            auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::seconds>(now - lastKeepalive).count() >= 50) {
            if (std::chrono::duration_cast<std::chrono::seconds>(now - lastKeepalive).count() >= 25) {
                uint8_t ping[3] = { 0x00, 0x01, 0x00 };
                writeFull(socketFd, ping, 3);
                lastKeepalive = now;
            }
        }

        // Disconnected
        {
            std::lock_guard<std::mutex> lock(socketMutex_);
            if (activeSocketFd_ >= 0) {
#ifdef _WIN32
                closesocket(activeSocketFd_);
#else
                close(activeSocketFd_);
#endif
                activeSocketFd_ = -1;
            }
            connected_ = false;
        }

        if (callbacks_.onConnectionChanged) {
            callbacks_.onConnectionChanged(false);
        }

        if (running_.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(config_.reconnectIntervalMs));
        }
    }
}

bool NewcamdClient::testConnection(
    const std::string& host,
    uint16_t port,
    const std::string& user,
    const std::string& password,
    const std::vector<uint8_t>& desKey,
    int timeoutMs,
    std::string& outError
) {
    NewcamdConfig cfg;
    cfg.host = host;
    cfg.port = port;
    cfg.user = user;
    cfg.password = password;
    cfg.desKey = desKey;
    cfg.connectTimeoutSec = std::max(1, timeoutMs / 1000);

    NewcamdCallbacks cbs;
    NewcamdClient client(cfg, cbs);

    int socketFd = -1;
    bool ok = client.connectAndLogin(socketFd);
    if (socketFd >= 0) {
#ifdef _WIN32
        closesocket(socketFd);
#else
        close(socketFd);
#endif
    }

    if (!ok) {
        outError = "Newcamd: Connection refused or authentication failed";
    }
    return ok;
}

} // namespace oscam::newcamd
