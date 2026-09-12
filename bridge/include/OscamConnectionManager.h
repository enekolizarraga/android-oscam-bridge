// bridge/include/OscamConnectionManager.h
//
// Multi-protocol Connection Manager and Orchestrator for OSCam.
// Dynamically manages DVBAPI (TCP/UNIX), Cs378x, Radegast, Newcamd, CCcam, and WebIF.
// Provides automated failover, health metrics, and thread-safe ECM dispatching.
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#pragma once

#include "IOscamClient.h"
#include "DvbapiClient.h"
#include "Cs378xClient.h"
#include "RadegastClient.h"
#include "NewcamdClient.h"
#include "CCcamClient.h"
#include "OscamWebIfClient.h"

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace oscam {

/**
 * @brief Universal server endpoint profile.
 */
struct ServerProfile {
    std::string   name{"Primary Server"};
    ProtocolType  protocol{ProtocolType::DVBAPI_TCP};
    std::string   host{"192.168.1.100"};
    uint16_t      port{9000};
    std::string   user{"android_tv"};
    std::string   password{"android_tv"};
    std::string   desKey{"0102030405060708091011121314"};
    uint16_t      caid{0x1810};
    int           connectTimeoutSec{4};
    int           recvTimeoutSec{8};
    int           reconnectIntervalMs{2000};
    bool          enabled{true};
    bool          isPrimary{true};
};

/**
 * @brief Operational statistics across all managed OSCam connections.
 */
struct ConnectionStats {
    std::atomic<uint64_t> ecmSentCount{0};
    std::atomic<uint64_t> cwReceivedCount{0};
    std::atomic<uint64_t> emmSentCount{0};
    std::atomic<uint32_t> lastCwTimeMs{0};
    std::atomic<uint32_t> reconnectCount{0};
    std::atomic<uint32_t> failoverCount{0};
};

/**
 * @brief High-level connection orchestrator for OSCam domestic clients.
 */
class OscamConnectionManager {
public:
    explicit OscamConnectionManager(OscamClientCallbacks callbacks);
    ~OscamConnectionManager();

    OscamConnectionManager(const OscamConnectionManager&) = delete;
    OscamConnectionManager& operator=(const OscamConnectionManager&) = delete;

    /// Configures the active server profiles (primary + fallbacks).
    void setServers(const std::vector<ServerProfile>& profiles);

    /// Launches the connection manager and connects to the primary server.
    bool start();

    /// Stops all running protocol clients.
    void stop();

    /// Checks if currently connected through any protocol.
    bool isConnected() const;

    /// Returns the currently active protocol type.
    ProtocolType getActiveProtocol() const;

    /// Returns the active server profile name and host.
    std::string getActiveServerDescription() const;

    /// Dispatches an ECM packet to the currently active client.
    bool sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId,
                 const uint8_t* ecmData, size_t length);

    /// Manually triggers failover to the next available server profile.
    bool failoverNext();

    /// Diagnostic tester for a specific server profile without affecting active state.
    static bool testServer(const ServerProfile& profile, int timeoutMs, std::string& outResult);

    /// Access live operational metrics.
    const ConnectionStats& getStats() const noexcept { return stats_; }

    /// Returns last recorded error message.
    std::string getLastError() const;

private:
    std::shared_ptr<IOscamClient> createClientForProfile(const ServerProfile& profile);
    void handleConnectionState(bool connected, size_t serverIndex);

    mutable std::mutex mutex_;
    std::vector<ServerProfile> servers_;
    size_t activeIndex_{0};

    std::shared_ptr<IOscamClient> activeClient_;
    OscamClientCallbacks callbacks_;
    ConnectionStats stats_;

    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    std::string lastError_;
};

} // namespace oscam

