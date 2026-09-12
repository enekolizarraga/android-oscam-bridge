// bridge/RadegastClient.cpp
//
// Implementation of Radegast v3 protocol client for OSCam domestic servers.
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#include "include/RadegastClient.h"
#include "include/BridgeLogger.h"

#include <algorithm>
#include <chrono>
#include <cstring>

#ifdef _WIN32
#  include <winsock2.h>
#  include <ws2tcpip.h>
   using SockLen = int;
#  define INVALID_SOCKET_FD INVALID_SOCKET
#  define CLOSE_SOCKET(s)   ::closesocket(s)
#  define SOCK_ERRNO        WSAGetLastError()
#else
#  include <arpa/inet.h>
#  include <fcntl.h>
#  include <netdb.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <sys/select.h>
#  include <sys/socket.h>
#  include <unistd.h>
   using SockLen = socklen_t;
#  define INVALID_SOCKET_FD (-1)
#  define CLOSE_SOCKET(s)   ::close(s)
#  define SOCK_ERRNO        errno
#endif

namespace oscam::radegast {

RadegastClient::RadegastClient(RadegastConfig config, OscamClientCallbacks callbacks)
    : config_(std::move(config))
    , callbacks_(std::move(callbacks)) {
}

RadegastClient::~RadegastClient() {
    stop();
}

bool RadegastClient::start() {
    if (running_.exchange(true)) return true;
    workerThread_ = std::thread(&RadegastClient::workerLoop, this);
    BRIDGE_LOGI("RadegastClient: Started background thread for %s:%u", config_.host.c_str(), config_.port);
    return true;
}

void RadegastClient::stop() {
    if (!running_.exchange(false)) return;

    {
        std::lock_guard<std::mutex> lk(socketMutex_);
        if (activeSocketFd_ != INVALID_SOCKET_FD) {
            CLOSE_SOCKET(activeSocketFd_);
            activeSocketFd_ = INVALID_SOCKET_FD;
        }
    }

    if (workerThread_.joinable()) {
        workerThread_.join();
    }
    connected_ = false;
    BRIDGE_LOGI("RadegastClient: Stopped cleanly.");
}

bool RadegastClient::isConnected() const {
    return connected_.load();
}

bool RadegastClient::sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId,
                             const uint8_t* ecmData, size_t length) {
    if (!connected_ || !ecmData || length == 0) return false;

    activeServiceId_ = serviceId;

    // Radegast ECM frame format:
    // [0x02] [TotalLen]
    //   [TAG_CAID=0x02] [2] [CAID_HI] [CAID_LO]
    //   [TAG_PROVIDER=0x06] [4] [PRV3] [PRV2] [PRV1] [PRV0]
    //   [TAG_SERVICE_ID=0x07] [2] [SID_HI] [SID_LO]
    //   [TAG_ECM_DATA=0x03] [Length] [ECM Bytes...]
    size_t totalPayloadLen = (2 + 2) + (2 + 4) + (2 + 2) + (2 + length);
    if (totalPayloadLen > 250) return false;

    std::vector<uint8_t> frame;
    frame.reserve(2 + totalPayloadLen);
    frame.push_back(TAG_ECM_REQUEST);
    frame.push_back(static_cast<uint8_t>(totalPayloadLen));

    // CAID TLV
    frame.push_back(TAG_CAID);
    frame.push_back(2);
    frame.push_back(static_cast<uint8_t>((caid >> 8) & 0xFF));
    frame.push_back(static_cast<uint8_t>(caid & 0xFF));

    // Provider TLV
    frame.push_back(TAG_PROVIDER);
    frame.push_back(4);
    frame.push_back(static_cast<uint8_t>((providerId >> 24) & 0xFF));
    frame.push_back(static_cast<uint8_t>((providerId >> 16) & 0xFF));
    frame.push_back(static_cast<uint8_t>((providerId >> 8) & 0xFF));
    frame.push_back(static_cast<uint8_t>(providerId & 0xFF));

    // Service ID TLV
    frame.push_back(TAG_SERVICE_ID);
    frame.push_back(2);
    frame.push_back(static_cast<uint8_t>((serviceId >> 8) & 0xFF));
    frame.push_back(static_cast<uint8_t>(serviceId & 0xFF));

    // ECM Data TLV
    frame.push_back(TAG_ECM_DATA);
    frame.push_back(static_cast<uint8_t>(length));
    frame.insert(frame.end(), ecmData, ecmData + length);

    std::lock_guard<std::mutex> lk(socketMutex_);
    if (activeSocketFd_ == INVALID_SOCKET_FD) return false;
    return writeFull(activeSocketFd_, frame.data(), frame.size());
}

