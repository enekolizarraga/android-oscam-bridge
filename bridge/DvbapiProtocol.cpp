// bridge/DvbapiProtocol.cpp
//
// Serialización y deserialización del protocolo dvbapi de OSCam.
// Toda la lógica de bytes es big-endian (network byte order).
//
// Referencia de bytes exactos:
//   https://github.com/oscam-emu/oscam-patched/blob/master/oscam-dvbapi.c
//   https://github.com/tvheadend/tvheadend/blob/master/src/descrambler/caid.c

#include "include/DvbapiProtocol.h"
#include "include/BridgeLogger.h"

#include <algorithm>
#include <cassert>
#include <cstring>
#include <sstream>
#include <iomanip>
#include <stdexcept>

namespace oscam::dvbapi {

// ===========================================================================
// Helpers privados de serialización big-endian
// ===========================================================================

void DvbapiProtocol::appendU8(std::vector<uint8_t>& buf, uint8_t v) {
    buf.push_back(v);
}

void DvbapiProtocol::appendU16(std::vector<uint8_t>& buf, uint16_t v) {
    buf.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>( v       & 0xFF));
}

void DvbapiProtocol::appendU32(std::vector<uint8_t>& buf, uint32_t v) {
    buf.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >>  8) & 0xFF));
    buf.push_back(static_cast<uint8_t>( v        & 0xFF));
}

void DvbapiProtocol::appendI32(std::vector<uint8_t>& buf, int32_t v) {
    appendU32(buf, static_cast<uint32_t>(v));
}

uint16_t DvbapiProtocol::readU16(std::span<const uint8_t> buf, size_t offset) {
    assert(offset + 2 <= buf.size());
    return (static_cast<uint16_t>(buf[offset])     << 8)
         |  static_cast<uint16_t>(buf[offset + 1]);
}

uint32_t DvbapiProtocol::readU32(std::span<const uint8_t> buf, size_t offset) {
    assert(offset + 4 <= buf.size());
    return (static_cast<uint32_t>(buf[offset])     << 24)
         | (static_cast<uint32_t>(buf[offset + 1]) << 16)
         | (static_cast<uint32_t>(buf[offset + 2]) <<  8)
         |  static_cast<uint32_t>(buf[offset + 3]);
}

int32_t DvbapiProtocol::readI32(std::span<const uint8_t> buf, size_t offset) {
    return static_cast<int32_t>(readU32(buf, offset));
}

// ===========================================================================
// Serialización (bridge → OSCam)
// ===========================================================================

/**
 * Formato CLIENT_INFO (wire):
 *   uint32_t  opcode   = DVBAPI_CLIENT_INFO          (4 bytes, BE)
 *   uint16_t  protover = kProtocolVersion             (2 bytes, BE)
 *   uint8_t   namelen  = length of client name string (1 byte)
 *   uint8_t[] name     = client name (namelen bytes, UTF-8, NO null terminator)
 */
std::vector<uint8_t> DvbapiProtocol::buildClientInfo(const ClientInfo& info) {
    std::vector<uint8_t> buf;
    buf.reserve(4 + 2 + 1 + info.clientName.size());

    appendU32(buf, DVBAPI_CLIENT_INFO);
    appendU16(buf, info.protocolVersion);

    const auto nameLen = static_cast<uint8_t>(
        std::min(info.clientName.size(), static_cast<size_t>(255)));
    appendU8(buf, nameLen);

    buf.insert(buf.end(),
               info.clientName.begin(),
               info.clientName.begin() + nameLen);

    BLOG_D("buildClientInfo: proto=%u name='%s' -> %zu bytes",
           info.protocolVersion, info.clientName.c_str(), buf.size());
    return buf;
}

/**
 * Formato CA_SET_PID (wire):
 *   uint32_t  opcode      = DVBAPI_CA_SET_PID         (4 bytes, BE)
 *   uint8_t   adapter_idx                              (1 byte)
 *   uint32_t  ca_pid.pid                              (4 bytes, BE)
 *   int32_t   ca_pid.index                            (4 bytes, BE, con signo)
 *
 * Total: 13 bytes.
 *
 * Referencia: struct ca_pid_t del kernel Linux DVB API v3.
 */
std::vector<uint8_t> DvbapiProtocol::buildCaSetPid(uint8_t adapterId,
                                                    const CaPid& pid) {
    std::vector<uint8_t> buf;
    buf.reserve(13);

    appendU32(buf, DVBAPI_CA_SET_PID);
    appendU8 (buf, adapterId);
    appendU32(buf, pid.pid);
    appendI32(buf, pid.index);

    BLOG_D("buildCaSetPid: adapter=%u pid=0x%04X index=%d -> %zu bytes",
           adapterId, pid.pid, pid.index, buf.size());
    return buf;
}

