// bridge/Cs378xClient.cpp
//
// Implementation of native OSCam Camd35 / Cs378x client over TCP.
// Includes self-contained MD5 and AES-128 cryptographic engines (no external OpenSSL).
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#include "include/Cs378xClient.h"
#include "include/BridgeLogger.h"

#include <algorithm>
#include <chrono>
#include <cstring>

#ifdef _WIN32
#  include <winsock2.h>
#  include <ws2tcpip.h>
   using SockLen = int;
#  define INVALID_SOCKET_FD INVALID_SOCKET
#  define CLOSE_SOCKET(s)   ::closesocket(s)
#  define SOCK_ERRNO        WSAGetLastError()
#else
#  include <arpa/inet.h>
#  include <fcntl.h>
#  include <netdb.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <sys/select.h>
#  include <sys/socket.h>
#  include <unistd.h>
   using SockLen = socklen_t;
#  define INVALID_SOCKET_FD (-1)
#  define CLOSE_SOCKET(s)   ::close(s)
#  define SOCK_ERRNO        errno
#endif

namespace oscam::cs378x {

// ---------------------------------------------------------------------------
// Self-contained RFC 1321 MD5 Implementation
// ---------------------------------------------------------------------------
namespace {

struct Md5Context {
    uint32_t state[4];
    uint32_t count[2];
    uint8_t  buffer[64];
};

#define F(x, y, z) (((x) & (y)) | ((~x) & (z)))
#define G(x, y, z) (((x) & (z)) | ((y) & (~z)))
#define H(x, y, z) ((x) ^ (y) ^ (z))
#define I(x, y, z) ((y) ^ ((x) | (~z)))

#define ROTATE_LEFT(x, n) (((x) << (n)) | ((x) >> (32 - (n))))

#define FF(a, b, c, d, x, s, ac) { \
    (a) += F((b), (c), (d)) + (x) + (uint32_t)(ac); \
    (a) = ROTATE_LEFT((a), (s)); \
    (a) += (b); \
}
#define GG(a, b, c, d, x, s, ac) { \
    (a) += G((b), (c), (d)) + (x) + (uint32_t)(ac); \
    (a) = ROTATE_LEFT((a), (s)); \
    (a) += (b); \
}
#define HH(a, b, c, d, x, s, ac) { \
    (a) += H((b), (c), (d)) + (x) + (uint32_t)(ac); \
    (a) = ROTATE_LEFT((a), (s)); \
    (a) += (b); \
}
#define II(a, b, c, d, x, s, ac) { \
    (a) += I((b), (c), (d)) + (x) + (uint32_t)(ac); \
    (a) = ROTATE_LEFT((a), (s)); \
    (a) += (b); \
}

