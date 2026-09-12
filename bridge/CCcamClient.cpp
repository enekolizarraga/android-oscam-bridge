// bridge/CCcamClient.cpp
//
// CCcam (v2.0.11 / v2.3.0) Protocol Client implementation for Android TV CAS Bridge.
// Author: android-oscam-bridge

#include "CCcamClient.h"
#include "BridgeLogger.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <random>

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

namespace oscam::cccam {

namespace {

// ===========================================================================
// Self-Contained SHA-1 Implementation (RFC 3174 / FIPS 180-1)
// ===========================================================================

static inline uint32_t rotl32(uint32_t val, int bits) {
    return (val << bits) | (val >> (32 - bits));
}

void sha1Block(uint32_t state[5], const uint8_t block[64]) {
    uint32_t w[80];
    for (int t = 0; t < 16; ++t) {
        w[t] = (static_cast<uint32_t>(block[t * 4])     << 24) |
               (static_cast<uint32_t>(block[t * 4 + 1]) << 16) |
               (static_cast<uint32_t>(block[t * 4 + 2]) <<  8) |
               (static_cast<uint32_t>(block[t * 4 + 3]));
    }
    for (int t = 16; t < 80; ++t) {
        w[t] = rotl32(w[t - 3] ^ w[t - 8] ^ w[t - 14] ^ w[t - 16], 1);
    }

    uint32_t a = state[0];
    uint32_t b = state[1];
    uint32_t c = state[2];
    uint32_t d = state[3];
    uint32_t e = state[4];

    for (int t = 0; t < 80; ++t) {
        uint32_t f, k;
        if (t < 20) {
            f = (b & c) | ((~b) & d);
            k = 0x5A827999;
        } else if (t < 40) {
            f = b ^ c ^ d;
            k = 0x6ED9EBA1;
        } else if (t < 60) {
            f = (b & c) | (b & d) | (c & d);
            k = 0x8F1BBCDC;
        } else {
            f = b ^ c ^ d;
            k = 0xCA62C1D6;
        }
        uint32_t temp = rotl32(a, 5) + f + e + k + w[t];
        e = d;
        d = c;
        c = rotl32(b, 30);
        b = a;
        a = temp;
    }

    state[0] += a;
    state[1] += b;
    state[2] += c;
    state[3] += d;
    state[4] += e;
}

} // namespace

void CCcamClient::sha1(const uint8_t* data, size_t length, uint8_t outDigest[20]) {
    uint32_t state[5] = {
        0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0
    };

    size_t fullBlocks = length / 64;
    for (size_t i = 0; i < fullBlocks; ++i) {
        sha1Block(state, data + i * 64);
    }

    uint8_t buffer[128];
    size_t rem = length % 64;
    std::memcpy(buffer, data + fullBlocks * 64, rem);
    buffer[rem] = 0x80;
    rem++;

    if (rem > 56) {
        std::memset(buffer + rem, 0, 64 - rem);
        sha1Block(state, buffer);
        std::memset(buffer, 0, 56);
    } else {
        std::memset(buffer + rem, 0, 56 - rem);
    }

    uint64_t bitLen = static_cast<uint64_t>(length) * 8;
    for (int i = 0; i < 8; ++i) {
        buffer[56 + i] = static_cast<uint8_t>((bitLen >> (56 - i * 8)) & 0xFF);
    }
    sha1Block(state, buffer);

    for (int i = 0; i < 5; ++i) {
        outDigest[i * 4]     = static_cast<uint8_t>((state[i] >> 24) & 0xFF);
        outDigest[i * 4 + 1] = static_cast<uint8_t>((state[i] >> 16) & 0xFF);
        outDigest[i * 4 + 2] = static_cast<uint8_t>((state[i] >>  8) & 0xFF);
        outDigest[i * 4 + 3] = static_cast<uint8_t>( state[i]        & 0xFF);
    }
}

// ===========================================================================
// Self-Contained RC4 Implementation
// ===========================================================================

void CCcamClient::rc4Init(Rc4Key* key, const uint8_t* keyData, size_t keyLen) {
    if (!key || !keyData || keyLen == 0) return;
    for (int i = 0; i < 256; ++i) {
        key->state[i] = static_cast<uint8_t>(i);
    }
    key->x = 0;
    key->y = 0;

    uint8_t j = 0;
    for (int i = 0; i < 256; ++i) {
        j = static_cast<uint8_t>(j + key->state[i] + keyData[i % keyLen]);
        std::swap(key->state[i], key->state[j]);
    }
}

void CCcamClient::rc4Crypt(Rc4Key* key, const uint8_t* in, uint8_t* out, size_t len) {
    if (!key || !in || !out) return;
    uint8_t x = key->x;
    uint8_t y = key->y;

    for (size_t i = 0; i < len; ++i) {
        x = static_cast<uint8_t>(x + 1);
        y = static_cast<uint8_t>(y + key->state[x]);
        std::swap(key->state[x], key->state[y]);
        uint8_t xorByte = key->state[(key->state[x] + key->state[y]) & 0xFF];
        out[i] = in[i] ^ xorByte;
    }

    key->x = x;
    key->y = y;
}

// ===========================================================================
// CCcamClient Implementation
// ===========================================================================

CCcamClient::CCcamClient(CCcamConfig config, CCcamCallbacks callbacks)
    : config_(std::move(config)), callbacks_(std::move(callbacks)) {
    // Generate a unique 8-byte client node ID
    std::mt19937_64 rng(static_cast<uint64_t>(std::chrono::high_resolution_clock::now().time_since_epoch().count()));
    uint64_t randId = rng();
    std::memcpy(clientNodeId_, &randId, 8);
}

CCcamClient::~CCcamClient() {
    stop();
}

bool CCcamClient::start() {
    if (running_.exchange(true)) {
        return true;
    }

    workerThread_ = std::thread(&CCcamClient::workerLoop, this);
    BRIDGE_LOGI("CCcamClient started for %s:%u", config_.host.c_str(), config_.port);
    return true;
}

void CCcamClient::stop() {
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
    BRIDGE_LOGI("CCcamClient stopped");
}

bool CCcamClient::isConnected() const {
    return connected_.load();
}

bool CCcamClient::readFull(int socketFd, uint8_t* buffer, size_t count) {
    size_t total = 0;
    while (total < count) {
        int r = recv(socketFd, reinterpret_cast<char*>(buffer + total), static_cast<int>(count - total), 0);
        if (r <= 0) {
            return false;
        }
        total += static_cast<size_t>(r);
    }
    return total == count;
}

bool CCcamClient::writeFull(int socketFd, const uint8_t* buffer, size_t count) {
    size_t total = 0;
    while (total < count) {
        int s = send(socketFd, reinterpret_cast<const char*>(buffer + total), static_cast<int>(count - total), 0);
        if (s <= 0) {
            return false;
        }
        total += static_cast<size_t>(s);
    }
    return total == count;
}

bool CCcamClient::connectAndLogin(int& socketFd) {
    socketFd = -1;

#ifdef _WIN32
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
#endif

    struct addrinfo hints{}, *res = nullptr;
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    std::string portStr = std::to_string(config_.port);
    if (getaddrinfo(config_.host.c_str(), portStr.c_str(), &hints, &res) != 0 || !res) {
        return false;
    }

    int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (fd < 0) {
        freeaddrinfo(res);
        return false;
    }

    // Set non-blocking for connect timeout
#ifdef _WIN32
    u_long mode = 1;
    ioctlsocket(fd, FIONBIO, &mode);
#else
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
#endif

    int connRes = connect(fd, res->ai_addr, static_cast<int>(res->ai_addrlen));
    freeaddrinfo(res);

    if (connRes < 0) {
        fd_set wfds;
        FD_ZERO(&wfds);
        FD_SET(fd, &wfds);
        struct timeval tv{};
        tv.tv_sec = config_.connectTimeoutSec;
        tv.tv_usec = 0;

        int sel = select(fd + 1, nullptr, &wfds, nullptr, &tv);
        if (sel <= 0) {
#ifdef _WIN32
            closesocket(fd);
#else
            close(fd);
#endif
            return false;
        }
    }

    // Restore blocking
#ifdef _WIN32
    mode = 0;
    ioctlsocket(fd, FIONBIO, &mode);
#else
    fcntl(fd, F_SETFL, flags);
#endif

    // Disable Nagle's algorithm for low-latency Control Word delivery
    int nodelay = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&nodelay), sizeof(nodelay));

    // CCcam Handshake:
    // Step 1: Read 16-byte random IV from server
    uint8_t srvRandom[16];
    if (!readFull(fd, srvRandom, 16)) {
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    // Step 2: Initialize crypto
    // SHA1(srvRandom) -> key for decrypting / encrypting
    uint8_t hash[20];
    sha1(srvRandom, 16, hash);

    rc4Init(&recvRc4_, hash, 20);
    rc4Init(&sendRc4_, hash, 20);

    // Encrypt srvRandom as challenge response
    uint8_t challengeResp[16];
    rc4Crypt(&sendRc4_, srvRandom, challengeResp, 16);

    // Send challenge response
    if (!writeFull(fd, challengeResp, 16)) {
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    // Step 3: Send credentials (User, Password, Client Node ID)
    // Packet: [Username (20 bytes)] + [Client Node ID (8 bytes)] + [Version string (6 bytes)]
    uint8_t loginBuf[34] = {0};
    std::strncpy(reinterpret_cast<char*>(loginBuf), config_.user.c_str(), 20);
    std::memcpy(loginBuf + 20, clientNodeId_, 8);
    std::memcpy(loginBuf + 28, "2.3.0\0", 6);

    uint8_t encLogin[34];
    rc4Crypt(&sendRc4_, loginBuf, encLogin, 34);

    if (!writeFull(fd, encLogin, 34)) {
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    // Step 4: Read server acknowledge and server node ID (8 bytes)
    uint8_t srvAck[8];
    if (!readFull(fd, srvAck, 8)) {
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    rc4Crypt(&recvRc4_, srvAck, serverNodeId_, 8);

    socketFd = fd;
    return true;
}

bool CCcamClient::sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId, const uint8_t* ecmData, size_t length) {
    if (!connected_.load() || length == 0 || length > 1024) {
        return false;
    }

    std::lock_guard<std::mutex> lock(socketMutex_);
    if (activeSocketFd_ < 0) return false;

    // CCcam ECM frame:
    // Header (4 bytes): [Opcode: 0x01 (MSG_CW_ECM)], [Length MSB], [Length LSB], [Parity / Flags]
    // Payload: [CAID 2B] + [ProvID 4B] + [ServiceID 2B] + [ECM raw data]
    size_t payloadLen = 2 + 4 + 2 + length;
    std::vector<uint8_t> frame(4 + payloadLen);

    frame[0] = 0x01; // MSG_CW_ECM
    frame[1] = static_cast<uint8_t>((payloadLen >> 8) & 0xFF);
    frame[2] = static_cast<uint8_t>(payloadLen & 0xFF);
    frame[3] = (ecmData[0] == 0x81) ? 1 : 0; // Parity

    frame[4] = static_cast<uint8_t>((caid >> 8) & 0xFF);
    frame[5] = static_cast<uint8_t>(caid & 0xFF);

    frame[6] = static_cast<uint8_t>((providerId >> 24) & 0xFF);
    frame[7] = static_cast<uint8_t>((providerId >> 16) & 0xFF);
    frame[8] = static_cast<uint8_t>((providerId >>  8) & 0xFF);
    frame[9] = static_cast<uint8_t>( providerId        & 0xFF);

    frame[10] = static_cast<uint8_t>((serviceId >> 8) & 0xFF);
    frame[11] = static_cast<uint8_t>(serviceId & 0xFF);

    std::memcpy(&frame[12], ecmData, length);

    // Encrypt frame with sendRc4
    rc4Crypt(&sendRc4_, frame.data(), frame.data(), frame.size());

    return writeFull(activeSocketFd_, frame.data(), frame.size());
}

void CCcamClient::workerLoop() {
    auto lastKeepalive = std::chrono::steady_clock::now();

    while (running_.load()) {
        int socketFd = -1;
        BRIDGE_LOGI("CCcamClient: Connecting to %s:%u...", config_.host.c_str(), config_.port);

        if (!connectAndLogin(socketFd)) {
            if (callbacks_.onError) {
                callbacks_.onError("CCcam: Connection or authentication failed");
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(config_.reconnectIntervalMs));
            continue;
        }

        {
            std::lock_guard<std::mutex> lock(socketMutex_);
            activeSocketFd_ = socketFd;
            connected_ = true;
        }

        BRIDGE_LOGI("CCcamClient: Connected and authenticated with server %s:%u",
                    config_.host.c_str(), config_.port);

        if (callbacks_.onConnectionChanged) {
            callbacks_.onConnectionChanged(true);
        }
        if (callbacks_.onLoginSuccess) {
            callbacks_.onLoginSuccess(serverNodeId_, 8);
        }

        lastKeepalive = std::chrono::steady_clock::now();

        // Message receive loop
        while (running_.load() && connected_.load()) {
            // Read 4-byte message header
            uint8_t rawHeader[4];
            if (!readFull(socketFd, rawHeader, 4)) {
                break;
            }

            uint8_t decHeader[4];
            rc4Crypt(&recvRc4_, rawHeader, decHeader, 4);

            uint8_t opcode = decHeader[0];
            uint16_t msgLen = (static_cast<uint16_t>(decHeader[1]) << 8) | decHeader[2];

            if (msgLen > 4096) {
                BRIDGE_LOGE("CCcamClient: Malformed message length: %u", msgLen);
                break;
            }

            std::vector<uint8_t> payload(msgLen);
            if (msgLen > 0 && !readFull(socketFd, payload.data(), msgLen)) {
                break;
            }

            if (msgLen > 0) {
                rc4Crypt(&recvRc4_, payload.data(), payload.data(), msgLen);
            }

            // Opcode 0x01: CW response (16 bytes = 8 bytes Even + 8 bytes Odd)
            if (opcode == 0x01 && payload.size() >= 16) {
                const uint8_t* evenCw = payload.data();
                const uint8_t* oddCw = payload.data() + 8;

                if (callbacks_.onControlWord) {
                    callbacks_.onControlWord(0, 0, evenCw, 8); // Even CW
                    callbacks_.onControlWord(0, 1, oddCw, 8);  // Odd CW
                }
            }

            // Keepalive ping every 45 seconds (Opcode 0x06 MSG_KEEPALIVE)
            auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::seconds>(now - lastKeepalive).count() >= 45) {
                uint8_t ping[4] = { 0x06, 0x00, 0x00, 0x00 };
                rc4Crypt(&sendRc4_, ping, ping, 4);
                writeFull(socketFd, ping, 4);
                lastKeepalive = now;
            }
        }

        // Clean up disconnected socket
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

bool CCcamClient::testConnection(
    const std::string& host,
    uint16_t port,
    const std::string& user,
    const std::string& password,
    int timeoutMs,
    std::string& outError
) {
    CCcamConfig cfg;
    cfg.host = host;
    cfg.port = port;
    cfg.user = user;
    cfg.password = password;
    cfg.connectTimeoutSec = std::max(1, timeoutMs / 1000);

    CCcamCallbacks cbs;
    CCcamClient client(cfg, cbs);

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
        outError = "CCcam: Connection refused or authentication failed";
    }
    return ok;
}

} // namespace oscam::cccam

