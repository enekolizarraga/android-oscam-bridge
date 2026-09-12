// bridge/include/RadegastClient.h
//
// Radegast v3 Protocol Client for OSCam domestic connections.
// Ultra-low latency TLV-based ECM/CW protocol (OSCam [radegast] port 678).
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#pragma once

#include "IOscamClient.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace oscam::radegast {

/**
 * @brief Radegast TLV tags.
 */
constexpr uint8_t TAG_ECM_REQUEST  = 0x02;
constexpr uint8_t TAG_CAID         = 0x02;
constexpr uint8_t TAG_ECM_DATA     = 0x03;
constexpr uint8_t TAG_CW_RESPONSE  = 0x05;
constexpr uint8_t TAG_PROVIDER     = 0x06;
constexpr uint8_t TAG_SERVICE_ID   = 0x07;

/**
 * @brief Connection configuration for Radegast server.
 */
struct RadegastConfig {
    std::string host{"192.168.1.100"};
    uint16_t    port{678};
    uint16_t    caid{0x1810};
    int         connectTimeoutSec{3};
    int         recvTimeoutSec{5};
    int         reconnectIntervalMs{2000};
};

/**
 * @brief Production-grade Radegast protocol client.
 */
class RadegastClient : public IOscamClient {
public:
    explicit RadegastClient(RadegastConfig config, OscamClientCallbacks callbacks);
    ~RadegastClient() override;

    RadegastClient(const RadegastClient&) = delete;
    RadegastClient& operator=(const RadegastClient&) = delete;
    RadegastClient(RadegastClient&&) = delete;
    RadegastClient& operator=(RadegastClient&&) = delete;

    bool start() override;
    void stop() override;
    bool isConnected() const override;

    bool sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId,
                 const uint8_t* ecmData, size_t length) override;

    ProtocolType getProtocolType() const override { return ProtocolType::RADEGAST; }
    std::string getProtocolName() const override { return "Radegast v3 (TCP 678)"; }

    /// Static diagnostic tester: checks if Radegast port on OSCam is reachable.
    static bool testConnection(const std::string& host, uint16_t port, int timeoutMs, std::string& outError);

private:
    void workerLoop();
    bool connectSocket(int& socketFd);
    bool readFull(int socketFd, uint8_t* buf, size_t count);
    bool writeFull(int socketFd, const uint8_t* buf, size_t count);

    RadegastConfig config_;
    OscamClientCallbacks callbacks_;

    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    std::thread workerThread_;

    mutable std::mutex socketMutex_;
    int activeSocketFd_{-1};
    std::atomic<uint16_t> activeServiceId_{0};
};

} // namespace oscam::radegast

