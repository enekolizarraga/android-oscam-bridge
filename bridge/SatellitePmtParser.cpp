// bridge/SatellitePmtParser.cpp
//
// Implementación del parser PMT y descriptores CA para canales satelitales DVB-S/S2/S2X.
//
// Autor: android-oscam-bridge

#include "include/SatellitePmtParser.h"
#include "include/BridgeLogger.h"

#include <cstring>

namespace oscam::bridge {

std::optional<SatelliteProgramInfo> SatellitePmtParser::parsePmtSection(
    const uint8_t* data,
    size_t length) {

    if (!data || length < 16) {
        BRIDGE_LOGW("parsePmtSection: Buffer PMT demasiado corto (%zu bytes)", length);
        return std::nullopt;
    }

    // El table_id de la PMT siempre es 0x02 en MPEG-TS DVB
    if (data[0] != 0x02) {
        BRIDGE_LOGW("parsePmtSection: table_id no es PMT (esperado 0x02, recibido 0x%02X)", data[0]);
        return std::nullopt;
    }

    // section_length: 12 bits inferiores de bytes 1 y 2
    uint16_t sectionLength = static_cast<uint16_t>(((data[1] & 0x0F) << 8) | data[2]);
    if (sectionLength + 3 > length) {
        BRIDGE_LOGW("parsePmtSection: section_length (%u) excede el tamaño del buffer (%zu)",
                    sectionLength, length);
        return std::nullopt;
    }

    SatelliteProgramInfo info;
    info.programNumber = static_cast<uint16_t>((data[3] << 8) | data[4]);
    info.pcrPid = static_cast<uint16_t>(((data[8] & 0x1F) << 8) | data[9]);

    uint16_t programInfoLength = static_cast<uint16_t>(((data[10] & 0x0F) << 8) | data[11]);
    size_t offset = 12;

    // 1. Parsear descriptores a nivel de programa
    size_t programInfoEnd = offset + programInfoLength;
    if (programInfoEnd > length - 4) { // Menos 4 bytes del CRC32
        BRIDGE_LOGW("parsePmtSection: programInfoLength corrupto");
        return std::nullopt;
    }

    while (offset + 2 <= programInfoEnd) {
        uint8_t descTag = data[offset];
        uint8_t descLen = data[offset + 1];
        offset += 2;

        if (offset + descLen > programInfoEnd) {
            break;
        }

        // Tag 0x09: CA_descriptor (DVB SI / ISO/IEC 13818-1)
        if (descTag == 0x09 && descLen >= 4) {
            CaDescriptor caDesc;
            caDesc.caSystemId = static_cast<uint16_t>((data[offset] << 8) | data[offset + 1]);
            caDesc.caPid = static_cast<uint16_t>(((data[offset + 2] & 0x1F) << 8) | data[offset + 3]);

            if (descLen > 4) {
                caDesc.privateData.assign(data + offset + 4, data + offset + descLen);
            }

            info.programCaDescriptors.push_back(caDesc);
            BRIDGE_LOGI("PMT Satélite Prog=%u: Descriptor CA detectado -> CAID=0x%04X, ECM_PID=0x%04X",
                        info.programNumber, caDesc.caSystemId, caDesc.caPid);
        }

        offset += descLen;
    }

    // 2. Parsear streams elementales (Video, Audios, Subtítulos, etc.)
    size_t sectionEnd = 3 + sectionLength - 4; // Excluir CRC32
    while (offset + 5 <= sectionEnd) {
        ElementaryStream stream;
        stream.streamType = data[offset];
        stream.elementaryPid = static_cast<uint16_t>(((data[offset + 1] & 0x1F) << 8) | data[offset + 2]);
        uint16_t esInfoLength = static_cast<uint16_t>(((data[offset + 3] & 0x0F) << 8) | data[offset + 4]);
        offset += 5;

        if (offset + esInfoLength > sectionEnd) {
            break;
        }

        size_t esEnd = offset + esInfoLength;
        while (offset + 2 <= esEnd) {
            uint8_t descTag = data[offset];
            uint8_t descLen = data[offset + 1];
            offset += 2;

            if (offset + descLen > esEnd) {
                break;
            }

            if (descTag == 0x09 && descLen >= 4) {
                CaDescriptor caDesc;
                caDesc.caSystemId = static_cast<uint16_t>((data[offset] << 8) | data[offset + 1]);
                caDesc.caPid = static_cast<uint16_t>(((data[offset + 2] & 0x1F) << 8) | data[offset + 3]);

                if (descLen > 4) {
                    caDesc.privateData.assign(data + offset + 4, data + offset + descLen);
                }

                stream.caDescriptors.push_back(caDesc);
                BRIDGE_LOGI("PMT Satélite Prog=%u: ES PID=0x%04X (tipo 0x%02X) CAID=0x%04X ECM_PID=0x%04X",
                            info.programNumber, stream.elementaryPid, stream.streamType,
                            caDesc.caSystemId, caDesc.caPid);
            }

            offset += descLen;
        }

        info.streams.push_back(stream);
    }

    BRIDGE_LOGI("PMT Satélite procesada: Programa=%u, Streams=%zu, Descriptores CA globales=%zu",
                info.programNumber, info.streams.size(), info.programCaDescriptors.size());

    return info;
}

std::vector<uint8_t> SatellitePmtParser::buildDvbapiCaSetPidPayload(
    uint16_t adapterIndex,
    uint16_t ecmPid,
    const std::vector<uint16_t>& elementaryPids) {

    // Serializa paquete DVBAPI_CA_SET_PID
    // Estructura:
    // uint32_t opcode (DVBAPI_CA_SET_PID = 0x40086F87)
    // uint16_t adapter
    // uint16_t pid
    // uint32_t index (slot)
    std::vector<uint8_t> payload;
    payload.reserve(32 + elementaryPids.size() * 8);

    auto appendU16 = [&payload](uint16_t val) {
        payload.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
        payload.push_back(static_cast<uint8_t>(val & 0xFF));
    };

    auto appendU32 = [&payload](uint32_t val) {
        payload.push_back(static_cast<uint8_t>((val >> 24) & 0xFF));
        payload.push_back(static_cast<uint8_t>((val >> 16) & 0xFF));
        payload.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
        payload.push_back(static_cast<uint8_t>(val & 0xFF));
    };

    // Opcode DVBAPI_CA_SET_PID
    appendU32(0x40086F87);
    appendU16(adapterIndex);
    appendU16(ecmPid);
    appendU32(0); // Slot 0 para el ECM principal

    return payload;
}

} // namespace oscam::bridge