/**
 * Formato DMX_SET_FILTER (wire):
 *   uint32_t  opcode       = DVBAPI_DMX_SET_FILTER    (4 bytes, BE)
 *   uint8_t   adapter_idx                             (1 byte)
 *   uint8_t   demux_idx                               (1 byte)
 *   uint8_t   filter_num                              (1 byte)
 *   uint16_t  pid                                     (2 bytes, BE)
 *   uint8_t[16] filter                                (16 bytes)
 *   uint8_t[16] mask                                  (16 bytes)
 *   uint8_t[16] mode                                  (16 bytes)
 *   uint32_t  timeout                                 (4 bytes, BE)
 *   uint32_t  flags                                   (4 bytes, BE)
 *
 * Total: 4 + 1 + 1 + 1 + 2 + 48 + 4 + 4 = 65 bytes.
 */
std::vector<uint8_t> DvbapiProtocol::buildDmxSetFilter(const DmxFilter& f) {
    std::vector<uint8_t> buf;
    buf.reserve(65);

    appendU32(buf, DVBAPI_DMX_SET_FILTER);
    appendU8 (buf, f.adapterId);
    appendU8 (buf, f.demuxId);
    appendU8 (buf, f.filterId);
    appendU16(buf, f.pid);
    buf.insert(buf.end(), f.filter, f.filter + kDmxFilterLen);
    buf.insert(buf.end(), f.mask,   f.mask   + kDmxFilterLen);
    buf.insert(buf.end(), f.mode,   f.mode   + kDmxFilterLen);
    appendU32(buf, f.timeout);
    appendU32(buf, f.flags);

    BLOG_D("buildDmxSetFilter: adapter=%u demux=%u filter=%u pid=0x%04X -> %zu bytes",
           f.adapterId, f.demuxId, f.filterId, f.pid, buf.size());
    return buf;
}

/**
 * Formato DMX_STOP (wire):
 *   uint32_t  opcode     = DVBAPI_DMX_STOP            (4 bytes, BE)
 *   uint8_t   adapter_idx                             (1 byte)
 *   uint8_t   demux_idx                               (1 byte)
 *   uint8_t   filter_num                              (1 byte)
 *   uint16_t  pid                                     (2 bytes, BE)
 *
 * Total: 9 bytes.
 */
std::vector<uint8_t> DvbapiProtocol::buildDmxStop(uint8_t  adapterId,
                                                   uint8_t  demuxId,
                                                   uint8_t  filterId,
                                                   uint16_t pid) {
    std::vector<uint8_t> buf;
    buf.reserve(9);

    appendU32(buf, DVBAPI_DMX_STOP);
    appendU8 (buf, adapterId);
    appendU8 (buf, demuxId);
    appendU8 (buf, filterId);
    appendU16(buf, pid);

    BLOG_D("buildDmxStop: adapter=%u demux=%u filter=%u pid=0x%04X -> %zu bytes",
           adapterId, demuxId, filterId, pid, buf.size());
    return buf;
}

// ===========================================================================
// Deserialización (OSCam → bridge)
// ===========================================================================

std::optional<uint32_t> DvbapiProtocol::peekOpcode(std::span<const uint8_t> buf) {
    if (buf.size() < 4) return std::nullopt;
    return readU32(buf, 0);
}

/**
 * Tamaños de mensaje en bytes (incluyendo los 4 del opcode):
 *
 *   CA_SET_DESCR      : 4 (opcode) + 4 (index) + 4 (parity) + 8 (cw)  = 20
 *   CA_SET_DESCR_MODE : 4 (opcode) + 4 (index) + 4 (algo)  + 4 (mode) = 16
 *   SERVER_INFO       : variable (opcode + uint16 + uint8 + N)          = mínimo 7
 *
 * Para SERVER_INFO devolvemos 0 (variable) — el llamador debe manejar
 * la longitud dinámicamente.
 */
size_t DvbapiProtocol::expectedMessageSize(uint32_t opcode) {
    switch (opcode) {
        case DVBAPI_CA_SET_DESCR:      return 20;
        case DVBAPI_CA_SET_DESCR_MODE: return 16;
        case DVBAPI_SERVER_INFO:       return 0;   // variable
        default:
            BLOG_W("expectedMessageSize: opcode desconocido 0x%08X", opcode);
            return 0;
    }
}

/**
 * Formato CA_SET_DESCR (wire) recibido de OSCam:
 *   uint32_t  opcode  = DVBAPI_CA_SET_DESCR  (4 bytes, BE) — ya consumido por peekOpcode
 *   int32_t   index                           (4 bytes, BE)
 *   int32_t   parity  (0=even, 1=odd)        (4 bytes, BE)
 *   uint8_t[8] cw                            (8 bytes)
 *
 * Total esperado: 20 bytes.
 */
