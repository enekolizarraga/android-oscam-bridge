// bridge/include/CCcamClient.h
//
// CCcam (v2.0.11 / v2.3.0) Protocol Client implementation for Android TV CAS Bridge.
// Enables connecting to domestic CCcam cardservers as primary or fallback readers.
//
// Features:
//  - Fully self-contained RC4 stream cipher and SHA-1 cryptographic engine (no external OpenSSL dependency).
//  - 16-byte random node ID handshake and authentication sequence.
//  - Asynchronous ECM dispatch (MSG_CW_ECM) and Control Word extraction.
//  - Background network worker thread with automatic keepalive (MSG_KEEPALIVE) and reconnect backoff.
//  - Static diagnostic helper for rapid latency benchmarking.
//
// Author: android-oscam-bridge

#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "IOscamClient.h"

namespace oscam::cccam {

/**
 * @brief Configuration parameters for a CCcam server connection.
 */
struct CCcamConfig {
    std::string host{"192.168.1.100"};
    uint16_t port{12000};
    std::string user{"android_tv"};
    std::string password{"android_tv"};
    uint16_t caid{0x1810};
    int connectTimeoutSec{4};
    int recvTimeoutSec{8};
    int reconnectIntervalMs{2000};
};

/**
 * @brief Callbacks invoked by CCcamClient upon protocol events.
 */
struct CCcamCallbacks {
    /// Invoked when a valid Control Word is returned by the CCcam server.
    /// parity: 0 = EVEN, 1 = ODD. cw: 8-byte control word.
    std::function<void(uint16_t serviceId, uint8_t parity, const uint8_t* cw, size_t length)> onControlWord;

    /// Invoked upon successful login acknowledging server node ID.
    std::function<void(const uint8_t* serverNodeId, size_t length)> onLoginSuccess;

    /// Invoked when connection state changes (connected = true/false).
    std::function<void(bool connected)> onConnectionChanged;

    /// Invoked when an error or warning occurs.
    std::function<void(const std::string& reason)> onError;
};

/**
 * @brief Self-contained CCcam proprietary stream cipher block (cc_crypt).
 */
struct CcCryptBlock {
    uint8_t keytable[256]{0};
    uint8_t state{0};
    uint8_t counter{0};
    uint8_t sum{0};
};

enum class CcCryptMode : uint8_t {
    Decrypt = 0,
    Encrypt = 1
};

/**
 * @brief Production-grade CCcam v2.3.0 TCP client.
 */
class CCcamClient : public IOscamClient {
public:
    explicit CCcamClient(CCcamConfig config, CCcamCallbacks callbacks);
    ~CCcamClient() override;

    // Disable copy / move
    CCcamClient(const CCcamClient&) = delete;
    CCcamClient& operator=(const CCcamClient&) = delete;
    CCcamClient(CCcamClient&&) = delete;
    CCcamClient& operator=(CCcamClient&&) = delete;

    /// Starts the background network thread and establishes connection.
    bool start() override;

    /// Stops network thread and gracefully disconnects.
    void stop() override;

    /// Dispatches an ECM packet to the CCcam server.
    bool sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId, const uint8_t* ecmData, size_t length) override;

    /// Checks if currently connected and authenticated.
    bool isConnected() const override;

    ProtocolType getProtocolType() const override { return ProtocolType::CCCAM; }
    std::string getProtocolName() const override { return "CCcam v2.3.0 (TCP)"; }

    /// Static diagnostic helper: tests connection and login without launching long-lived threads.
    static bool testConnection(
        const std::string& host,
        uint16_t port,
        const std::string& user,
        const std::string& password,
        int timeoutMs,
        std::string& outError
    );

    // Cryptographic utility helpers (publicly exposed for testing & compliance)
    static void ccInitCrypt(CcCryptBlock* block, const uint8_t* key, size_t keyLen);
    static void ccCrypt(CcCryptBlock* block, uint8_t* data, size_t len, CcCryptMode mode);
    static void ccXor(uint8_t* buf);
    static void ccCwCrypt(uint8_t* cws, uint64_t nodeId, uint32_t cardId);
    static void sha1(const uint8_t* data, size_t length, uint8_t outDigest[20]);

private:
    void workerLoop();
    bool connectAndLogin(int& socketFd);
    bool readFull(int socketFd, uint8_t* buffer, size_t count);
    bool writeFull(int socketFd, const uint8_t* buffer, size_t count);
    bool sendMsg(int socketFd, uint8_t cmd, const uint8_t* payload, size_t payloadLen);
    bool recvMsg(int socketFd, uint8_t& outCmd, std::vector<uint8_t>& outPayload);

    CCcamConfig config_;
    CCcamCallbacks callbacks_;

    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    std::thread workerThread_;

    mutable std::mutex socketMutex_;
    int activeSocketFd_{-1};

    CcCryptBlock sendBlock_;
    CcCryptBlock recvBlock_;
    uint8_t serverNodeId_[8]{0};
    uint8_t clientNodeId_[8]{0};
};

} // namespace oscam::cccam