static void md5Transform(uint32_t state[4], const uint8_t block[64]) {
    uint32_t a = state[0], b = state[1], c = state[2], d = state[3], x[16];
    for (int i = 0, j = 0; i < 16; ++i, j += 4) {
        x[i] = (static_cast<uint32_t>(block[j])) |
               (static_cast<uint32_t>(block[j + 1]) << 8) |
               (static_cast<uint32_t>(block[j + 2]) << 16) |
               (static_cast<uint32_t>(block[j + 3]) << 24);
    }

    // Round 1
    FF(a, b, c, d, x[ 0],  7, 0xd76aa478); FF(d, a, b, c, x[ 1], 12, 0xe8c7b756);
    FF(c, d, a, b, x[ 2], 17, 0x242070db); FF(b, c, d, a, x[ 3], 22, 0xc1bdceee);
    FF(a, b, c, d, x[ 4],  7, 0xf57c0faf); FF(d, a, b, c, x[ 5], 12, 0x4787c62a);
    FF(c, d, a, b, x[ 6], 17, 0xa8304613); FF(b, c, d, a, x[ 7], 22, 0xfd469501);
    FF(a, b, c, d, x[ 8],  7, 0x698098d8); FF(d, a, b, c, x[ 9], 12, 0x8b44f7af);
    FF(c, d, a, b, x[10], 17, 0xffff5bb1); FF(b, c, d, a, x[11], 22, 0x895cd7be);
    FF(a, b, c, d, x[12],  7, 0x6b901122); FF(d, a, b, c, x[13], 12, 0xfd987193);
    FF(c, d, a, b, x[14], 17, 0xa679438e); FF(b, c, d, a, x[15], 22, 0x49b40821);

    // Round 2
    GG(a, b, c, d, x[ 1],  5, 0xf61e2562); GG(d, a, b, c, x[ 6],  9, 0xc040b340);
    GG(c, d, a, b, x[11], 14, 0x265e5a51); GG(b, c, d, a, x[ 0], 20, 0xe9b6c7aa);
    GG(a, b, c, d, x[ 5],  5, 0xd62f105d); GG(d, a, b, c, x[10],  9, 0x02441453);
    GG(c, d, a, b, x[15], 14, 0xd8a1e681); GG(b, c, d, a, x[ 4], 20, 0xe7d3fbc8);
    GG(a, b, c, d, x[ 9],  5, 0x21e1cde6); GG(d, a, b, c, x[14],  9, 0xc33707d6);
    GG(c, d, a, b, x[ 3], 14, 0xf4d50d87); GG(b, c, d, a, x[ 8], 20, 0x455a14ed);
    GG(a, b, c, d, x[13],  5, 0xa9e3e905); GG(d, a, b, c, x[ 2],  9, 0xfcefa3f8);
    GG(c, d, a, b, x[ 7], 14, 0x676f02d9); GG(b, c, d, a, x[12], 20, 0x8d2a4c8a);

    // Round 3
    HH(a, b, c, d, x[ 5],  4, 0xfffa3942); HH(d, a, b, c, x[ 8], 11, 0x8771f681);
    HH(c, d, a, b, x[11], 16, 0x6d9d6122); HH(b, c, d, a, x[14], 23, 0xfde5380c);
    HH(a, b, c, d, x[ 1],  4, 0xa4beea44); HH(d, a, b, c, x[ 4], 11, 0x4bdecfa9);
    HH(c, d, a, b, x[ 7], 16, 0xf6bb4b60); HH(b, c, d, a, x[10], 23, 0xbebfbc70);
    HH(a, b, c, d, x[13],  4, 0x289b7ec6); HH(d, a, b, c, x[ 0], 11, 0xeaa127fa);
    HH(c, d, a, b, x[ 3], 16, 0xd4ef3085); HH(b, c, d, a, x[ 6], 23, 0x04881d05);
    HH(a, b, c, d, x[ 9],  4, 0xd9d4d039); HH(d, a, b, c, x[12], 11, 0xe6db99e5);
    HH(c, d, a, b, x[15], 16, 0x1fa27cf8); HH(b, c, d, a, x[ 2], 23, 0xc4ac5665);

    // Round 4
    II(a, b, c, d, x[ 0],  6, 0xf4292244); II(d, a, b, c, x[ 7], 10, 0x432aff97);
    II(c, d, a, b, x[14], 15, 0xab9423a7); II(b, c, d, a, x[ 5], 21, 0xfc93a039);
    II(a, b, c, d, x[12],  6, 0x655b59c3); II(d, a, b, c, x[ 3], 10, 0x8f0ccc92);
    II(c, d, a, b, x[10], 15, 0xffeff47d); II(b, c, d, a, x[ 1], 21, 0x85845dd1);
    II(a, b, c, d, x[ 8],  6, 0x6fa87e4f); II(d, a, b, c, x[15], 10, 0xfe2ce6e0);
    II(c, d, a, b, x[ 6], 15, 0xa3014314); II(b, c, d, a, x[13], 21, 0x4e0811a1);
    II(a, b, c, d, x[ 4],  6, 0xf7537e82); II(d, a, b, c, x[11], 10, 0xbd3af235);
    II(c, d, a, b, x[ 2], 15, 0x2ad7d2bb); II(b, c, d, a, x[ 9], 21, 0xeb86d391);

    state[0] += a; state[1] += b; state[2] += c; state[3] += d;
}

static void md5Init(Md5Context* ctx) {
    ctx->count[0] = ctx->count[1] = 0;
    ctx->state[0] = 0x67452301;
    ctx->state[1] = 0xefcdab89;
    ctx->state[2] = 0x98badcfe;
    ctx->state[3] = 0x10325476;
}

