// tests/DvbapiProtocolTest.cpp
//
// Tests unitarios de DvbapiProtocol usando GoogleTest.
//
// No requieren un servidor OSCam real — validan la serialización y
// deserialización contra los bytes exactos del protocolo dvbapi.
//
// Compilar (host, Linux/macOS):
//   g++ -std=c++20 -Wall -Wextra -I../bridge \
//       DvbapiProtocolTest.cpp \
//       ../bridge/DvbapiProtocol.cpp \
//       ../bridge/BridgeLogger.cpp \
//       -lgtest -lgtest_main -lpthread -o protocol_tests
//   ./protocol_tests
//
// Compilar (host, Windows con vcpkg):
//   cl /std:c++20 /W4 /I..\bridge DvbapiProtocolTest.cpp
//      ..\bridge\DvbapiProtocol.cpp ..\bridge\BridgeLogger.cpp
//      /link gtest.lib gtest_main.lib

#include <gtest/gtest.h>
#include "include/DvbapiProtocol.h"

#include <cstring>
#include <vector>

using namespace oscam::dvbapi;

// ===========================================================================
// Fixtures y helpers
// ===========================================================================

/// Lee un uint32_t big-endian de los primeros 4 bytes de un vector.
static uint32_t readBEU32(const std::vector<uint8_t>& v, size_t offset = 0) {
    return (static_cast<uint32_t>(v[offset])     << 24)
         | (static_cast<uint32_t>(v[offset + 1]) << 16)
         | (static_cast<uint32_t>(v[offset + 2]) <<  8)
         |  static_cast<uint32_t>(v[offset + 3]);
}

static uint16_t readBEU16(const std::vector<uint8_t>& v, size_t offset) {
    return (static_cast<uint16_t>(v[offset]) << 8)
         |  static_cast<uint16_t>(v[offset + 1]);
}

static int32_t readBEI32(const std::vector<uint8_t>& v, size_t offset) {
    return static_cast<int32_t>(readBEU32(v, offset));
}

// ===========================================================================
// Tests: buildCaSetPid
// ===========================================================================

TEST(BuildCaSetPid, SizeIs13Bytes) {
    CaPid pid{0x0600, 0};
    const auto buf = DvbapiProtocol::buildCaSetPid(0, pid);
    EXPECT_EQ(buf.size(), 13u);
}

TEST(BuildCaSetPid, OpcodeIsDvbapiCaSetPid) {
    CaPid pid{0x0600, 0};
    const auto buf = DvbapiProtocol::buildCaSetPid(0, pid);
    EXPECT_EQ(readBEU32(buf, 0), DVBAPI_CA_SET_PID);
}

TEST(BuildCaSetPid, AdapterIndexIsCorrect) {
    CaPid pid{0x0600, 0};
    const auto buf = DvbapiProtocol::buildCaSetPid(2, pid);
    EXPECT_EQ(buf[4], 2u);
}

TEST(BuildCaSetPid, PidFieldIsBigEndian) {
    CaPid pid{0x1234, 0};
    const auto buf = DvbapiProtocol::buildCaSetPid(0, pid);
    // Bytes 5-8 = PID en BE
    EXPECT_EQ(buf[5], 0x00u);
    EXPECT_EQ(buf[6], 0x00u);
    EXPECT_EQ(buf[7], 0x12u);
    EXPECT_EQ(buf[8], 0x34u);
}

TEST(BuildCaSetPid, IndexNegativeOne) {
    // index = -1 (desactivar slot) → 0xFFFFFFFF en BE
    CaPid pid{0x0100, -1};
    const auto buf = DvbapiProtocol::buildCaSetPid(0, pid);
    EXPECT_EQ(readBEI32(buf, 9), -1);
}

TEST(BuildCaSetPid, IndexZero) {
    CaPid pid{0x0100, 0};
    const auto buf = DvbapiProtocol::buildCaSetPid(0, pid);
    EXPECT_EQ(readBEI32(buf, 9), 0);
}

// ===========================================================================
// Tests: buildDmxStop
// ===========================================================================

TEST(BuildDmxStop, SizeIs9Bytes) {
    const auto buf = DvbapiProtocol::buildDmxStop(0, 0, 0, 0x0600);
    EXPECT_EQ(buf.size(), 9u);
}

TEST(BuildDmxStop, OpcodeCorrect) {
    const auto buf = DvbapiProtocol::buildDmxStop(0, 0, 0, 0x0600);
    EXPECT_EQ(readBEU32(buf, 0), DVBAPI_DMX_STOP);
}