void RadegastClient::workerLoop() {
    while (running_) {
        int socketFd = INVALID_SOCKET_FD;
        if (!connectSocket(socketFd)) {
            connected_ = false;
            if (callbacks_.onConnectionChanged) callbacks_.onConnectionChanged(false);
            std::this_thread::sleep_for(std::chrono::milliseconds(config_.reconnectIntervalMs));
            continue;
        }

        {
            std::lock_guard<std::mutex> lk(socketMutex_);
            activeSocketFd_ = socketFd;
            connected_ = true;
        }

        if (callbacks_.onConnectionChanged) callbacks_.onConnectionChanged(true);
        BRIDGE_LOGI("RadegastClient: Connected to OSCam Radegast server at %s:%u", config_.host.c_str(), config_.port);

        while (running_ && connected_) {
            fd_set readFds;
            FD_ZERO(&readFds);
#if defined(_MSC_VER)
#  pragma warning(push)
#  pragma warning(disable: 4548)
#endif
            FD_SET(socketFd, &readFds);
#if defined(_MSC_VER)
#  pragma warning(pop)
#endif

            struct timeval tv{};
            tv.tv_sec = 1;
            tv.tv_usec = 0;

            int sel = ::select(socketFd + 1, &readFds, nullptr, nullptr, &tv);
            if (sel < 0) {
                BRIDGE_LOGE("RadegastClient: select() failed: error %d", SOCK_ERRNO);
                break;
            }

            if (sel > 0 && FD_ISSET(socketFd, &readFds)) {
                uint8_t header[2];
                if (!readFull(socketFd, header, 2)) {
                    BRIDGE_LOGW("RadegastClient: Disconnected by server");
                    break;
                }

                uint8_t respTag = header[0];
                uint8_t respLen = header[1];
                std::vector<uint8_t> payload(respLen);
                if (respLen > 0) {
                    if (!readFull(socketFd, payload.data(), respLen)) {
                        break;
                    }
                }

                if (respTag == TAG_ECM_REQUEST) {
                    // Parse sub-TLVs
                    size_t idx = 0;
                    while (idx + 2 <= payload.size()) {
                        uint8_t subTag = payload[idx];
                        uint8_t subLen = payload[idx + 1];
                        idx += 2;
                        if (idx + subLen > payload.size()) break;

                        if (subTag == TAG_CW_RESPONSE && subLen >= 16) {
                            // 16 bytes: 8 bytes even CW + 8 bytes odd CW
                            const uint8_t* cwPtr = &payload[idx];
                            uint16_t sid = activeServiceId_.load();
                            if (callbacks_.onControlWord) {
                                callbacks_.onControlWord(sid, 0, cwPtr, 8);
                                callbacks_.onControlWord(sid, 1, cwPtr + 8, 8);
                            }
                        }
                        idx += subLen;
                    }
                }
            }
        }

        {
            std::lock_guard<std::mutex> lk(socketMutex_);
            if (activeSocketFd_ != INVALID_SOCKET_FD) {
                CLOSE_SOCKET(activeSocketFd_);
                activeSocketFd_ = INVALID_SOCKET_FD;
            }
            connected_ = false;
        }
        if (callbacks_.onConnectionChanged) callbacks_.onConnectionChanged(false);
    }
}

bool RadegastClient::connectSocket(int& socketFd) {
    struct addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    struct addrinfo* res = nullptr;
    std::string portStr = std::to_string(config_.port);
    if (::getaddrinfo(config_.host.c_str(), portStr.c_str(), &hints, &res) != 0 || !res) {
        if (callbacks_.onError) callbacks_.onError("Could not resolve host: " + config_.host);
        return false;
    }

    int fd = static_cast<int>(::socket(res->ai_family, res->ai_socktype, res->ai_protocol));
    if (fd < 0) {
        ::freeaddrinfo(res);
        return false;
    }

    int noDelay = 1;
    ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&noDelay), sizeof(noDelay));

#ifdef _WIN32
    u_long nb = 1;
    ::ioctlsocket(fd, FIONBIO, &nb);
#else
    int flags = ::fcntl(fd, F_GETFL, 0);
    ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
#endif

    int ret = ::connect(fd, res->ai_addr, static_cast<SockLen>(res->ai_addrlen));
    ::freeaddrinfo(res);

    if (ret != 0) {
        fd_set wfds;
        FD_ZERO(&wfds);
#if defined(_MSC_VER)
#  pragma warning(push)
#  pragma warning(disable: 4548)
#endif
        FD_SET(fd, &wfds);
#if defined(_MSC_VER)
#  pragma warning(pop)
#endif
        struct timeval tv{};
        tv.tv_sec = config_.connectTimeoutSec;
        if (::select(fd + 1, nullptr, &wfds, nullptr, &tv) <= 0) {
            CLOSE_SOCKET(fd);
            return false;
        }
    }

#ifdef _WIN32
    nb = 0;
    ::ioctlsocket(fd, FIONBIO, &nb);
#else
    ::fcntl(fd, F_SETFL, flags);
#endif

    socketFd = fd;
    return true;
}

bool RadegastClient::readFull(int socketFd, uint8_t* buf, size_t count) {
    size_t total = 0;
    while (total < count) {
        int r = static_cast<int>(::recv(socketFd, reinterpret_cast<char*>(buf + total), static_cast<int>(count - total), 0));
        if (r <= 0) return false;
        total += r;
    }
    return true;
}

bool RadegastClient::writeFull(int socketFd, const uint8_t* buf, size_t count) {
    size_t total = 0;
    while (total < count) {
        int w = static_cast<int>(::send(socketFd, reinterpret_cast<const char*>(buf + total), static_cast<int>(count - total), 0));
        if (w <= 0) return false;
        total += w;
    }
    return true;
}

bool RadegastClient::testConnection(const std::string& host, uint16_t port, int timeoutMs, std::string& outError) {
    RadegastConfig cfg;
    cfg.host = host;
    cfg.port = port;
    cfg.connectTimeoutSec = std::max(1, timeoutMs / 1000);

    RadegastClient client(cfg, {});
    int sockFd = INVALID_SOCKET_FD;
    bool ok = client.connectSocket(sockFd);
    if (ok) {
        CLOSE_SOCKET(sockFd);
        outError.clear();
        return true;
    }
    outError = "Connection refused on Radegast port";
    return false;
}

} // namespace oscam::radegast