static void md5Update(Md5Context* ctx, const uint8_t* input, size_t inputLen) {
    size_t i = 0, index = (ctx->count[0] >> 3) & 0x3F;
    if ((ctx->count[0] += (static_cast<uint32_t>(inputLen) << 3)) < (static_cast<uint32_t>(inputLen) << 3)) {
        ctx->count[1]++;
    }
    ctx->count[1] += (static_cast<uint32_t>(inputLen) >> 29);
    size_t partLen = 64 - index;

    if (inputLen >= partLen) {
        std::memcpy(&ctx->buffer[index], input, partLen);
        md5Transform(ctx->state, ctx->buffer);
        for (i = partLen; i + 63 < inputLen; i += 64) {
            md5Transform(ctx->state, &input[i]);
        }
        index = 0;
    }
    std::memcpy(&ctx->buffer[index], &input[i], inputLen - i);
}

static void md5Final(Md5Context* ctx, uint8_t digest[16]) {
    static const uint8_t PADDING[64] = { 0x80 };
    uint8_t bits[8];
    for (int i = 0; i < 4; ++i) {
        bits[i] = static_cast<uint8_t>((ctx->count[0] >> (i * 8)) & 0xFF);
        bits[i + 4] = static_cast<uint8_t>((ctx->count[1] >> (i * 8)) & 0xFF);
    }
    size_t index = (ctx->count[0] >> 3) & 0x3F;
    size_t padLen = (index < 56) ? (56 - index) : (120 - index);
    md5Update(ctx, PADDING, padLen);
    md5Update(ctx, bits, 8);

    for (int i = 0; i < 4; ++i) {
        digest[i * 4]     = static_cast<uint8_t>(ctx->state[i] & 0xFF);
        digest[i * 4 + 1] = static_cast<uint8_t>((ctx->state[i] >> 8) & 0xFF);
        digest[i * 4 + 2] = static_cast<uint8_t>((ctx->state[i] >> 16) & 0xFF);
        digest[i * 4 + 3] = static_cast<uint8_t>((ctx->state[i] >> 24) & 0xFF);
    }
}

} // anonymous namespace

void Cs378xClient::md5(const uint8_t* data, size_t length, uint8_t outDigest[16]) {
    Md5Context ctx;
    md5Init(&ctx);
    md5Update(&ctx, data, length);
    md5Final(&ctx, outDigest);
}

// ---------------------------------------------------------------------------
// Self-contained AES-128 Implementation
// ---------------------------------------------------------------------------
namespace {

static const uint8_t sBox[256] = {
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
    0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
    0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
    0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
    0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
    0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
    0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
    0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
    0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
    0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
    0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
    0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
    0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
    0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
    0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
    0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16
};

static const uint8_t rsBox[256] = {
    0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38, 0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
    0x7c, 0xe3, 0x39, 0x82, 0x9b, 0x2f, 0xff, 0x87, 0x34, 0x8e, 0x43, 0x44, 0xc4, 0xde, 0xe9, 0xcb,
    0x54, 0x7b, 0x94, 0x32, 0xa6, 0xc2, 0x23, 0x3d, 0xee, 0x4c, 0x95, 0x0b, 0x42, 0xfa, 0xc3, 0x4e,
    0x08, 0x2e, 0xa1, 0x66, 0x28, 0xd9, 0x24, 0xb2, 0x76, 0x5b, 0xa2, 0x49, 0x6d, 0x8b, 0xd1, 0x25,
    0x72, 0xf8, 0xf6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xd4, 0xa4, 0x5c, 0xcc, 0x5d, 0x65, 0xb6, 0x92,
    0x6c, 0x70, 0x48, 0x50, 0xfd, 0xed, 0xb9, 0xda, 0x5e, 0x15, 0x46, 0x57, 0xa7, 0x8d, 0x9d, 0x84,
    0x90, 0xd8, 0xab, 0x00, 0x8c, 0xbc, 0xd3, 0x0a, 0xf7, 0xe4, 0x58, 0x05, 0xb8, 0xb3, 0x45, 0x06,
    0xd0, 0x2c, 0x1e, 0x8f, 0xca, 0x3f, 0x0f, 0x02, 0xc1, 0xaf, 0xbd, 0x03, 0x01, 0x13, 0x8a, 0x6b,
    0x3a, 0x91, 0x11, 0x41, 0x4f, 0x67, 0xdc, 0xea, 0x97, 0xf2, 0xcf, 0xce, 0xf0, 0xb4, 0xe6, 0x73,
    0x96, 0xac, 0x74, 0x22, 0xe7, 0xad, 0x35, 0x85, 0xe2, 0xf9, 0x37, 0xe8, 0x1c, 0x75, 0xdf, 0x6e,
    0x47, 0xf1, 0x1a, 0x71, 0x1d, 0x29, 0xc5, 0x89, 0x6f, 0xb7, 0x62, 0x0e, 0xaa, 0x18, 0xbe, 0x1b,
    0xfc, 0x56, 0x3e, 0x4b, 0xc6, 0xd2, 0x79, 0x20, 0x9a, 0xdb, 0xc0, 0xfe, 0x78, 0xcd, 0x5a, 0xf4,
    0x1f, 0xdd, 0xa8, 0x33, 0x88, 0x07, 0xc7, 0x31, 0xb1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xec, 0x5f,
    0x60, 0x51, 0x7f, 0xa9, 0x19, 0xb5, 0x4a, 0x0d, 0x2d, 0xe5, 0x7a, 0x9f, 0x93, 0xc9, 0x9c, 0xef,
    0xa0, 0xe0, 0x3b, 0x4d, 0xae, 0x2a, 0xf5, 0xb0, 0xc8, 0xeb, 0xbb, 0x3c, 0x83, 0x53, 0x99, 0x61,
    0x17, 0x2b, 0x04, 0x7e, 0xba, 0x77, 0xd6, 0x26, 0xe1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0c, 0x7d
};

static const uint32_t rCon[11] = {
    0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36
};

static uint8_t xtime(uint8_t x) {
    return static_cast<uint8_t>((x << 1) ^ (((x >> 7) & 1) * 0x1b));
}

static uint8_t multiply(uint8_t x, uint8_t y) {
    return static_cast<uint8_t>(
        ((y & 1) * x) ^
        ((y >> 1 & 1) * xtime(x)) ^
        ((y >> 2 & 1) * xtime(xtime(x))) ^
        ((y >> 3 & 1) * xtime(xtime(xtime(x)))) ^
        ((y >> 4 & 1) * xtime(xtime(xtime(xtime(x)))))
    );
}

} // anonymous namespace

