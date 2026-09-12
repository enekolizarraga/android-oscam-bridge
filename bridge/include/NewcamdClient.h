// bridge/include/NewcamdClient.h
//
// Newcamd (v5.25) Protocol Client implementation for Android TV CAS Bridge.
// Enables connecting to domestic Newcamd cardservers as primary or fallback readers.
//
// Features:
//  - Fully self-contained DES / Triple-DES crypto engine (no external OpenSSL dependency).
//  - Multi-server support with individual port, credentials, and 14-byte DES keys.
//  - Automatic login handshake (MSG_CLIENT_2_SERVER_LOGIN / MSG_SERVER_2_CLIENT_LOGIN_ACK).
//  - Automatic keepalive ping loop (MSG_KEEPALIVE).
//  - Asynchronous ECM dispatch and Control Word extraction.
//  - Thread-safe non-blocking I/O worker thread with exponential reconnect backoff.
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

namespace oscam::newcamd {

/**
 * @brief Configuration parameters for a Newcamd server connection.
 */
struct NewcamdConfig {
    std::string host{"192.168.1.100"};
    uint16_t port{10000};
    std::string user{"android_tv"};
    std::string password{"android_tv"};
    std::vector<uint8_t> desKey{
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x10, 0x11, 0x12, 0x13, 0x14
    };
    uint16_t caid{0x1810};
    int connectTimeoutSec{4};
    int recvTimeoutSec{8};
    int reconnectIntervalMs{2000};
};

/**
 * @brief Callbacks invoked by NewcamdClient upon protocol events.
 */
struct NewcamdCallbacks {
    /// Invoked when a valid Control Word is returned by the Newcamd server.
    /// parity: 0 = EVEN, 1 = ODD. cw: 8-byte control word.
    std::function<void(uint16_t serviceId, uint8_t parity, const uint8_t* cw, size_t length)> onControlWord;

    /// Invoked upon successful login acknowledging available CAID and providers.
    std::function<void(uint16_t caid, const std::vector<uint32_t>& providers)> onLoginSuccess;

    /// Invoked when connection state changes (connected = true/false).
    std::function<void(bool connected)> onConnectionChanged;

    /// Invoked when an error or warning occurs.
    std::function<void(const std::string& reason)> onError;
};

/**
 * @brief Production-grade Newcamd v5.25 TCP client.
 */
class NewcamdClient {
public:
    explicit NewcamdClient(NewcamdConfig config, NewcamdCallbacks callbacks);
    ~NewcamdClient();

    // Disable copy / move
    NewcamdClient(const NewcamdClient&) = delete;
    NewcamdClient& operator=(const NewcamdClient&) = delete;
    NewcamdClient(NewcamdClient&&) = delete;
    NewcamdClient& operator=(NewcamdClient&&) = delete;

    /// Starts the background network thread and establishes connection.
    bool start();

    /// Stops network thread and gracefully disconnects.
    void stop();

    /// Dispatches an ECM packet to the Newcamd server.
    bool sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId, const uint8_t* ecmData, size_t length);

    /// Checks if currently connected and authenticated.
    bool isConnected() const;

    /// Static diagnostic helper: tests connection and login without launching long-lived threads.
    static bool testConnection(
        const std::string& host,
        uint16_t port,
        const std::string& user,
        const std::string& password,
        const std::vector<uint8_t>& desKey,
        int timeoutMs,
        std::string& outError
    );

    /// Static utility: parse 28-character hex string into 14-byte DES key vector.
    static std::vector<uint8_t> parseDesKeyHex(const std::string& hexStr);

    /// Static utility: 3DES EDE2 encryption / decryption of an 8-byte block using a 16-byte key.
    static void des3Crypt(const uint8_t* in, uint8_t* out, const uint8_t* key16, bool decrypt);

private:
    void workerLoop();
    bool connectAndLogin(int& socketFd);
    bool readFull(int socketFd, uint8_t* buffer, size_t count);
    bool writeFull(int socketFd, const uint8_t* buffer, size_t count);

    NewcamdConfig config_;
    NewcamdCallbacks callbacks_;

    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    std::thread workerThread_;

    mutable std::mutex socketMutex_;
    int activeSocketFd_{-1};

    // Derived session encryption keys
    uint8_t sessionKey_[16]{0};
};

} // namespace oscam::newcamd
