// tests/CCcamClientTest.cpp
//
// Unit tests for CCcamClient, self-contained RC4 stream cipher, and SHA-1 engine.
// Validates cryptographic correctness and client lifecycle without requiring a live CCcam cardserver.

#include <gtest/gtest.h>
#include "CCcamClient.h"

#include <array>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

using namespace oscam::cccam;

// Helper: convert 20-byte digest to hex string
static std::string toHex(const uint8_t* data, size_t len) {
    std::ostringstream oss;
    for (size_t i = 0; i < len; ++i) {
        oss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(data[i]);
    }
    return oss.str();
}

// ===========================================================================
// Tests: SHA-1 Cryptographic Correctness (FIPS 180-1 Test Vectors)
// ===========================================================================

TEST(CCcamCryptoTest, Sha1EmptyString) {
    uint8_t digest[20];
    CCcamClient::sha1(reinterpret_cast<const uint8_t*>(""), 0, digest);

    // Known SHA-1 of "": da39a3ee5e6b4b0d3255bfef95601890afd80709
    EXPECT_EQ(toHex(digest, 20), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
}

TEST(CCcamCryptoTest, Sha1StandardAbcVector) {
    uint8_t digest[20];
    const char* input = "abc";
    CCcamClient::sha1(reinterpret_cast<const uint8_t*>(input), std::strlen(input), digest);

    // Known SHA-1 of "abc": a9993e364706816aba3e25717850c26c9cd0d89d
    EXPECT_EQ(toHex(digest, 20), "a9993e364706816aba3e25717850c26c9cd0d89d");
}

TEST(CCcamCryptoTest, Sha1LongerStringVector) {
    uint8_t digest[20];
    const char* input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
    CCcamClient::sha1(reinterpret_cast<const uint8_t*>(input), std::strlen(input), digest);

    // Known SHA-1 of 56-byte string: 84983e441c3bd26ebaae4aa1f95129e5e54670f1
    EXPECT_EQ(toHex(digest, 20), "84983e441c3bd26ebaae4aa1f95129e5e54670f1");
}

// ===========================================================================
// Tests: CCcam Stream Cipher (cc_crypt) & ccXor
// ===========================================================================

TEST(CCcamCryptoTest, CcCryptEncryptDecryptSymmetry) {
    const uint8_t keyData[] = { 'K', 'e', 'y', '1', '2', '3' };
    const uint8_t plaintext[] = { 'P', 'l', 'a', 'i', 'n', 't', 'e', 'x', 't' };
    size_t len = sizeof(plaintext);

    CcCryptBlock encBlock, decBlock;
    CCcamClient::ccInitCrypt(&encBlock, keyData, sizeof(keyData));
    CCcamClient::ccInitCrypt(&decBlock, keyData, sizeof(keyData));

    std::vector<uint8_t> buffer(plaintext, plaintext + len);

    // Encrypt
    CCcamClient::ccCrypt(&encBlock, buffer.data(), len, CcCryptMode::Encrypt);
    EXPECT_NE(std::memcmp(plaintext, buffer.data(), len), 0);

    // Decrypt
    CCcamClient::ccCrypt(&decBlock, buffer.data(), len, CcCryptMode::Decrypt);
    EXPECT_EQ(std::memcmp(plaintext, buffer.data(), len), 0);
}

TEST(CCcamCryptoTest, CcXorTransformation) {
    uint8_t seed[16] = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
    };
    uint8_t copy[16];
    std::memcpy(copy, seed, 16);

    CCcamClient::ccXor(seed);

    // ccXor must modify both lower and upper halves
    EXPECT_NE(std::memcmp(seed, copy, 16), 0);
    // Verification: buf[8+i] = i * buf[i]
    for (uint8_t i = 0; i < 8; ++i) {
        EXPECT_EQ(seed[8 + i], static_cast<uint8_t>(i * copy[i]));
    }
}

TEST(CCcamCryptoTest, CcCwCryptRoundtrip) {
    uint8_t originalCw[16] = {
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
        0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00
    };
    uint8_t cw[16];
    std::memcpy(cw, originalCw, 16);

    uint64_t nodeId = 0x0102030405060708ULL;
    uint32_t cardId = 0x12345678U;

    // First call encodes CW
    CCcamClient::ccCwCrypt(cw, nodeId, cardId);
    EXPECT_NE(std::memcmp(originalCw, cw, 16), 0);

    // Second call decodes CW back to original
    CCcamClient::ccCwCrypt(cw, nodeId, cardId);
    EXPECT_EQ(std::memcmp(originalCw, cw, 16), 0);
}

// ===========================================================================
// Tests: CCcamConfig & Client Lifecycle
// ===========================================================================

TEST(CCcamClientLifecycleTest, DefaultConfigValues) {
    CCcamConfig cfg;
    EXPECT_EQ(cfg.host, "192.168.1.100");
    EXPECT_EQ(cfg.port, 12000); // Standard CCcam port
    EXPECT_EQ(cfg.user, "android_tv");
    EXPECT_EQ(cfg.password, "android_tv");
    EXPECT_EQ(cfg.caid, 0x1810);
    EXPECT_GT(cfg.connectTimeoutSec, 0);
}

TEST(CCcamClientLifecycleTest, InitialStateIsNotConnected) {
    CCcamConfig cfg;
    CCcamCallbacks cbs;
    CCcamClient client(cfg, cbs);

    EXPECT_FALSE(client.isConnected());
}

TEST(CCcamClientLifecycleTest, SendEcmFailsWhenDisconnected) {
    CCcamConfig cfg;
    CCcamCallbacks cbs;
    CCcamClient client(cfg, cbs);

    uint8_t dummyEcm[16] = { 0x80, 0x70, 0x0D, 0x00 };
    bool result = client.sendEcm(0x7696, 0x1810, 0x004106, dummyEcm, sizeof(dummyEcm));
    EXPECT_FALSE(result);
}

TEST(CCcamClientLifecycleTest, SendEcmRejectsZeroLength) {
    CCcamConfig cfg;
    CCcamCallbacks cbs;
    CCcamClient client(cfg, cbs);

    bool result = client.sendEcm(0x7696, 0x1810, 0x004106, nullptr, 0);
    EXPECT_FALSE(result);
}

TEST(CCcamClientLifecycleTest, StartAndStopGraceful) {
    CCcamConfig cfg;
    cfg.host = "127.0.0.1";
    cfg.port = 59995; // Unbound port
    cfg.connectTimeoutSec = 1;
    cfg.reconnectIntervalMs = 500;

    bool stateChanged = false;
    CCcamCallbacks cbs;
    cbs.onConnectionChanged = [&](bool conn) {
        if (!conn) stateChanged = true;
    };

    CCcamClient client(cfg, cbs);
    EXPECT_TRUE(client.start());
    EXPECT_TRUE(client.start()); // Idempotent check

    client.stop();
    EXPECT_FALSE(client.isConnected());
}

TEST(CCcamClientLifecycleTest, TestConnectionFailsGracefullyOnInvalidTarget) {
    std::string error;
    bool ok = CCcamClient::testConnection("127.0.0.1", 59994, "user", "pass", 500, error);
    EXPECT_FALSE(ok);
    EXPECT_FALSE(error.empty());
}