void Cs378xClient::aes128KeySetupEnc(Aes128Key* key, const uint8_t key16[16]) {
    for (int i = 0; i < 4; ++i) {
        key->roundKeys[i] = (static_cast<uint32_t>(key16[4 * i]) << 24) |
                            (static_cast<uint32_t>(key16[4 * i + 1]) << 16) |
                            (static_cast<uint32_t>(key16[4 * i + 2]) << 8) |
                            (static_cast<uint32_t>(key16[4 * i + 3]));
    }
    for (int i = 4; i < 44; ++i) {
        uint32_t temp = key->roundKeys[i - 1];
        if (i % 4 == 0) {
            temp = ((temp << 8) | (temp >> 24));
            temp = (static_cast<uint32_t>(sBox[(temp >> 24) & 0xFF]) << 24) |
                   (static_cast<uint32_t>(sBox[(temp >> 16) & 0xFF]) << 16) |
                   (static_cast<uint32_t>(sBox[(temp >> 8) & 0xFF]) << 8) |
                   (static_cast<uint32_t>(sBox[temp & 0xFF]));
            temp ^= (rCon[i / 4] << 24);
        }
        key->roundKeys[i] = key->roundKeys[i - 4] ^ temp;
    }
}

void Cs378xClient::aes128KeySetupDec(Aes128Key* key, const uint8_t key16[16]) {
    aes128KeySetupEnc(key, key16);
}

