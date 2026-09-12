// bridge/OscamWebIfClient.cpp
//
// Implementation of OSCam WebIF HTTP & REST API Client.
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#include "include/OscamWebIfClient.h"
#include "include/BridgeLogger.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <sstream>

#ifdef _WIN32
#  include <winsock2.h>
#  include <ws2tcpip.h>
   using SockLen = int;
#  define INVALID_SOCKET_FD INVALID_SOCKET
#  define CLOSE_SOCKET(s)   ::closesocket(s)
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
#endif

namespace oscam::webif {

OscamWebIfClient::OscamWebIfClient(WebIfConfig config)
    : config_(std::move(config)) {
}

std::string OscamWebIfClient::base64Encode(const std::string& in) {
    static const char* tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    int val = 0, valb = -6;
    for (uint8_t c : in) {
        val = (val << 8) + c;
        valb += 8;
        while (valb >= 0) {
            out.push_back(tbl[(val >> valb) & 0x3F]);
            valb -= 6;
        }
    }
    if (valb > -6) out.push_back(tbl[((val << 8) >> (valb + 8)) & 0x3F]);
    while (out.size() % 4) out.push_back('=');
    return out;
}

bool OscamWebIfClient::httpGet(const std::string& path, std::string& outBody, int& outStatusCode) {
    outStatusCode = 0;
    outBody.clear();

    struct addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    struct addrinfo* res = nullptr;
    std::string portStr = std::to_string(config_.port);
    if (::getaddrinfo(config_.host.c_str(), portStr.c_str(), &hints, &res) != 0 || !res) {
        return false;
    }

    int fd = static_cast<int>(::socket(res->ai_family, res->ai_socktype, res->ai_protocol));
    if (fd < 0) {
        ::freeaddrinfo(res);
        return false;
    }

    // Set non-blocking for connect timeout
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
        tv.tv_sec = config_.timeoutSec;
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

    // Build HTTP Request
    std::ostringstream req;
    req << "GET " << path << " HTTP/1.1\r\n";
    req << "Host: " << config_.host << ":" << config_.port << "\r\n";
    req << "User-Agent: android-oscam-bridge/2.0\r\n";
    req << "Connection: close\r\n";
    if (!config_.user.empty() || !config_.password.empty()) {
        std::string auth = config_.user + ":" + config_.password;
        req << "Authorization: Basic " << base64Encode(auth) << "\r\n";
    }
    req << "\r\n";

    std::string reqStr = req.str();
    ::send(fd, reqStr.data(), static_cast<int>(reqStr.size()), 0);

    // Read response
    std::string response;
    char buf[2048];
    while (true) {
        int r = static_cast<int>(::recv(fd, buf, sizeof(buf) - 1, 0));
        if (r <= 0) break;
        buf[r] = '\0';
        response.append(buf, static_cast<size_t>(r));
    }
    CLOSE_SOCKET(fd);

    if (response.empty()) return false;

    // Parse status line (HTTP/1.x 200 OK)
    size_t firstLineEnd = response.find("\r\n");
    if (firstLineEnd == std::string::npos) return false;

    std::string statusLine = response.substr(0, firstLineEnd);
    size_t codePos = statusLine.find(' ');
    if (codePos != std::string::npos && codePos + 4 <= statusLine.size()) {
        outStatusCode = std::stoi(statusLine.substr(codePos + 1, 3));
    }

    size_t headerEnd = response.find("\r\n\r\n");
    if (headerEnd != std::string::npos) {
        outBody = response.substr(headerEnd + 4);
    } else {
        outBody = response;
    }

    return (outStatusCode == 200);
}

bool OscamWebIfClient::ping(std::string& outVersion) {
    std::string body;
    int code = 0;
    if (!httpGet("/api.html?part=status", body, code) && !httpGet("/status.xml", body, code)) {
        return false;
    }

    // Attempt to extract version string
    size_t pos = body.find("OSCam r");
    if (pos != std::string::npos) {
        size_t end = body.find_first_of("\"< \r\n", pos);
        outVersion = body.substr(pos, end - pos);
    } else {
        outVersion = "OSCam (WebIF OK)";
    }
    return true;
}

bool OscamWebIfClient::queryStatus(OscamServerStatus& outStatus) {
    outStatus = OscamServerStatus{};
    std::string body;
    int code = 0;

    if (!httpGet("/api.html?part=status", body, code)) {
        if (!httpGet("/status.xml", body, code)) {
            return false;
        }
    }

    outStatus.reachable = true;

    size_t verPos = body.find("OSCam r");
    if (verPos != std::string::npos) {
        size_t end = body.find_first_of("\"< \r\n", verPos);
        outStatus.version = body.substr(verPos, end - verPos);
    } else {
        outStatus.version = "OSCam (Active)";
    }

    // Query active ecm.info
    std::string ecmInfo;
    if (queryEcmInfo(ecmInfo)) {
        outStatus.rawEcmInfo = ecmInfo;

        // Parse caid: 0xXXXX
        size_t caidPos = ecmInfo.find("caid:");
        if (caidPos != std::string::npos) {
            size_t end = ecmInfo.find('\n', caidPos);
            outStatus.activeCaid = ecmInfo.substr(caidPos + 5, end - (caidPos + 5));
            outStatus.activeCaid.erase(0, outStatus.activeCaid.find_first_not_of(" \t\r"));
        }

        // Parse reader: XXXX
        size_t rdrPos = ecmInfo.find("reader:");
        if (rdrPos != std::string::npos) {
            size_t end = ecmInfo.find('\n', rdrPos);
            outStatus.activeReader = ecmInfo.substr(rdrPos + 7, end - (rdrPos + 7));
            outStatus.activeReader.erase(0, outStatus.activeReader.find_first_not_of(" \t\r"));
        }

        // Parse response time: XXX ms
        size_t timePos = ecmInfo.find("response time:");
        if (timePos != std::string::npos) {
            size_t msPos = ecmInfo.find("ms", timePos);
            if (msPos != std::string::npos) {
                std::string num = ecmInfo.substr(timePos + 14, msPos - (timePos + 14));
                num.erase(0, num.find_first_not_of(" \t"));
                try {
                    outStatus.lastEcmTimeMs = static_cast<uint32_t>(std::stoul(num));
                } catch (...) {}
            }
        }
    }

    return true;
}

bool OscamWebIfClient::queryEcmInfo(std::string& outEcmInfo) {
    int code = 0;
    return httpGet("/ecm.info", outEcmInfo, code) && code == 200;
}

bool OscamWebIfClient::restartReader(const std::string& readerName) {
    std::string path = "/status.xml?action=restart&reader=" + readerName;
    std::string body;
    int code = 0;
    return httpGet(path, body, code);
}

} // namespace oscam::webif

