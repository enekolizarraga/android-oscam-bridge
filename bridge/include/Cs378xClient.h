// bridge/include/Cs378xClient.h
//
// Native OSCam Camd35 / Cs378x (TCP) Protocol Client for Android TV CAS Bridge.
// Implements OSCam's native binary protocol over TCP with self-contained AES-128
// encryption and MD5 key derivation (zero external OpenSSL dependencies).
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#pragma once

#include "IOscamClient.h"

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace oscam::cs378x {

/**
 * @brief Cs378x command opcodes used by OSCam.
 */
constexpr uint8_t CMD_ECM_REQUEST = 0x00;
constexpr uint8_t CMD_CW_RESPONSE = 0x01;
constexpr uint8_t CMD_EMM_REQUEST = 0x02;
constexpr uint8_t CMD_CARD_INFO   = 0x03;
constexpr uint8_t CMD_KEEPALIVE   = 0x05;
constexpr uint8_t CMD_CONNECT     = 0x08;
constexpr uint8_t CMD_CONNECT_ACK = 0x09;

/**
 * @brief Connection parameters for OSCam Cs378x server.
 */
struct Cs378xConfig {
    std::string host{"192.168.1.100"};
    uint16_t    port{13000};
    std::string user{"android_tv"};
    std::string password{"android_tv"};
    uint16_t    caid{0x1810};
    int         connectTimeoutSec{4};
    int         recvTimeoutSec{8};
    int         reconnectIntervalMs{2000};
};

/**
 * @brief Self-contained AES-128 cipher context.
 */
struct Aes128Key {
    uint32_t roundKeys[44];
};

/**
 * @brief Production-grade native OSCam Cs378x client.
 */
class Cs378xClient : public IOscamClient {
public:
    explicit Cs378xClient(Cs378xConfig config, OscamClientCallbacks callbacks);
    ~Cs378xClient() override;

    Cs378xClient(const Cs378xClient&) = delete;
    Cs378xClient& operator=(const Cs378xClient&) = delete;
    Cs378xClient(Cs378xClient&&) = delete;
    Cs378xClient& operator=(Cs378xClient&&) = delete;

    bool start() override;
    void stop() override;
    bool isConnected() const override;

    bool sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId,
                 const uint8_t* ecmData, size_t length) override;

    ProtocolType getProtocolType() const override { return ProtocolType::CS378X; }
    std::string getProtocolName() const override { return "OSCam Camd35 / Cs378x (TCP)"; }

    /// Static diagnostic tester: verifies TCP connectivity and Cs378x handshake.
    static bool testConnection(const std::string& host, uint16_t port,
                              const std::string& user, const std::string& password,
                              int timeoutMs, std::string& outError);

    // Self-contained cryptographic helpers
    static void md5(const uint8_t* data, size_t length, uint8_t outDigest[16]);
    static void aes128KeySetupEnc(Aes128Key* key, const uint8_t key16[16]);
    static void aes128KeySetupDec(Aes128Key* key, const uint8_t key16[16]);
    static void aes128EncryptBlock(const Aes128Key* key, const uint8_t in[16], uint8_t out[16]);
    static void aes128DecryptBlock(const Aes128Key* key, const uint8_t in[16], uint8_t out[16]);

private:
    void workerLoop();
    bool connectAndAuthenticate(int& socketFd);
    bool sendEncryptedPacket(int socketFd, uint8_t cmd, uint16_t caid, uint32_t provider,
                            uint16_t serviceId, const uint8_t* payload, size_t payloadLen);
    bool readEncryptedPacket(int socketFd, uint8_t& outCmd, uint16_t& outCaid,
                            uint32_t& outProvider, uint16_t& outServiceId,
                            std::vector<uint8_t>& outPayload);

    bool readFull(int socketFd, uint8_t* buf, size_t count);
    bool writeFull(int socketFd, const uint8_t* buf, size_t count);

    Cs378xConfig config_;
    OscamClientCallbacks callbacks_;

    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    std::thread workerThread_;

    mutable std::mutex socketMutex_;
    int activeSocketFd_{-1};

    uint8_t sessionKey_[16]{0};
    Aes128Key aesEncKey_{};
    Aes128Key aesDecKey_{};
    std::atomic<uint16_t> sequenceNumber_{1};
};

} // namespace oscam::cs378x

