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
// Self-Contained CCcam Stream Cipher Implementation (cc_crypt)
// ===========================================================================

void CCcamClient::ccInitCrypt(CcCryptBlock* block, const uint8_t* key, size_t keyLen) {
    if (!block || !key || keyLen == 0) return;
    for (int i = 0; i < 256; ++i) {
        block->keytable[i] = static_cast<uint8_t>(i);
    }
    uint8_t j = 0;
    for (int i = 0; i < 256; ++i) {
        j = static_cast<uint8_t>(j + key[i % keyLen] + block->keytable[i]);
        std::swap(block->keytable[i], block->keytable[j]);
    }
    block->state = key[0];
    block->counter = 0;
    block->sum = 0;
}

void CCcamClient::ccCrypt(CcCryptBlock* block, uint8_t* data, size_t len, CcCryptMode mode) {
    if (!block || !data) return;
    for (size_t i = 0; i < len; ++i) {
        block->counter = static_cast<uint8_t>(block->counter + 1);
        block->sum = static_cast<uint8_t>(block->sum + block->keytable[block->counter]);
        std::swap(block->keytable[block->counter], block->keytable[block->sum]);

        uint8_t z = data[i];
        data[i] = static_cast<uint8_t>(z ^ block->keytable[(block->keytable[block->counter] + block->keytable[block->sum]) & 0xFF]);
        data[i] ^= block->state;
        if (mode == CcCryptMode::Decrypt) {
            z = data[i];
        }
        block->state = static_cast<uint8_t>(block->state ^ z);
    }
}

void CCcamClient::ccXor(uint8_t* buf) {
    if (!buf) return;
    const char cccamMagic[] = "CCcam";
    for (uint8_t i = 0; i < 8; ++i) {
        buf[8 + i] = static_cast<uint8_t>(i * buf[i]);
        if (i <= 5) {
            buf[i] ^= static_cast<uint8_t>(cccamMagic[i]);
        }
    }
}