TEST(BuildDmxStop, PidBigEndian) {
    const auto buf = DvbapiProtocol::buildDmxStop(0, 0, 0, 0xABCD);
    EXPECT_EQ(readBEU16(buf, 7), static_cast<uint16_t>(0xABCD));
}

TEST(BuildDmxStop, AllFieldsCorrect) {
    const auto buf = DvbapiProtocol::buildDmxStop(1, 2, 3, 0x0600);
    EXPECT_EQ(buf[4], 1u);  // adapterId
    EXPECT_EQ(buf[5], 2u);  // demuxId
    EXPECT_EQ(buf[6], 3u);  // filterId
}

// ===========================================================================
// Tests: buildDmxSetFilter
// ===========================================================================

TEST(BuildDmxSetFilter, SizeIs65Bytes) {
    DmxFilter f{};
    f.pid = 0x0600;
    const auto buf = DvbapiProtocol::buildDmxSetFilter(f);
    EXPECT_EQ(buf.size(), 65u);
}

TEST(BuildDmxSetFilter, OpcodeCorrect) {
    DmxFilter f{};
    const auto buf = DvbapiProtocol::buildDmxSetFilter(f);
    EXPECT_EQ(readBEU32(buf, 0), DVBAPI_DMX_SET_FILTER);
}

TEST(BuildDmxSetFilter, FilterDataRoundtrip) {
    DmxFilter f{};
    f.adapterId = 0;
    f.demuxId   = 1;
    f.filterId  = 2;
    f.pid       = 0x0600;
    f.filter[0] = 0x80;
    f.mask[0]   = 0xFE;
    f.timeout   = 1000;
    f.flags     = 1;

    const auto buf = DvbapiProtocol::buildDmxSetFilter(f);

    EXPECT_EQ(buf[4], 0u);    // adapterId
    EXPECT_EQ(buf[5], 1u);    // demuxId
    EXPECT_EQ(buf[6], 2u);    // filterId
    EXPECT_EQ(readBEU16(buf, 7), static_cast<uint16_t>(0x0600));  // pid
    EXPECT_EQ(buf[9],  0x80u); // filter[0]
    EXPECT_EQ(buf[25], 0xFEu); // mask[0]

    // timeout en BE (bytes 57-60)
    EXPECT_EQ(readBEU32(buf, 57), 1000u);
    // flags en BE (bytes 61-64)
    EXPECT_EQ(readBEU32(buf, 61), 1u);
}

// ===========================================================================
// Tests: buildClientInfo
// ===========================================================================

TEST(BuildClientInfo, MinimumSize) {
    ClientInfo ci{kProtocolVersion, ""};
    const auto buf = DvbapiProtocol::buildClientInfo(ci);
    // 4 (opcode) + 2 (proto) + 1 (namelen) + 0 (name) = 7
    EXPECT_EQ(buf.size(), 7u);
}

TEST(BuildClientInfo, OpcodeCorrect) {
    ClientInfo ci{kProtocolVersion, "test"};
    const auto buf = DvbapiProtocol::buildClientInfo(ci);
    EXPECT_EQ(readBEU32(buf, 0), DVBAPI_CLIENT_INFO);
}

TEST(BuildClientInfo, ProtocolVersionInBE) {
    ClientInfo ci{3, "bridge"};
    const auto buf = DvbapiProtocol::buildClientInfo(ci);
    EXPECT_EQ(readBEU16(buf, 4), 3u);
}

TEST(BuildClientInfo, NameLenAndContent) {
    const std::string name = "oscam-bridge";
    ClientInfo ci{kProtocolVersion, name};
    const auto buf = DvbapiProtocol::buildClientInfo(ci);

    EXPECT_EQ(buf.size(), 7u + name.size());
    EXPECT_EQ(buf[6], static_cast<uint8_t>(name.size()));  // namelen
    EXPECT_EQ(std::string(reinterpret_cast<const char*>(buf.data() + 7),
                          name.size()),
              name);
}

TEST(BuildClientInfo, LongNameTruncatedAt255) {
    const std::string longName(300, 'X');
    ClientInfo ci{kProtocolVersion, longName};
    const auto buf = DvbapiProtocol::buildClientInfo(ci);
    EXPECT_EQ(buf[6], 255u);  // namelen truncado
    EXPECT_EQ(buf.size(), 7u + 255u);
}

// ===========================================================================
// Tests: parseCaSetDescr
// ===========================================================================

