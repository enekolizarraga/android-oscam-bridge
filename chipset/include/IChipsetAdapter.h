// chipset/include/IChipsetAdapter.h
//
// Hardware abstraction layer for Android TV chipsets (Amlogic, MediaTek, Realtek,
// Broadcom, Synaptics, Novatek).
// Defines required operations for the CAS plugin to interface with hardware demuxers
// and transport stream descrambler units (DVB-CSA / AES-128 descrambler).
//
// Author: android-oscam-bridge

#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include <memory>

namespace oscam::chipset {

/**
 * @brief Supported SoC architecture types.
 */
enum class ChipsetType {
    kUnknown = 0,
    kAmlogic,    ///< S905, S905X, S905D, S928X (TCL, Xiaomi, MeCool, Nokia)
    kMediaTek,   ///< MT5895, MT9632, Pentonic 700/1000 (Sony, Philips, TCL, Hisense)
    kRealtek,    ///< RTD2851, RTD1319, RTD2873 (Chiq, Strong, Thomson)
    kBroadcom,   ///< BCM7252, BCM72604, BCM72180 (Technicolor, Humax, Bouygues, Swisscom)
    kSynaptics,  ///< VS680, Berlin BG4CT (Google TV reference, Canal+, Bbox)
    kNovatek     ///< NT72671, NT72688 (Hisense, Skyworth, Toshiba, Sharp)
};

/**
 * @brief Descrambling algorithm types for DVB-S2, DVB-S2X, DVB-T2, and DVB-C.
 */
enum class CipherAlgorithm : uint8_t {
    kDvbCsa1_2 = 0, ///< DVB-CSA v1/v2 (8-byte Control Word)
    kDvbCsa3   = 1, ///< DVB-CSA v3 (16-byte Control Word)
    kAes128    = 2, ///< AES-128 (16-byte key)
    kDes       = 3  ///< DES (8-byte key)
};

/**
 * @brief Parameters for Control Word (CW) key injection into hardware descramblers.
 */
struct KeyInjectionParams {
    int32_t sessionHandle{0};              ///< CAS session ID
    int32_t streamIndex{0};                ///< Demux stream slot / channel index
    int32_t parity{0};                     ///< 0 = Even, 1 = Odd
    CipherAlgorithm algorithm{CipherAlgorithm::kDvbCsa1_2}; ///< Descrambler cipher algorithm
    uint8_t cw[8]{0};                      ///< 8-byte Control Word (legacy DVB-CSA1/2)
    std::vector<uint8_t> key;              ///< Key buffer (8 bytes CSA2, 16 bytes CSA3/AES)
    bool requiresTeeRouting{false};        ///< Whether the SoC enforces Trusted Execution Environment key injection
};

/**
 * @brief Abstract interface for SoC-specific hardware adapters.
 */
class IChipsetAdapter {
public:
    virtual ~IChipsetAdapter() = default;

    /**
     * @brief Returns the chipset type of the current adapter.
     */
    virtual ChipsetType getChipsetType() const noexcept = 0;

    /**
     * @brief Returns the commercial brand or SoC family name.
     */
    virtual std::string getChipsetName() const noexcept = 0;

    /**
     * @brief Registers a CAID with the SoC demux hardware filter subsystem.
     * @param caSystemId CA identifier (e.g. 0x1810, 0x0100, 0x0500, 0x0604).
     * @return true if hardware filter setup succeeded.
     */
    virtual bool registerCasSystemId(uint16_t caSystemId) = 0;

    /**
     * @brief Queries the active ECM PID associated with a service or channel in demux.
     * @param demuxIndex Hardware demultiplexer index.
     * @param serviceId DVB Service ID (optional/contextual).
     * @return ECM PID if found, or negative value on failure.
     */
    virtual int32_t getEcmPidFromDemux(int32_t demuxIndex, uint16_t serviceId) = 0;

    /**
     * @brief Injects the resolved Control Word into the hardware descrambler.
     * @param params Key, parity, slot, and cipher algorithm configuration.
     * @return true if injection via ioctl / userspace / TEE was successful.
     */
    virtual bool injectControlWord(const KeyInjectionParams& params) = 0;

    /**
     * @brief Returns the primary Linux device node path for this SoC.
     * E.g. "/dev/dvb0.demux0", "/dev/amstream_mpps", "/dev/ca0", "/dev/bcm_ca0".
     */
    virtual std::string getDeviceNodePath() const noexcept = 0;

    /**
     * @brief Returns the system property prefix for this vendor.
     */
    virtual std::string getVendorPropertyPath() const noexcept = 0;

    /**
     * @brief Initializes file descriptors and kernel driver handles.
     */
    virtual bool initialize() = 0;

    /**
     * @brief Releases associated hardware resources.
     */
    virtual void release() = 0;
};

} // namespace oscam::chipset
