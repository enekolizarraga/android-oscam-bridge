// bridge/include/IOscamClient.h
//
// Unified protocol abstraction interface for OSCam client connections.
// Provides a polymorphic contract implemented by all connection methods:
// DVBAPI (TCP / UNIX Socket), Camd35 / Cs378x, Radegast, Newcamd, CCcam, and WebIF.
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace oscam {

/**
 * @brief Supported communication protocols for OSCam domestic servers.
 */
enum class ProtocolType : uint8_t {
    DVBAPI_TCP  = 0,  ///< Native OSCam dvbapi over TCP (port 9000)
    DVBAPI_UNIX = 1,  ///< Native OSCam dvbapi over UNIX domain socket (/tmp/camd.socket)
    CS378X      = 2,  ///< OSCam native Camd35 over TCP with AES-128 crypto
    RADEGAST    = 3,  ///< Radegast v3 low-latency ECM protocol (port 678)
    NEWCAMD     = 4,  ///< Newcamd v5.25 protocol with DES/3DES encryption
    CCCAM       = 5,  ///< CCcam v2.3.0 protocol with RC4/SHA1 crypto
    OSCAM_WEBIF = 6   ///< OSCam WebIF HTTP/REST management & diagnostic API
};

/**
 * @brief Common callbacks for all OSCam protocol clients.
 */
struct OscamClientCallbacks {
    /// Dispatched when a valid Control Word is returned by the cardserver.
    std::function<void(uint16_t serviceId, uint8_t parity, const uint8_t* cw, size_t length)> onControlWord;

    /// Dispatched when connection state changes (connected / disconnected).
    std::function<void(bool connected)> onConnectionChanged;

    /// Dispatched upon communication errors or protocol anomalies.
    std::function<void(const std::string& reason)> onError;

    /// Dispatched when server information or card status is received.
    std::function<void(const std::string& info)> onServerInfo;
};

/**
 * @brief Polymorphic client interface for connecting to OSCam.
 */
class IOscamClient {
public:
    virtual ~IOscamClient() = default;

    /// Launches the client worker thread and begins connection cycle.
    virtual bool start() = 0;

    /// Stops network threads and gracefully terminates connections.
    virtual void stop() = 0;

    /// Returns true if currently connected and authenticated.
    virtual bool isConnected() const = 0;

    /// Dispatches an ECM payload to request a descrambling Control Word.
    virtual bool sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId,
                         const uint8_t* ecmData, size_t length) = 0;

    /// Returns the active protocol type.
    virtual ProtocolType getProtocolType() const = 0;

    /// Returns human-readable protocol name.
    virtual std::string getProtocolName() const = 0;
};

inline const char* protocolTypeToString(ProtocolType type) {
    switch (type) {
        case ProtocolType::DVBAPI_TCP:  return "DVBAPI (TCP)";
        case ProtocolType::DVBAPI_UNIX: return "DVBAPI (UNIX Socket)";
        case ProtocolType::CS378X:      return "Camd35 / Cs378x (TCP)";
        case ProtocolType::RADEGAST:    return "Radegast v3";
        case ProtocolType::NEWCAMD:     return "Newcamd v5.25";
        case ProtocolType::CCCAM:       return "CCcam v2.3.0";
        case ProtocolType::OSCAM_WEBIF: return "OSCam WebIF REST API";
        default:                        return "Unknown";
    }
}

} // namespace oscam

