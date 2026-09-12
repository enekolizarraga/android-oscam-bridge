// tests/NewcamdClientTest.cpp
//
// Unit tests for NewcamdClient and its self-contained 3DES crypto engine.
// Validates DES/3DES encryption-decryption roundtrips, key schedule, hex parsing,
// and client lifecycle without requiring a live Newcamd cardserver.

#include <gtest/gtest.h>
#include "NewcamdClient.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <string>
#include <vector>

using namespace oscam::newcamd;

// ===========================================================================
// Tests: DES Key Hex Parsing
// ===========================================================================

TEST(NewcamdClientKeyTest, ParsesStandard28HexChars) {
    std::string hexKey = "0102030405060708091011121314";
    auto parsed = NewcamdClient::parseDesKeyHex(hexKey);

    ASSERT_EQ(parsed.size(), 14u);
    std::vector<uint8_t> expected = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x10, 0x11, 0x12, 0x13, 0x14
    };
    EXPECT_EQ(parsed, expected);
}

TEST(NewcamdClientKeyTest, ParsesWithColonsAndSpaces) {
    std::string colonSeparated = "01:02:03:04:05:06:07:08:09:10:11:12:13:14";
    auto parsedColon = NewcamdClient::parseDesKeyHex(colonSeparated);
    ASSERT_EQ(parsedColon.size(), 14u);

    std::string spaceSeparated = "01 02 03 04 05 06 07 08 09 10 11 12 13 14";
    auto parsedSpace = NewcamdClient::parseDesKeyHex(spaceSeparated);
    ASSERT_EQ(parsedSpace.size(), 14u);

    EXPECT_EQ(parsedColon, parsedSpace);
    EXPECT_EQ(parsedColon[0], 0x01);
    EXPECT_EQ(parsedColon[13], 0x14);
}

TEST(NewcamdClientKeyTest, ParsesCaseInsensitive) {
    std::string mixedCase = "0a0b0C0D0e0F1011121314151617";
    auto parsed = NewcamdClient::parseDesKeyHex(mixedCase);

    ASSERT_EQ(parsed.size(), 14u);
    EXPECT_EQ(parsed[0], 0x0A);
    EXPECT_EQ(parsed[1], 0x0B);
    EXPECT_EQ(parsed[2], 0x0C);
    EXPECT_EQ(parsed[3], 0x0D);
    EXPECT_EQ(parsed[4], 0x0E);
    EXPECT_EQ(parsed[5], 0x0F);
}

TEST(NewcamdClientKeyTest, PadsShortKeyWithZerosTo14Bytes) {
    std::string shortKey = "010203";
    auto parsed = NewcamdClient::parseDesKeyHex(shortKey);

    ASSERT_EQ(parsed.size(), 14u);
    EXPECT_EQ(parsed[0], 0x01);
    EXPECT_EQ(parsed[1], 0x02);
    EXPECT_EQ(parsed[2], 0x03);
    for (size_t i = 3; i < 14; ++i) {
        EXPECT_EQ(parsed[i], 0x00);
    }
}

TEST(NewcamdClientKeyTest, HandlesEmptyString) {
    auto parsed = NewcamdClient::parseDesKeyHex("");
    ASSERT_EQ(parsed.size(), 14u);
    for (size_t i = 0; i < 14; ++i) {
        EXPECT_EQ(parsed[i], 0x00);
    }
}

// ===========================================================================
// Tests: Triple-DES EDE2 Crypto Roundtrip
// ===========================================================================

TEST(NewcamdCryptoTest, EncryptDecryptSymmetry) {
    // 16-byte key (two 8-byte DES keys: K1, K2)
    uint8_t key16[16] = {
        0x13, 0x34, 0x57, 0x79, 0x9B, 0xBC, 0xDF, 0xF1,
        0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF
    };

    uint8_t plaintext[8] = { 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF };
    uint8_t ciphertext[8] = { 0 };
    uint8_t decrypted[8] = { 0 };

    // Encrypt
    NewcamdClient::des3Crypt(plaintext, ciphertext, key16, false);

    // Ciphertext must differ from plaintext
    EXPECT_NE(std::memcmp(plaintext, ciphertext, 8), 0);

    // Decrypt
    NewcamdClient::des3Crypt(ciphertext, decrypted, key16, true);

    // Decrypted must match original plaintext
    EXPECT_EQ(std::memcmp(plaintext, decrypted, 8), 0);
}