void Cs378xClient::aes128EncryptBlock(const Aes128Key* key, const uint8_t in[16], uint8_t out[16]) {
    uint8_t state[4][4];
    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 4; ++c) {
            state[r][c] = in[r + 4 * c];
        }
    }

    // AddRoundKey 0
    for (int c = 0; c < 4; ++c) {
        uint32_t rk = key->roundKeys[c];
        state[0][c] ^= (rk >> 24) & 0xFF;
        state[1][c] ^= (rk >> 16) & 0xFF;
        state[2][c] ^= (rk >> 8) & 0xFF;
        state[3][c] ^= rk & 0xFF;
    }

    for (int round = 1; round <= 10; ++round) {
        // SubBytes
        for (int r = 0; r < 4; ++r) {
            for (int c = 0; c < 4; ++c) {
                state[r][c] = sBox[state[r][c]];
            }
        }
        // ShiftRows
        uint8_t t = state[1][0];
        state[1][0] = state[1][1]; state[1][1] = state[1][2]; state[1][2] = state[1][3]; state[1][3] = t;
        t = state[2][0]; uint8_t t2 = state[2][1];
        state[2][0] = state[2][2]; state[2][1] = state[2][3]; state[2][2] = t; state[2][3] = t2;
        t = state[3][3];
        state[3][3] = state[3][2]; state[3][2] = state[3][1]; state[3][1] = state[3][0]; state[3][0] = t;

        // MixColumns (rounds 1..9)
        if (round < 10) {
            for (int c = 0; c < 4; ++c) {
                uint8_t a = state[0][c], b = state[1][c], d = state[2][c], e = state[3][c];
                state[0][c] = multiply(a, 2) ^ multiply(b, 3) ^ d ^ e;
                state[1][c] = a ^ multiply(b, 2) ^ multiply(d, 3) ^ e;
                state[2][c] = a ^ b ^ multiply(d, 2) ^ multiply(e, 3);
                state[3][c] = multiply(a, 3) ^ b ^ d ^ multiply(e, 2);
            }
        }

        // AddRoundKey
        for (int c = 0; c < 4; ++c) {
            uint32_t rk = key->roundKeys[round * 4 + c];
            state[0][c] ^= (rk >> 24) & 0xFF;
            state[1][c] ^= (rk >> 16) & 0xFF;
            state[2][c] ^= (rk >> 8) & 0xFF;
            state[3][c] ^= rk & 0xFF;
        }
    }

    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 4; ++c) {
            out[r + 4 * c] = state[r][c];
        }
    }
}

void Cs378xClient::aes128DecryptBlock(const Aes128Key* key, const uint8_t in[16], uint8_t out[16]) {
    uint8_t state[4][4];
    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 4; ++c) {
            state[r][c] = in[r + 4 * c];
        }
    }

    // AddRoundKey 10
    for (int c = 0; c < 4; ++c) {
        uint32_t rk = key->roundKeys[40 + c];
        state[0][c] ^= (rk >> 24) & 0xFF;
        state[1][c] ^= (rk >> 16) & 0xFF;
        state[2][c] ^= (rk >> 8) & 0xFF;
        state[3][c] ^= rk & 0xFF;
    }

    for (int round = 9; round >= 0; --round) {
        // InvShiftRows
        uint8_t t = state[1][3];
        state[1][3] = state[1][2]; state[1][2] = state[1][1]; state[1][1] = state[1][0]; state[1][0] = t;
        t = state[2][2]; uint8_t t2 = state[2][3];
        state[2][2] = state[2][0]; state[2][3] = state[2][1]; state[2][0] = t; state[2][1] = t2;
        t = state[3][0];
        state[3][0] = state[3][1]; state[3][1] = state[3][2]; state[3][2] = state[3][3]; state[3][3] = t;

        // InvSubBytes
        for (int r = 0; r < 4; ++r) {
            for (int c = 0; c < 4; ++c) {
                state[r][c] = rsBox[state[r][c]];
            }
        }

        // AddRoundKey
        for (int c = 0; c < 4; ++c) {
            uint32_t rk = key->roundKeys[round * 4 + c];
            state[0][c] ^= (rk >> 24) & 0xFF;
            state[1][c] ^= (rk >> 16) & 0xFF;
            state[2][c] ^= (rk >> 8) & 0xFF;
            state[3][c] ^= rk & 0xFF;
        }

        // InvMixColumns
        if (round > 0) {
            for (int c = 0; c < 4; ++c) {
                uint8_t a = state[0][c], b = state[1][c], d = state[2][c], e = state[3][c];
                state[0][c] = multiply(a, 0x0e) ^ multiply(b, 0x0b) ^ multiply(d, 0x0d) ^ multiply(e, 0x09);
                state[1][c] = multiply(a, 0x09) ^ multiply(b, 0x0e) ^ multiply(d, 0x0b) ^ multiply(e, 0x0d);
                state[2][c] = multiply(a, 0x0d) ^ multiply(b, 0x09) ^ multiply(d, 0x0e) ^ multiply(e, 0x0b);
                state[3][c] = multiply(a, 0x0b) ^ multiply(b, 0x0d) ^ multiply(d, 0x09) ^ multiply(e, 0x0e);
            }
        }
    }

    for (int r = 0; r < 4; ++r) {
        for (int c = 0; c < 4; ++c) {
            out[r + 4 * c] = state[r][c];
        }
    }
}

// ---------------------------------------------------------------------------
// Cs378xClient Implementation
// ---------------------------------------------------------------------------

