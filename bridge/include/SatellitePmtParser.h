// bridge/include/SatellitePmtParser.h
//
// Parser de tablas PMT (Program Map Table) y descriptores CA específicos para
// transmisiones satelitales DVB-S, DVB-S2 y DVB-S2X en Android TV.
//
// Responsabilidades:
//  - Parsear la sección PMT del canal sintonizado en la frecuencia satélite.
//  - Extraer los Program Elementary PIDs (Video H.264/H.265/HEVC, Audio AC3/EAC3/AAC).
//  - Extraer los descriptores CA (Descriptor Tag 0x09) tanto a nivel de programa
//    como a nivel de stream individual (Simulcrypt).
//  - Seleccionar automáticamente el ECM_PID óptimo para el CA_system_id deseado.
//  - Formatear la estructura dvbapi para su envío por socket TCP a OSCam.
//
// Autor: android-oscam-bridge

#pragma once

#include <cstdint>
#include <vector>
#include <string>
#include <optional>
#include <unordered_map>

namespace oscam::bridge {

/**
 * @brief Descriptor CA (Conditional Access Descriptor - DVB SI Tag 0x09).
 */
struct CaDescriptor {
    uint16_t caSystemId{0};       ///< E.g. 0x1810 (Nagra), 0x0100 (Seca), 0x0500 (Viaccess)
    uint16_t caPid{0};            ///< PID del flujo ECM donde viajan las CWs cifradas
    std::vector<uint8_t> privateData; ///< Datos específicos del proveedor CA
};

/**
 * @brief Stream elemental en un transpondedor satelital DVB-S/S2/S2X.
 */
struct ElementaryStream {
    uint8_t streamType{0};        ///< Tipo de stream (0x1B: H.264, 0x24: HEVC/H.265, 0x06: AC3/AC4)
    uint16_t elementaryPid{0};    ///< PID del stream de audio o video
    std::vector<CaDescriptor> caDescriptors; ///< Descriptores CA a nivel de stream
};

/**
 * @brief Información completa del canal DVB-S/S2/S2X extraída de la PMT.
 */
struct SatelliteProgramInfo {
    uint16_t programNumber{0};    ///< Service ID del canal satelital
    uint16_t pmtPid{0};           ///< PID de la tabla PMT
    uint16_t pcrPid{0};           ///< Program Clock Reference PID
    std::vector<CaDescriptor> programCaDescriptors; ///< Descriptores CA globales del programa
    std::vector<ElementaryStream> streams;          ///< Streams elementales (Video, Audios, etc.)

    /**
     * @brief Busca el ECM PID para un CAID dado, verificando primero los descriptores
     * a nivel de programa y luego a nivel de stream.
     */
    std::optional<uint16_t> findEcmPidForCaid(uint16_t caid) const {
        for (const auto& desc : programCaDescriptors) {
            if (desc.caSystemId == caid) {
                return desc.caPid;
            }
        }
        for (const auto& stream : streams) {
            for (const auto& desc : stream.caDescriptors) {
                if (desc.caSystemId == caid) {
                    return desc.caPid;
                }
            }
        }
        return std::nullopt;
    }
};

/**
 * @brief Parser de bajo nivel para paquetes MPEG-TS / secciones PSI satelitales.
 */
class SatellitePmtParser {
public:
    /**
     * @brief Parsea una sección binaria de PMT entregada por el demux satelital.
     * @param pmtSectionData Buffer con los bytes de la sección PMT (comienza en table_id 0x02).
     * @param length Longitud del buffer en bytes.
     * @return SatelliteProgramInfo estructurado o std::nullopt si el buffer es inválido o corrupto.
     */
    static std::optional<SatelliteProgramInfo> parsePmtSection(
        const uint8_t* pmtSectionData,
        size_t length);

    /**
     * @brief Genera el payload dvbapi CA_SET_PID para notificar a OSCam todos los PIDs
     * asociados a este canal sintonizado en DVB-S2/S/SX.
     */
    static std::vector<uint8_t> buildDvbapiCaSetPidPayload(
        uint16_t adapterIndex,
        uint16_t ecmPid,
        const std::vector<uint16_t>& elementaryPids);
};

} // namespace oscam::bridge