/// Construye un buffer válido de CA_SET_DESCR con los valores dados.
static std::vector<uint8_t> makeCaSetDescrBuf(int32_t index, int32_t parity,
                                               const uint8_t cw[8]) {
    std::vector<uint8_t> buf(20, 0);
    // opcode
    const uint32_t op = DVBAPI_CA_SET_DESCR;
    buf[0] = (op >> 24) & 0xFF;
    buf[1] = (op >> 16) & 0xFF;
    buf[2] = (op >>  8) & 0xFF;
    buf[3] =  op        & 0xFF;
    // index (BE, signed)
    const uint32_t idxU = static_cast<uint32_t>(index);
    buf[4] = (idxU >> 24) & 0xFF;
    buf[5] = (idxU >> 16) & 0xFF;
    buf[6] = (idxU >>  8) & 0xFF;
    buf[7] =  idxU        & 0xFF;
    // parity (BE, signed)
    const uint32_t parU = static_cast<uint32_t>(parity);
    buf[8]  = (parU >> 24) & 0xFF;
    buf[9]  = (parU >> 16) & 0xFF;
    buf[10] = (parU >>  8) & 0xFF;
    buf[11] =  parU        & 0xFF;
    // cw (8 bytes)
    std::memcpy(buf.data() + 12, cw, 8);
    return buf;
}

TEST(ParseCaSetDescr, ValidMessage) {
    const uint8_t cw[8] = {0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF};
    const auto buf = makeCaSetDescrBuf(0, kEvenKeyIndex, cw);

    auto result = DvbapiProtocol::parseCaSetDescr(
        std::span<const uint8_t>(buf.data(), buf.size()));

    ASSERT_TRUE(result.has_value());
    EXPECT_EQ(result->index,  0);
    EXPECT_EQ(result->parity, kEvenKeyIndex);
    EXPECT_EQ(std::memcmp(result->cw, cw, 8), 0);
}

TEST(ParseCaSetDescr, OddParity) {
    const uint8_t cw[8] = {0xFF, 0xFE, 0xFD, 0xFC, 0xFB, 0xFA, 0xF9, 0xF8};
    const auto buf = makeCaSetDescrBuf(1, kOddKeyIndex, cw);

    auto result = DvbapiProtocol::parseCaSetDescr(
        std::span<const uint8_t>(buf.data(), buf.size()));

    ASSERT_TRUE(result.has_value());
    EXPECT_EQ(result->parity, kOddKeyIndex);
}

TEST(ParseCaSetDescr, BufferTooShort) {
    const uint8_t cw[8] = {};
    auto buf = makeCaSetDescrBuf(0, 0, cw);
    buf.resize(15);  // Truncar

    auto result = DvbapiProtocol::parseCaSetDescr(
        std::span<const uint8_t>(buf.data(), buf.size()));

    EXPECT_FALSE(result.has_value());
}

TEST(ParseCaSetDescr, EmptyBuffer) {
    auto result = DvbapiProtocol::parseCaSetDescr(
        std::span<const uint8_t>{});
    EXPECT_FALSE(result.has_value());
}

// ===========================================================================
// Tests: peekOpcode y expectedMessageSize
// ===========================================================================

TEST(PeekOpcode, ReturnsNulloptForShortBuffer) {
    std::vector<uint8_t> buf = {0x40, 0x10};
    auto result = DvbapiProtocol::peekOpcode(
        std::span<const uint8_t>(buf.data(), buf.size()));
    EXPECT_FALSE(result.has_value());
}

TEST(PeekOpcode, ReturnsCorrectOpcode) {
    std::vector<uint8_t> buf(20, 0);
    const uint32_t op = DVBAPI_CA_SET_DESCR;
    buf[0] = (op >> 24) & 0xFF;
    buf[1] = (op >> 16) & 0xFF;
    buf[2] = (op >>  8) & 0xFF;
    buf[3] =  op        & 0xFF;

    auto result = DvbapiProtocol::peekOpcode(
        std::span<const uint8_t>(buf.data(), buf.size()));
    ASSERT_TRUE(result.has_value());
    EXPECT_EQ(*result, DVBAPI_CA_SET_DESCR);
}

TEST(ExpectedMessageSize, CaSetDescrIs20) {
    EXPECT_EQ(DvbapiProtocol::expectedMessageSize(DVBAPI_CA_SET_DESCR), 20u);
}

TEST(ExpectedMessageSize, CaSetDescrModeIs16) {
    EXPECT_EQ(DvbapiProtocol::expectedMessageSize(DVBAPI_CA_SET_DESCR_MODE), 16u);
}

TEST(ExpectedMessageSize, ServerInfoIsVariable) {
    EXPECT_EQ(DvbapiProtocol::expectedMessageSize(DVBAPI_SERVER_INFO), 0u);
}

TEST(ExpectedMessageSize, UnknownOpcodeIsZero) {
    EXPECT_EQ(DvbapiProtocol::expectedMessageSize(0xDEADBEEF), 0u);
}

// ===========================================================================
// Tests: parseServerInfo
// ===========================================================================