Cs378xClient::Cs378xClient(Cs378xConfig config, OscamClientCallbacks callbacks)
    : config_(std::move(config))
    , callbacks_(std::move(callbacks)) {
}

Cs378xClient::~Cs378xClient() {
    stop();
}

bool Cs378xClient::start() {
    if (running_.exchange(true)) return true;
    workerThread_ = std::thread(&Cs378xClient::workerLoop, this);
    BRIDGE_LOGI("Cs378xClient: Started background thread for %s:%u", config_.host.c_str(), config_.port);
    return true;
}

void Cs378xClient::stop() {
    if (!running_.exchange(false)) return;

    {
        std::lock_guard<std::mutex> lk(socketMutex_);
        if (activeSocketFd_ != INVALID_SOCKET_FD) {
            CLOSE_SOCKET(activeSocketFd_);
            activeSocketFd_ = INVALID_SOCKET_FD;
        }
    }

    if (workerThread_.joinable()) {
        workerThread_.join();
    }
    connected_ = false;
    BRIDGE_LOGI("Cs378xClient: Stopped cleanly.");
}

bool Cs378xClient::isConnected() const {
    return connected_.load();
}

bool Cs378xClient::sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId,
                           const uint8_t* ecmData, size_t length) {
    if (!connected_ || !ecmData || length == 0) return false;

    std::lock_guard<std::mutex> lk(socketMutex_);
    if (activeSocketFd_ == INVALID_SOCKET_FD) return false;

    return sendEncryptedPacket(activeSocketFd_, CMD_ECM_REQUEST, caid, providerId, serviceId, ecmData, length);
}

void Cs378xClient::workerLoop() {
    while (running_) {
        int socketFd = INVALID_SOCKET_FD;
        if (!connectAndAuthenticate(socketFd)) {
            connected_ = false;
            if (callbacks_.onConnectionChanged) callbacks_.onConnectionChanged(false);
            std::this_thread::sleep_for(std::chrono::milliseconds(config_.reconnectIntervalMs));
            continue;
        }

        {
            std::lock_guard<std::mutex> lk(socketMutex_);
            activeSocketFd_ = socketFd;
            connected_ = true;
        }

        if (callbacks_.onConnectionChanged) callbacks_.onConnectionChanged(true);
        BRIDGE_LOGI("Cs378xClient: Connected & authenticated with OSCam Cs378x server");

        auto lastKeepalive = std::chrono::steady_clock::now();

        while (running_ && connected_) {
            fd_set readFds;
            FD_ZERO(&readFds);
#if defined(_MSC_VER)
#  pragma warning(push)
#  pragma warning(disable: 4548)
#endif
            FD_SET(socketFd, &readFds);
#if defined(_MSC_VER)
#  pragma warning(pop)
#endif

            struct timeval tv{};
            tv.tv_sec = 1;
            tv.tv_usec = 0;

            int sel = ::select(socketFd + 1, &readFds, nullptr, nullptr, &tv);
            if (sel < 0) {
                BRIDGE_LOGE("Cs378xClient: select() failed (error %d)", SOCK_ERRNO);
                break;
            }

            if (sel > 0 && FD_ISSET(socketFd, &readFds)) {
                uint8_t cmd = 0;
                uint16_t caid = 0;
                uint32_t provider = 0;
                uint16_t serviceId = 0;
                std::vector<uint8_t> payload;

                if (!readEncryptedPacket(socketFd, cmd, caid, provider, serviceId, payload)) {
                    BRIDGE_LOGW("Cs378xClient: Failed to read frame or socket disconnected");
                    break;
                }

                if (cmd == CMD_CW_RESPONSE && payload.size() >= 16) {
                    // 16 bytes Control Word: 8 even + 8 odd
                    if (callbacks_.onControlWord) {
                        callbacks_.onControlWord(serviceId, 0, payload.data(), 8);
                        callbacks_.onControlWord(serviceId, 1, payload.data() + 8, 8);
                    }
                } else if (cmd == CMD_KEEPALIVE) {
                    BRIDGE_LOGD("Cs378xClient: Keepalive ACK received from OSCam");
                }
            }

            // Periodic keepalive every 15 seconds
            auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::seconds>(now - lastKeepalive).count() >= 15) {
                lastKeepalive = now;
                sendEncryptedPacket(socketFd, CMD_KEEPALIVE, 0, 0, 0, nullptr, 0);
            }
        }

        {
            std::lock_guard<std::mutex> lk(socketMutex_);
            if (activeSocketFd_ != INVALID_SOCKET_FD) {
                CLOSE_SOCKET(activeSocketFd_);
                activeSocketFd_ = INVALID_SOCKET_FD;
            }
            connected_ = false;
        }
        if (callbacks_.onConnectionChanged) callbacks_.onConnectionChanged(false);
    }
}

