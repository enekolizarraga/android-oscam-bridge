// tests/Cs378xClientTest.cpp
//
// Unit tests for Cs378xClient (Camd35 TCP) and its self-contained AES-128 / MD5 crypto engine.
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#include <gtest/gtest.h>
#include "Cs378xClient.h"

#include <array>
#include <cstring>
#include <iomanip>
#include <sstream>

using namespace oscam::cs378x;

static std::string hexStr(const uint8_t* data, size_t len) {
    std::ostringstream ss;
    for (size_t i = 0; i < len; ++i) {
        ss << std::hex << std::setw(2) << std::setfill('0') << static_cast<int>(data[i]);
    }
    return ss.str();
}

TEST(Cs378xCryptoTest, Md5EmptyString) {
    uint8_t digest[16];
    Cs378xClient::md5(nullptr, 0, digest);
    EXPECT_EQ(hexStr(digest, 16), "d41d8cd98f00b204e9800998ecf8427e");
}

TEST(Cs378xCryptoTest, Md5KnownString) {
    std::string text = "password";
    uint8_t digest[16];
    Cs378xClient::md5(reinterpret_cast<const uint8_t*>(text.data()), text.size(), digest);
    EXPECT_EQ(hexStr(digest, 16), "5f4dcc3b5aa765d61d8327deb882cf99");
}

TEST(Cs378xCryptoTest, Aes128Roundtrip) {
    uint8_t key16[16] = {
        0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae, 0xd2, 0xa6,
        0xab, 0xf7, 0x15, 0x88, 0x09, 0xcf, 0x4f, 0x3c
    };
    uint8_t plaintext[16] = {
        0x6b, 0xc1, 0xbe, 0xe2, 0x2e, 0x40, 0x9f, 0x96,
        0xe9, 0x3d, 0x7e, 0x11, 0x73, 0x93, 0x17, 0x2a
    };

    Aes128Key encKey, decKey;
    Cs378xClient::aes128KeySetupEnc(&encKey, key16);
    Cs378xClient::aes128KeySetupDec(&decKey, key16);

    uint8_t ciphertext[16];
    Cs378xClient::aes128EncryptBlock(&encKey, plaintext, ciphertext);

    // NIST standard ciphertext for these test vectors: 3ad77bb40d7a3660a89ecaf32466ef97
    EXPECT_EQ(hexStr(ciphertext, 16), "3ad77bb40d7a3660a89ecaf32466ef97");

    uint8_t decrypted[16];
    Cs378xClient::aes128DecryptBlock(&decKey, ciphertext, decrypted);
    EXPECT_EQ(std::memcmp(plaintext, decrypted, 16), 0);
}

TEST(Cs378xClientTest, LifecycleStartStop) {
    Cs378xConfig cfg;
    cfg.host = "127.0.0.1";
    cfg.port = 13000;
    cfg.user = "test_user";
    cfg.password = "test_pass";

    Cs378xClient client(cfg, {});
    EXPECT_FALSE(client.isConnected());
    EXPECT_EQ(client.getProtocolType(), oscam::ProtocolType::CS378X);
}

