// bridge/include/OscamWebIfClient.h
//
// OSCam WebIF HTTP & REST API Client for diagnostics, health monitoring,
// reader status, and live ECM metrics.
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace oscam::webif {

/**
 * @brief Configuration parameters for OSCam WebIF HTTP interface.
 */
struct WebIfConfig {
    std::string host{"192.168.1.100"};
    uint16_t    port{8888};          ///< Default OSCam WebIF port
    std::string user{"admin"};
    std::string password{"admin"};
    int         timeoutSec{3};
};

/**
 * @brief Snapshot of OSCam server status fetched from WebIF.
 */
struct OscamServerStatus {
    bool        reachable{false};
    std::string version;             ///< e.g. "OSCam r11725"
    uint32_t    uptimeSec{0};
    int         totalReaders{0};
    int         activeReaders{0};
    int         connectedClients{0};
    std::string activeCaid;
    std::string activeReader;
    uint32_t    lastEcmTimeMs{0};
    std::string rawEcmInfo;
};

/**
 * @brief Native HTTP/REST client for querying and managing OSCam WebIF.
 */
class OscamWebIfClient {
public:
    explicit OscamWebIfClient(WebIfConfig config);

    /// Checks if the OSCam WebIF is reachable and credentials are valid.
    bool ping(std::string& outVersion);

    /// Queries the full status snapshot of the OSCam server.
    bool queryStatus(OscamServerStatus& outStatus);

    /// Reads live ECM decoding information from /ecm.info.
    bool queryEcmInfo(std::string& outEcmInfo);

    /// Restarts a specific reader on OSCam (/status.xml?action=restart&reader=...).
    bool restartReader(const std::string& readerName);

    /// Base64 helper for HTTP Basic Authentication.
    static std::string base64Encode(const std::string& in);

private:
    bool httpGet(const std::string& path, std::string& outBody, int& outStatusCode);

    WebIfConfig config_;
};

} // namespace oscam::webif