bool Cs378xClient::connectAndAuthenticate(int& socketFd) {
    struct addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    struct addrinfo* res = nullptr;
    std::string portStr = std::to_string(config_.port);
    if (::getaddrinfo(config_.host.c_str(), portStr.c_str(), &hints, &res) != 0 || !res) {
        if (callbacks_.onError) callbacks_.onError("Could not resolve host: " + config_.host);
        return false;
    }

    int fd = static_cast<int>(::socket(res->ai_family, res->ai_socktype, res->ai_protocol));
    if (fd < 0) {
        ::freeaddrinfo(res);
        return false;
    }

    // Set non-blocking for connect timeout
#ifdef _WIN32
    u_long nb = 1;
    ::ioctlsocket(fd, FIONBIO, &nb);
#else
    int flags = ::fcntl(fd, F_GETFL, 0);
    ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
#endif

    int ret = ::connect(fd, res->ai_addr, static_cast<SockLen>(res->ai_addrlen));
    ::freeaddrinfo(res);

    if (ret != 0) {
        fd_set wfds;
        FD_ZERO(&wfds);
#if defined(_MSC_VER)
#  pragma warning(push)
#  pragma warning(disable: 4548)
#endif
        FD_SET(fd, &wfds);
#if defined(_MSC_VER)
#  pragma warning(pop)
#endif
        struct timeval tv{};
        tv.tv_sec = config_.connectTimeoutSec;
        if (::select(fd + 1, nullptr, &wfds, nullptr, &tv) <= 0) {
            CLOSE_SOCKET(fd);
            return false;
        }
    }

    // Restore blocking
#ifdef _WIN32
    nb = 0;
    ::ioctlsocket(fd, FIONBIO, &nb);
#else
    ::fcntl(fd, F_SETFL, flags);
#endif

    // Derive sessionKey_ = MD5(password)
    md5(reinterpret_cast<const uint8_t*>(config_.password.data()), config_.password.size(), sessionKey_);
    aes128KeySetupEnc(&aesEncKey_, sessionKey_);
    aes128KeySetupDec(&aesDecKey_, sessionKey_);

    // Send CONNECT packet with username
    std::vector<uint8_t> userPayload(config_.user.begin(), config_.user.end());
    if (!sendEncryptedPacket(fd, CMD_CONNECT, config_.caid, 0, 0, userPayload.data(), userPayload.size())) {
        CLOSE_SOCKET(fd);
        return false;
    }

    // Read CONNECT_ACK
    uint8_t ackCmd = 0;
    uint16_t ackCaid = 0;
    uint32_t ackProv = 0;
    uint16_t ackSid = 0;
    std::vector<uint8_t> ackPayload;
    if (!readEncryptedPacket(fd, ackCmd, ackCaid, ackProv, ackSid, ackPayload) || (ackCmd != CMD_CONNECT_ACK && ackCmd != CMD_CARD_INFO)) {
        CLOSE_SOCKET(fd);
        return false;
    }

    socketFd = fd;
    return true;
}

bool Cs378xClient::sendEncryptedPacket(int socketFd, uint8_t cmd, uint16_t caid, uint32_t provider,
                                      uint16_t serviceId, const uint8_t* payload, size_t payloadLen) {
    size_t totalUnpadded = 20 + payloadLen;
    size_t paddedLen = ((totalUnpadded + 15) / 16) * 16;
    std::vector<uint8_t> raw(paddedLen, 0);

    uint16_t seq = sequenceNumber_++;
    raw[0] = cmd;
    raw[1] = static_cast<uint8_t>(payloadLen & 0xFF);
    raw[2] = static_cast<uint8_t>((payloadLen >> 8) & 0xFF);
    raw[3] = static_cast<uint8_t>((caid >> 8) & 0xFF);
    raw[4] = static_cast<uint8_t>(caid & 0xFF);
    raw[5] = static_cast<uint8_t>((provider >> 24) & 0xFF);
    raw[6] = static_cast<uint8_t>((provider >> 16) & 0xFF);
    raw[7] = static_cast<uint8_t>((provider >> 8) & 0xFF);
    raw[8] = static_cast<uint8_t>(provider & 0xFF);
    raw[9] = static_cast<uint8_t>((serviceId >> 8) & 0xFF);
    raw[10] = static_cast<uint8_t>(serviceId & 0xFF);
    raw[11] = static_cast<uint8_t>((seq >> 8) & 0xFF);
    raw[12] = static_cast<uint8_t>(seq & 0xFF);

    if (payload && payloadLen > 0) {
        std::memcpy(&raw[20], payload, payloadLen);
    }

    std::vector<uint8_t> enc(paddedLen, 0);
    for (size_t i = 0; i < paddedLen; i += 16) {
        aes128EncryptBlock(&aesEncKey_, &raw[i], &enc[i]);
    }

    return writeFull(socketFd, enc.data(), enc.size());
}