static std::vector<uint8_t> makeServerInfoBuf(uint16_t proto,
                                               const std::string& name) {
    std::vector<uint8_t> buf;
    const uint32_t op = DVBAPI_SERVER_INFO;
    buf.push_back((op >> 24) & 0xFF);
    buf.push_back((op >> 16) & 0xFF);
    buf.push_back((op >>  8) & 0xFF);
    buf.push_back( op        & 0xFF);
    buf.push_back((proto >> 8) & 0xFF);
    buf.push_back( proto       & 0xFF);
    buf.push_back(static_cast<uint8_t>(name.size()));
    buf.insert(buf.end(), name.begin(), name.end());
    return buf;
}

TEST(ParseServerInfo, ValidMessage) {
    const auto buf = makeServerInfoBuf(3, "OSCam");
    auto result = DvbapiProtocol::parseServerInfo(
        std::span<const uint8_t>(buf.data(), buf.size()));
    ASSERT_TRUE(result.has_value());
    EXPECT_EQ(result->protocolVersion, 3u);
    EXPECT_EQ(result->serverName, "OSCam");
}

TEST(ParseServerInfo, EmptyName) {
    const auto buf = makeServerInfoBuf(3, "");
    auto result = DvbapiProtocol::parseServerInfo(
        std::span<const uint8_t>(buf.data(), buf.size()));
    ASSERT_TRUE(result.has_value());
    EXPECT_EQ(result->serverName, "");
}

TEST(ParseServerInfo, BufferTooShort) {
    std::vector<uint8_t> buf = {0xFF, 0xFF, 0x00, 0x01};  // Solo 4 bytes
    auto result = DvbapiProtocol::parseServerInfo(
        std::span<const uint8_t>(buf.data(), buf.size()));
    EXPECT_FALSE(result.has_value());
}

TEST(ParseServerInfo, NameLongerThanBuffer) {
    auto buf = makeServerInfoBuf(3, "short");
    buf[6] = 100;  // Mentir sobre el tamaño del nombre
    buf.resize(10); // Buffer no tiene esos 100 bytes
    auto result = DvbapiProtocol::parseServerInfo(
        std::span<const uint8_t>(buf.data(), buf.size()));
    EXPECT_FALSE(result.has_value());
}

// ===========================================================================
// Tests: opcodeToString (smoke test)
// ===========================================================================

TEST(OpcodeToString, KnownOpcodes) {
    EXPECT_EQ(DvbapiProtocol::opcodeToString(DVBAPI_CA_SET_PID),        "CA_SET_PID");
    EXPECT_EQ(DvbapiProtocol::opcodeToString(DVBAPI_CA_SET_DESCR),      "CA_SET_DESCR");
    EXPECT_EQ(DvbapiProtocol::opcodeToString(DVBAPI_DMX_SET_FILTER),    "DMX_SET_FILTER");
    EXPECT_EQ(DvbapiProtocol::opcodeToString(DVBAPI_DMX_STOP),          "DMX_STOP");
    EXPECT_EQ(DvbapiProtocol::opcodeToString(DVBAPI_CA_SET_DESCR_MODE), "CA_SET_DESCR_MODE");
    EXPECT_EQ(DvbapiProtocol::opcodeToString(DVBAPI_CLIENT_INFO),       "CLIENT_INFO");
    EXPECT_EQ(DvbapiProtocol::opcodeToString(DVBAPI_SERVER_INFO),       "SERVER_INFO");
}

TEST(OpcodeToString, UnknownOpcode) {
    const std::string s = DvbapiProtocol::opcodeToString(0xDEADBEEF);
    EXPECT_FALSE(s.empty());
    EXPECT_NE(s.find("UNKNOWN"), std::string::npos);
}

// ===========================================================================
// Tests: cwToHex
// ===========================================================================

TEST(CwToHex, AllZeros) {
    const uint8_t cw[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    const std::string result = DvbapiProtocol::cwToHex(
        std::span<const uint8_t, 8>(cw, 8));
    EXPECT_EQ(result, "00 00 00 00 00 00 00 00");
}

TEST(CwToHex, AllFF) {
    const uint8_t cw[8] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
    const std::string result = DvbapiProtocol::cwToHex(
        std::span<const uint8_t, 8>(cw, 8));
    EXPECT_EQ(result, "ff ff ff ff ff ff ff ff");
}

TEST(CwToHex, MixedValues) {
    const uint8_t cw[8] = {0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF};
    const std::string result = DvbapiProtocol::cwToHex(
        std::span<const uint8_t, 8>(cw, 8));
    EXPECT_EQ(result, "01 23 45 67 89 ab cd ef");
}