void CCcamClient::ccCwCrypt(uint8_t* cws, uint64_t nodeId, uint32_t cardId) {
    if (!cws) return;
    for (int i = 0; i < 16; ++i) {
        uint8_t tmp = static_cast<uint8_t>(cws[i] ^ ((nodeId >> (4 * i)) & 0xFF));
        if (i & 1) {
            tmp = static_cast<uint8_t>(~tmp);
        }
        cws[i] = static_cast<uint8_t>(((cardId >> (2 * i)) & 0xFF) ^ tmp);
    }
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

bool CCcamClient::sendMsg(int socketFd, uint8_t cmd, const uint8_t* payload, size_t payloadLen) {
    std::vector<uint8_t> netbuf(4 + payloadLen);
    netbuf[0] = 0; // flag / index
    netbuf[1] = cmd;
    netbuf[2] = static_cast<uint8_t>((payloadLen >> 8) & 0xFF);
    netbuf[3] = static_cast<uint8_t>(payloadLen & 0xFF);
    if (payload && payloadLen > 0) {
        std::memcpy(netbuf.data() + 4, payload, payloadLen);
    }
    ccCrypt(&sendBlock_, netbuf.data(), netbuf.size(), CcCryptMode::Encrypt);
    return writeFull(socketFd, netbuf.data(), netbuf.size());
}

bool CCcamClient::recvMsg(int socketFd, uint8_t& outCmd, std::vector<uint8_t>& outPayload) {
    uint8_t hdr[4];
    if (!readFull(socketFd, hdr, 4)) return false;
    ccCrypt(&recvBlock_, hdr, 4, CcCryptMode::Decrypt);
    outCmd = hdr[1];
    uint16_t size = (static_cast<uint16_t>(hdr[2]) << 8) | hdr[3];
    if (size > 4096) return false;
    outPayload.resize(size);
    if (size > 0) {
        if (!readFull(socketFd, outPayload.data(), size)) return false;
        ccCrypt(&recvBlock_, outPayload.data(), size, CcCryptMode::Decrypt);
    }
    return true;
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
    int keepalive = 1;
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, reinterpret_cast<const char*>(&keepalive), sizeof(keepalive));

    // CCcam Handshake (strictly compliant with CCcam 2.x & OSCam module-cccam.c specification):
    // Step 1: Read 16-byte random seed from server
    uint8_t data[16];
    if (!readFull(fd, data, 16)) {
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    // Step 2: XOR init bytes with "CCcam"
    ccXor(data);

    // Step 3: SHA1(data) -> hash (20 bytes)
    uint8_t hash[20];
    sha1(data, 16, hash);

    // Step 4: Initialize cryptographic states (DECRYPT with hash then crypt data; ENCRYPT with data then crypt hash)
    ccInitCrypt(&recvBlock_, hash, 20);
    ccCrypt(&recvBlock_, data, 16, CcCryptMode::Decrypt);
    ccInitCrypt(&sendBlock_, data, 16);
    ccCrypt(&sendBlock_, hash, 20, CcCryptMode::Decrypt);

    // Step 5: Send encrypted hash (20 bytes) to server
    uint8_t sendHash[20];
    std::memcpy(sendHash, hash, 20);
    ccCrypt(&sendBlock_, sendHash, 20, CcCryptMode::Encrypt);
    if (!writeFull(fd, sendHash, 20)) {
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    // Step 6: Send username (20 bytes, 0-padded) encrypted
    uint8_t userBuf[20] = {0};
    std::memcpy(userBuf, config_.user.data(), std::min(config_.user.size(), size_t(20)));
    ccCrypt(&sendBlock_, userBuf, 20, CcCryptMode::Encrypt);
    if (!writeFull(fd, userBuf, 20)) {
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    // Step 7: Password advancement & sending "CCcam\0" challenge
    // In CCcam protocol, the password encrypts through sendBlock_ to advance cipher state
    std::vector<uint8_t> pwdBuf(config_.password.begin(), config_.password.end());
    ccCrypt(&sendBlock_, pwdBuf.data(), pwdBuf.size(), CcCryptMode::Encrypt);

    uint8_t cccamMagic[6] = { 'C', 'C', 'c', 'a', 'm', '\0' };
    ccCrypt(&sendBlock_, cccamMagic, 6, CcCryptMode::Encrypt);
    if (!writeFull(fd, cccamMagic, 6)) {
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    // Step 8: Read 20-byte server password ACK
    uint8_t srvAck[20];
    if (!readFull(fd, srvAck, 20)) {
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    ccCrypt(&recvBlock_, srvAck, 20, CcCryptMode::Decrypt);
    if (std::memcmp(srvAck, "CCcam\0", 6) != 0 && std::memcmp(srvAck, "CCcam", 5) != 0) {
        BRIDGE_LOGE("CCcam: Authentication rejected by server (invalid username/password)");
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    // Step 9: Send client data (MSG_CLI_DATA = 0x00, size = 93 bytes)
    const size_t cliDataSize = 20 + 8 + 1 + 32 + 32;
    uint8_t cliData[cliDataSize] = {0};
    std::memcpy(cliData, config_.user.data(), std::min(config_.user.size(), size_t(20)));
    std::memcpy(cliData + 20, clientNodeId_, 8);
    cliData[28] = 0; // want_emu = 0
    std::memcpy(cliData + 29, "2.3.0", 5);
    std::memcpy(cliData + 61, "3367", 4);
    cliData[28] = config_.wantEmu; // 0 = standard, 1 = want EMU
    std::string ver = config_.version.empty() ? "2.3.0" : config_.version;
    std::string bld = config_.build.empty() ? "3367" : config_.build;
    std::memcpy(cliData + 29, ver.data(), std::min(ver.size(), size_t(31)));
    std::memcpy(cliData + 61, bld.data(), std::min(bld.size(), size_t(31)));
    if (!sendMsg(fd, 0x00, cliData, cliDataSize)) { // MSG_CLI_DATA
#ifdef _WIN32
        closesocket(fd);
#else
        close(fd);
#endif
        return false;
    }

    socketFd = fd;
    return true;
}

bool CCcamClient::sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId, const uint8_t* ecmData, size_t length) {
    if (!connected_.load() || length == 0 || length > 1024) {
        return false;
    }

    std::lock_guard<std::mutex> lock(socketMutex_);
    if (activeSocketFd_ < 0) return false;

    // CCcam ECM payload:
    // [caid: 2B] + [providerId: 4B] + [cardId: 4B] + [serviceId: 2B] + [ecmlen: 1B] + [ecmData]
    size_t payloadLen = 13 + length;
    std::vector<uint8_t> frame(payloadLen, 0);

    frame[0] = static_cast<uint8_t>((caid >> 8) & 0xFF);
    frame[1] = static_cast<uint8_t>(caid & 0xFF);

    frame[2] = static_cast<uint8_t>((providerId >> 24) & 0xFF);
    frame[3] = static_cast<uint8_t>((providerId >> 16) & 0xFF);
    frame[4] = static_cast<uint8_t>((providerId >>  8) & 0xFF);
    frame[5] = static_cast<uint8_t>( providerId        & 0xFF);

    // cardId [6..9] = 0 (default share card)

    frame[10] = static_cast<uint8_t>((serviceId >> 8) & 0xFF);
    frame[11] = static_cast<uint8_t>(serviceId & 0xFF);

    frame[12] = static_cast<uint8_t>(length & 0xFF);

    std::memcpy(frame.data() + 13, ecmData, length);

    return sendMsg(activeSocketFd_, 0x01, frame.data(), frame.size()); // MSG_CW_ECM = 0x01
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
            uint8_t opcode = 0;
            std::vector<uint8_t> payload;
            if (!recvMsg(socketFd, opcode, payload)) {
                break;
            }

            // Opcode 0x01: MSG_CW_ECM response (Control Word payload)
            if (opcode == 0x01 && payload.size() >= 16) {
                // Decode CW if encoded with node ID
                uint64_t nodeId64 = 0;
                for (int i = 0; i < 8; ++i) {
                    nodeId64 = (nodeId64 << 8) | clientNodeId_[i];
                // Check if payload is all zeros (ECM rejected / not found by server)
                bool isAllZero = true;
                for (size_t i = 0; i < 16; ++i) {
                    if (payload[i] != 0) { isAllZero = false; break; }
                }
                ccCwCrypt(payload.data(), nodeId64, 0);
                if (isAllZero) {
                    BRIDGE_LOGD("CCcamClient: Received null CW from server (service not decoded or rejected)");
                    continue;
                }

                // DVB-CSA Checksum verification lambda:
                // cw[3] = (cw[0]+cw[1]+cw[2]) & 0xFF; cw[7] = (cw[4]+cw[5]+cw[6]) & 0xFF
                auto isDvbChecksumOk = [](const uint8_t* cw) -> bool {
                    return (cw[3] == static_cast<uint8_t>((cw[0] + cw[1] + cw[2]) & 0xFF)) &&
                           (cw[7] == static_cast<uint8_t>((cw[4] + cw[5] + cw[6]) & 0xFF));
                };

                // If server returned plain CWs that already satisfy DVB-CSA checksums,
                // we do not re-crypt with node ID.
                bool directValid = isDvbChecksumOk(payload.data()) && isDvbChecksumOk(payload.data() + 8);
                if (!directValid) {
                    // Try node ID decoding
                    uint8_t decodedCw[16];
                    std::memcpy(decodedCw, payload.data(), 16);
                    uint64_t nodeId64 = 0;
                    for (int i = 0; i < 8; ++i) {
                        nodeId64 = (nodeId64 << 8) | clientNodeId_[i];
                    }
                    ccCwCrypt(decodedCw, nodeId64, 0);

                    if (isDvbChecksumOk(decodedCw) || isDvbChecksumOk(decodedCw + 8)) {
                        std::memcpy(payload.data(), decodedCw, 16);
                    }
                }

                const uint8_t* evenCw = payload.data();
                const uint8_t* oddCw  = payload.data() + 8;

                if (callbacks_.onControlWord) {
                    callbacks_.onControlWord(0, 0, evenCw, 8); // Even CW
                    callbacks_.onControlWord(0, 1, oddCw, 8);  // Odd CW
                }
            } else if (opcode == 0x08 && payload.size() >= 8) { // MSG_SRV_DATA
                std::memcpy(serverNodeId_, payload.data(), 8);
            } else if (opcode == 0x06) { // MSG_KEEPALIVE
                // ACK keepalive
                sendMsg(socketFd, 0x06, nullptr, 0);
            }

            // Periodic client keepalive every 45 seconds (MSG_KEEPALIVE = 0x06)
            // Periodic client keepalive every 25 seconds (MSG_KEEPALIVE = 0x06) to maintain NAT mapping
            auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::seconds>(now - lastKeepalive).count() >= 45) {
            if (std::chrono::duration_cast<std::chrono::seconds>(now - lastKeepalive).count() >= 25) {
                sendMsg(socketFd, 0x06, nullptr, 0);
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
    const std::string& version,
    const std::string& build,
    int timeoutMs,
    std::string& outError
) {
    CCcamConfig cfg;
    cfg.host = host;
    cfg.port = port;
    cfg.user = user;
    cfg.password = password;
    cfg.version = version.empty() ? "2.3.0" : version;
    cfg.build = build.empty() ? "3367" : build;
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
        outError = "CCcam: Connection refused or authentication failed (check host, port, user and password)";
        outError = "CCcam: Connection refused or authentication failed (check host, port, user, password and version)";
    }
    return ok;
}

} // namespace oscam::cccam