TEST(NewcamdCryptoTest, MultipleBlocksRoundtrip) {
    uint8_t key16[16] = {
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
    };

    // Test multiple distinct blocks (zeros, 0xFF, sequential, random pattern)
    std::vector<std::array<uint8_t, 8>> testBlocks = {
        { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
        { 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF },
        { 0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0 },
        { 0x80, 0x70, 0x01, 0x00, 0x00, 0x00, 0x18, 0x10 } // Simulated ECM header
    };

    for (const auto& block : testBlocks) {
        uint8_t cipher[8] = {0};
        uint8_t plain[8] = {0};

        NewcamdClient::des3Crypt(block.data(), cipher, key16, false);
        NewcamdClient::des3Crypt(cipher, plain, key16, true);

        EXPECT_EQ(std::memcmp(block.data(), plain, 8), 0);
    }
}

TEST(NewcamdCryptoTest, DifferentKeyYieldsDifferentCiphertext) {
    uint8_t keyA[16] = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10 };
    uint8_t keyB[16] = { 0xFE, 0xDC, 0xBA, 0x98, 0x76, 0x54, 0x32, 0x10, 0x0F, 0x1E, 0x2D, 0x3C, 0x4B, 0x5A, 0x69, 0x78 };

    uint8_t plaintext[8] = { 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x11, 0x22 };
    uint8_t cipherA[8] = {0};
    uint8_t cipherB[8] = {0};

    NewcamdClient::des3Crypt(plaintext, cipherA, keyA, false);
    NewcamdClient::des3Crypt(plaintext, cipherB, keyB, false);

    EXPECT_NE(std::memcmp(cipherA, cipherB, 8), 0);
}

// ===========================================================================
// Tests: NewcamdConfig & Client Lifecycle
// ===========================================================================

TEST(NewcamdClientLifecycleTest, DefaultConfigValues) {
    NewcamdConfig cfg;
    EXPECT_EQ(cfg.host, "192.168.1.100");
    EXPECT_EQ(cfg.port, 10000);
    EXPECT_EQ(cfg.user, "android_tv");
    EXPECT_EQ(cfg.password, "android_tv");
    EXPECT_EQ(cfg.desKey.size(), 14u);
    EXPECT_EQ(cfg.caid, 0x1810);
    EXPECT_GT(cfg.connectTimeoutSec, 0);
}

TEST(NewcamdClientLifecycleTest, InitialStateIsNotConnected) {
    NewcamdConfig cfg;
    NewcamdCallbacks cbs;
    NewcamdClient client(cfg, cbs);

    EXPECT_FALSE(client.isConnected());
}

TEST(NewcamdClientLifecycleTest, SendEcmFailsWhenDisconnected) {
    NewcamdConfig cfg;
    NewcamdCallbacks cbs;
    NewcamdClient client(cfg, cbs);

    uint8_t dummyEcm[16] = { 0x80, 0x70, 0x0D, 0x00 };
    bool result = client.sendEcm(0x7696, 0x1810, 0x004106, dummyEcm, sizeof(dummyEcm));
    EXPECT_FALSE(result);
}

TEST(NewcamdClientLifecycleTest, SendEcmRejectsZeroLength) {
    NewcamdConfig cfg;
    NewcamdCallbacks cbs;
    NewcamdClient client(cfg, cbs);

    bool result = client.sendEcm(0x7696, 0x1810, 0x004106, nullptr, 0);
    EXPECT_FALSE(result);
}

TEST(NewcamdClientLifecycleTest, StartAndStopGraceful) {
    NewcamdConfig cfg;
    cfg.host = "127.0.0.1";
    cfg.port = 59998; // Unbound port
    cfg.connectTimeoutSec = 1;
    cfg.reconnectIntervalMs = 500;

    bool stateNotificationReceived = false;
    NewcamdCallbacks cbs;
    cbs.onConnectionChanged = [&](bool conn) {
        if (!conn) {
            stateNotificationReceived = true;
        }
    };

    NewcamdClient client(cfg, cbs);
    EXPECT_TRUE(client.start());
    // Calling start again should be idempotent
    EXPECT_TRUE(client.start());

    // Stop client cleanly
    client.stop();
    EXPECT_FALSE(client.isConnected());
}

TEST(NewcamdClientLifecycleTest, TestConnectionFailsGracefullyOnInvalidTarget) {
    std::string error;
    std::vector<uint8_t> key = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 };

    // Connect to closed local port with 500ms timeout
    bool ok = NewcamdClient::testConnection("127.0.0.1", 59997, "user", "pass", key, 500, error);
    EXPECT_FALSE(ok);
    EXPECT_FALSE(error.empty());
}