bool Cs378xClient::readEncryptedPacket(int socketFd, uint8_t& outCmd, uint16_t& outCaid,
                                      uint32_t& outProvider, uint16_t& outServiceId,
                                      std::vector<uint8_t>& outPayload) {
    uint8_t firstBlockEnc[16];
    if (!readFull(socketFd, firstBlockEnc, 16)) return false;

    uint8_t firstBlockDec[16];
    aes128DecryptBlock(&aesDecKey_, firstBlockEnc, firstBlockDec);

    outCmd = firstBlockDec[0];
    uint16_t payloadLen = static_cast<uint16_t>(firstBlockDec[1]) | (static_cast<uint16_t>(firstBlockDec[2]) << 8);
    outCaid = (static_cast<uint16_t>(firstBlockDec[3]) << 8) | firstBlockDec[4];
    outProvider = (static_cast<uint32_t>(firstBlockDec[5]) << 24) |
                  (static_cast<uint32_t>(firstBlockDec[6]) << 16) |
                  (static_cast<uint32_t>(firstBlockDec[7]) << 8) |
                  firstBlockDec[8];
    outServiceId = (static_cast<uint16_t>(firstBlockDec[9]) << 8) | firstBlockDec[10];

    size_t totalUnpadded = 20 + payloadLen;
    size_t totalPadded = ((totalUnpadded + 15) / 16) * 16;
    size_t remainingEnc = totalPadded - 16;

    std::vector<uint8_t> restEnc(remainingEnc);
    if (remainingEnc > 0) {
        if (!readFull(socketFd, restEnc.data(), remainingEnc)) return false;
    }

    std::vector<uint8_t> allDec(totalPadded);
    std::memcpy(allDec.data(), firstBlockDec, 16);

    for (size_t i = 0; i < remainingEnc; i += 16) {
        aes128DecryptBlock(&aesDecKey_, &restEnc[i], &allDec[16 + i]);
    }

    outPayload.clear();
    if (payloadLen > 0 && 20 + payloadLen <= totalPadded) {
        outPayload.assign(&allDec[20], &allDec[20 + payloadLen]);
    }
    return true;
}

bool Cs378xClient::readFull(int socketFd, uint8_t* buf, size_t count) {
    size_t total = 0;
    while (total < count) {
        int r = static_cast<int>(::recv(socketFd, reinterpret_cast<char*>(buf + total), static_cast<int>(count - total), 0));
        if (r <= 0) return false;
        total += r;
    }
    return true;
}

bool Cs378xClient::writeFull(int socketFd, const uint8_t* buf, size_t count) {
    size_t total = 0;
    while (total < count) {
        int w = static_cast<int>(::send(socketFd, reinterpret_cast<const char*>(buf + total), static_cast<int>(count - total), 0));
        if (w <= 0) return false;
        total += w;
    }
    return true;
}

bool Cs378xClient::testConnection(const std::string& host, uint16_t port,
                                  const std::string& user, const std::string& password,
                                  int timeoutMs, std::string& outError) {
    Cs378xConfig cfg;
    cfg.host = host;
    cfg.port = port;
    cfg.user = user;
    cfg.password = password;
    cfg.connectTimeoutSec = std::max(1, timeoutMs / 1000);

    Cs378xClient client(cfg, {});
    int sockFd = INVALID_SOCKET_FD;
    bool ok = client.connectAndAuthenticate(sockFd);
    if (ok) {
        CLOSE_SOCKET(sockFd);
        outError.clear();
        return true;
    }
    outError = "Failed to establish Cs378x session with OSCam.";
    return false;
}

} // namespace oscam::cs378x

