// tests/ChipsetDetectorTest.cpp
//
// Tests unitarios para la capa de abstracción de chipset (Fase 2).

#include <gtest/gtest.h>
#include "../chipset/include/ChipsetDetector.h"
#include "../chipset/include/AmlogicAdapter.h"
#include "../chipset/include/MediaTekAdapter.h"
#include "../chipset/include/RealtekAdapter.h"
#include "../chipset/include/BroadcomAdapter.h"
#include "../chipset/include/SynapticsAdapter.h"
#include "../chipset/include/NovatekAdapter.h"

#include <unordered_map>

using namespace oscam::chipset;

namespace {

SystemPropertyGetter createMockGetter(
    std::unordered_map<std::string, std::string> props) {
    return [props = std::move(props)](const std::string& key) -> std::string {
        auto it = props.find(key);
        if (it != props.end()) {
            return it->second;
        }
        return "";
    };
}

} // namespace

// ===========================================================================
// Tests: Identificación de Amlogic
// ===========================================================================

TEST(ChipsetDetectorTest, DetectsAmlogicFromPlatformMeson) {
    auto getter = createMockGetter({
        {"ro.board.platform", "meson"},
        {"ro.hardware", "amlogic"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kAmlogic);
    EXPECT_NE(adapter->getChipsetName().find("Amlogic"), std::string::npos);
}

TEST(ChipsetDetectorTest, DetectsAmlogicS905X) {
    auto getter = createMockGetter({
        {"ro.board.platform", "s905x4"},
        {"ro.hardware", "amlogic"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kAmlogic);
}

TEST(ChipsetDetectorTest, DetectsAmlogicS928X) {
    auto getter = createMockGetter({
        {"ro.board.platform", "s928x"},
        {"ro.hardware", "amlogic_t7"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kAmlogic);
}

// ===========================================================================
// Tests: Identificación de MediaTek
// ===========================================================================

TEST(ChipsetDetectorTest, DetectsMediaTekMT5895) {
    auto getter = createMockGetter({
        {"ro.board.platform", "mt5895"},
        {"ro.hardware", "mt5895"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kMediaTek);
    EXPECT_NE(adapter->getChipsetName().find("MediaTek"), std::string::npos);
}

TEST(ChipsetDetectorTest, DetectsMediaTekMT9632) {
    auto getter = createMockGetter({
        {"ro.board.platform", "mt9632"},
        {"ro.hardware", "mediatek"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kMediaTek);
}

// ===========================================================================
// Tests: Identificación de Realtek
// ===========================================================================

TEST(ChipsetDetectorTest, DetectsRealtekRTD2851) {
    auto getter = createMockGetter({
        {"ro.board.platform", "rtd2851"},
        {"ro.hardware", "realtek"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kRealtek);
    EXPECT_NE(adapter->getChipsetName().find("Realtek"), std::string::npos);
}

TEST(ChipsetDetectorTest, DetectsRealtekRTD1319) {
    auto getter = createMockGetter({
        {"ro.board.platform", "rtd1319"},
        {"ro.hardware", "realtek"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kRealtek);
}

// ===========================================================================
// Tests: Fallback explícito ante SoCs no soportados
// ===========================================================================

TEST(ChipsetDetectorTest, ReturnsNullptrOnUnsupportedQualcomm) {
    auto getter = createMockGetter({
        {"ro.board.platform", "kona"},
        {"ro.hardware", "qcom"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    EXPECT_EQ(adapter, nullptr);
}

TEST(ChipsetDetectorTest, ReturnsNullptrOnEmptyProperties) {
    auto getter = createMockGetter({});
    auto adapter = ChipsetDetector::detect(getter);
    EXPECT_EQ(adapter, nullptr);
}

// ===========================================================================
// Tests: Operaciones básicas de los Adapters
// ===========================================================================

TEST(ChipsetAdaptersTest, AmlogicAdapterOperations) {
    AmlogicAdapter adapter;
    EXPECT_TRUE(adapter.initialize());
    EXPECT_TRUE(adapter.registerCasSystemId(0x0604));

    KeyInjectionParams params{};
    params.streamIndex = 0;
    params.parity = 0;
    params.cw[0] = 0xAA;
    EXPECT_TRUE(adapter.injectControlWord(params));
    adapter.release();
}

TEST(ChipsetAdaptersTest, MediaTekAdapterOperations) {
    MediaTekAdapter adapter;
    EXPECT_TRUE(adapter.initialize());
    EXPECT_TRUE(adapter.registerCasSystemId(0x1801));

    KeyInjectionParams params{};
    params.streamIndex = 1;
    params.parity = 1;
    EXPECT_TRUE(adapter.injectControlWord(params));
    adapter.release();
}

TEST(ChipsetAdaptersTest, RealtekAdapterOperations) {
    RealtekAdapter adapter;
    EXPECT_TRUE(adapter.initialize());
    EXPECT_TRUE(adapter.registerCasSystemId(0x0500));

    KeyInjectionParams params{};
    params.streamIndex = 0;
    params.parity = 0;
    EXPECT_TRUE(adapter.injectControlWord(params));
    adapter.release();
}

// ===========================================================================
// Tests: Identification and operations of Broadcom, Synaptics, Novatek
// ===========================================================================

TEST(ChipsetDetectorTest, DetectsBroadcomBCM7252) {
    auto getter = createMockGetter({
        {"ro.board.platform", "bcm7252"},
        {"ro.hardware", "broadcom"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kBroadcom);
    EXPECT_NE(adapter->getChipsetName().find("Broadcom"), std::string::npos);
}

TEST(ChipsetDetectorTest, DetectsSynapticsVS680) {
    auto getter = createMockGetter({
        {"ro.board.platform", "vs680"},
        {"ro.hardware", "synaptics"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kSynaptics);
}

TEST(ChipsetDetectorTest, DetectsNovatekNT72671) {
    auto getter = createMockGetter({
        {"ro.board.platform", "nt72671"},
        {"ro.hardware", "novatek"}
    });
    auto adapter = ChipsetDetector::detect(getter);
    ASSERT_NE(adapter, nullptr);
    EXPECT_EQ(adapter->getChipsetType(), ChipsetType::kNovatek);
}

TEST(ChipsetAdaptersTest, BroadcomAdapterOperations) {
    BroadcomAdapter adapter;
    EXPECT_TRUE(adapter.initialize());
    EXPECT_TRUE(adapter.registerCasSystemId(0x1810));
    KeyInjectionParams params{};
    params.streamIndex = 0;
    params.parity = 0;
    params.key = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88};
    EXPECT_TRUE(adapter.injectControlWord(params));
    adapter.release();
}

TEST(ChipsetAdaptersTest, SynapticsAdapterOperations) {
    SynapticsAdapter adapter;
    EXPECT_TRUE(adapter.initialize());
    EXPECT_TRUE(adapter.registerCasSystemId(0x0100));
    KeyInjectionParams params{};
    params.streamIndex = 0;
    params.parity = 0;
    params.key = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
    EXPECT_TRUE(adapter.injectControlWord(params));
    adapter.release();
}

TEST(ChipsetAdaptersTest, NovatekAdapterOperations) {
    NovatekAdapter adapter;
    EXPECT_TRUE(adapter.initialize());
    EXPECT_TRUE(adapter.registerCasSystemId(0x0B00));
    KeyInjectionParams params{};
    params.streamIndex = 0;
    params.parity = 0;
    params.key = {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00, 0x11};
    EXPECT_TRUE(adapter.injectControlWord(params));
    adapter.release();
}