std::optional<CaDescr> DvbapiProtocol::parseCaSetDescr(std::span<const uint8_t> buf) {
    constexpr size_t kExpected = 20;
    if (buf.size() < kExpected) {
        BLOG_W("parseCaSetDescr: buffer insuficiente (%zu < %zu)", buf.size(), kExpected);
        return std::nullopt;
    }

    CaDescr d{};
    d.index  = readI32(buf, 4);
    d.parity = readI32(buf, 8);
    std::memcpy(d.cw, buf.data() + 12, kCwLen);

    BLOG_I("parseCaSetDescr: index=%d parity=%d cw=%s",
           d.index, d.parity,
           cwToHex(std::span<const uint8_t, kCwLen>(d.cw, kCwLen)).c_str());
    return d;
}

/**
 * Formato CA_SET_DESCR_MODE (wire):
 *   uint32_t  opcode  = DVBAPI_CA_SET_DESCR_MODE  (4 bytes)
 *   int32_t   index                                (4 bytes)
 *   uint32_t  algo                                 (4 bytes)
 *   uint32_t  mode                                 (4 bytes)
 *
 * Total: 16 bytes.
 */
std::optional<CaDescrMode> DvbapiProtocol::parseCaDescrMode(std::span<const uint8_t> buf) {
    constexpr size_t kExpected = 16;
    if (buf.size() < kExpected) {
        BLOG_W("parseCaDescrMode: buffer insuficiente (%zu < %zu)", buf.size(), kExpected);
        return std::nullopt;
    }

    CaDescrMode m{};
    m.index = readI32(buf, 4);
    m.algo  = readU32(buf, 8);
    m.mode  = readU32(buf, 12);

    BLOG_D("parseCaDescrMode: index=%d algo=%u mode=%u", m.index, m.algo, m.mode);
    return m;
}

/**
 * Formato SERVER_INFO (wire) recibido de OSCam:
 *   uint32_t  opcode   = DVBAPI_SERVER_INFO  (4 bytes)
 *   uint16_t  protover                       (2 bytes, BE)
 *   uint8_t   namelen                        (1 byte)
 *   uint8_t[] name                           (namelen bytes)
 *
 * Mínimo 7 bytes; longitud total = 7 + namelen.
 */
std::optional<ServerInfo> DvbapiProtocol::parseServerInfo(std::span<const uint8_t> buf) {
    constexpr size_t kMinSize = 7;
    if (buf.size() < kMinSize) {
        return std::nullopt;
    }

    ServerInfo si{};
    si.protocolVersion = readU16(buf, 4);
    const auto nameLen = static_cast<size_t>(buf[6]);

    if (buf.size() < kMinSize + nameLen) {
        BLOG_W("parseServerInfo: buffer insuficiente para nombre (%zu < %zu)",
               buf.size(), kMinSize + nameLen);
        return std::nullopt;
    }

    si.serverName.assign(
        reinterpret_cast<const char*>(buf.data() + 7), nameLen);

    BLOG_I("parseServerInfo: proto=%u server='%s'",
           si.protocolVersion, si.serverName.c_str());
    return si;
}

// ===========================================================================
// Utilidades de diagnóstico
// ===========================================================================

std::string DvbapiProtocol::opcodeToString(uint32_t opcode) {
    switch (opcode) {
        case DVBAPI_CA_SET_PID:        return "CA_SET_PID";
        case DVBAPI_CA_SET_DESCR:      return "CA_SET_DESCR";
        case DVBAPI_DMX_SET_FILTER:    return "DMX_SET_FILTER";
        case DVBAPI_DMX_STOP:          return "DMX_STOP";
        case DVBAPI_CA_SET_DESCR_MODE: return "CA_SET_DESCR_MODE";
        case DVBAPI_CLIENT_INFO:       return "CLIENT_INFO";
        case DVBAPI_SERVER_INFO:       return "SERVER_INFO";
        case DVBAPI_ECM_INFO:          return "ECM_INFO";
        default: {
            std::ostringstream oss;
            oss << "UNKNOWN(0x" << std::hex << std::setw(8)
                << std::setfill('0') << opcode << ')';
            return oss.str();
        }
    }
}

std::string DvbapiProtocol::cwToHex(std::span<const uint8_t, kCwLen> cw) {
    std::ostringstream oss;
    for (size_t i = 0; i < kCwLen; ++i) {
        if (i > 0) oss << ' ';
        oss << std::hex << std::setw(2) << std::setfill('0')
            << static_cast<unsigned>(cw[i]);
    }
    return oss.str();
}

} // namespace oscam::dvbapi
