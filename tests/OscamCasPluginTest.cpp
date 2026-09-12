// tests/OscamCasPluginTest.cpp
//
// Tests unitarios para el plugin CAS agnóstico (Fase 3).

#include <gtest/gtest.h>
#include "../hal/native/include/OscamCasPlugin.h"
#include "../hal/native/include/OscamCasService.h"
#include "../chipset/include/IChipsetAdapter.h"
#include "../bridge/include/DvbapiClient.h"

using namespace oscam::hal;
using namespace oscam::chipset;

namespace {

class MockChipsetAdapter : public IChipsetAdapter {
public:
    ChipsetType getChipsetType() const noexcept override { return ChipsetType::kAmlogic; }
    std::string getChipsetName() const noexcept override { return "MockChipset"; }
    bool registerCasSystemId(uint16_t caSystemId) override {
        registeredCaid = caSystemId;
        return true;
    }
    int32_t getEcmPidFromDemux(int32_t, uint16_t) override { return 0x0555; }
    bool injectControlWord(const KeyInjectionParams& params) override {
        lastInjectedParams = params;
        injectedCount++;
        return injectSuccess;
    }
    std::string getDeviceNodePath() const noexcept override { return "/dev/mock"; }
    std::string getVendorPropertyPath() const noexcept override { return "vendor.mock"; }
    bool initialize() override { return true; }
    void release() override {}

    uint16_t registeredCaid{0};
    KeyInjectionParams lastInjectedParams{};
    int injectedCount{0};
    bool injectSuccess{true};
};

class MockPluginListener : public IOscamPluginListener {
public:
    void onControlWordReady(int32_t sessionHandle, const std::vector<uint8_t>& cw) override {
        lastSession = sessionHandle;
        lastCw = cw;
        cwReadyCalled++;
    }
    void onSessionError(int32_t sessionHandle, CasStatus error) override {
        lastErrorSession = sessionHandle;
        lastError = error;
        errorCalled++;
    }

    int32_t lastSession{-1};
    std::vector<uint8_t> lastCw{};
    int cwReadyCalled{0};

    int32_t lastErrorSession{-1};
    CasStatus lastError{CasStatus::kOk};
    int errorCalled{0};
};

} // namespace

TEST(OscamCasPluginTest, OpenAndCloseSessionLifecycle) {
    auto mockChipset = std::make_shared<MockChipsetAdapter>();
    auto mockListener = std::make_shared<MockPluginListener>();

    OscamCasPlugin plugin(0x0604, mockListener, mockChipset, nullptr);

    EXPECT_EQ(mockChipset->registeredCaid, 0x0604);

    int32_t sessionHandle = plugin.openSession();
    EXPECT_GT(sessionHandle, 0);

    EXPECT_TRUE(plugin.closeSession(sessionHandle));
    EXPECT_FALSE(plugin.closeSession(sessionHandle)); // Cerrar 2 veces debe dar false
}

TEST(OscamCasPluginTest, HandleControlWordInjectsAndNotifiesListener) {
    auto mockChipset = std::make_shared<MockChipsetAdapter>();
    auto mockListener = std::make_shared<MockPluginListener>();

    OscamCasPlugin plugin(0x1801, mockListener, mockChipset, nullptr);
    int32_t session = plugin.openSession();

    oscam::dvbapi::CaDescr descr{};
    descr.index = session;
    descr.parity = 0; // even
    const uint8_t testCw[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    std::memcpy(descr.cw, testCw, 8);

    plugin.handleControlWord(descr);

    EXPECT_EQ(mockChipset->injectedCount, 1);
    EXPECT_EQ(mockChipset->lastInjectedParams.sessionHandle, session);
    EXPECT_EQ(mockChipset->lastInjectedParams.parity, 0);
    EXPECT_EQ(std::memcmp(mockChipset->lastInjectedParams.cw, testCw, 8), 0);

    EXPECT_EQ(mockListener->cwReadyCalled, 1);
    EXPECT_EQ(mockListener->lastSession, session);
    ASSERT_EQ(mockListener->lastCw.size(), 8u);
    EXPECT_EQ(mockListener->lastCw[0], 1);

    auto storedCw = plugin.getControlWord(session);
    EXPECT_GE(storedCw.size(), 8u);
}

TEST(OscamCasPluginTest, ProcessEcmValidatesPayloadSize) {
    auto mockChipset = std::make_shared<MockChipsetAdapter>();
    auto mockListener = std::make_shared<MockPluginListener>();

    OscamCasPlugin plugin(0x0500, mockListener, mockChipset, nullptr);
    int32_t session = plugin.openSession();

    // ECM vacío
    EXPECT_FALSE(plugin.processEcm(session, {}));
    EXPECT_EQ(mockListener->errorCalled, 1);

    // ECM normal
    std::vector<uint8_t> validEcm(128, 0x80);
    EXPECT_TRUE(plugin.processEcm(session, validEcm));
}

TEST(OscamCasServiceTest, ServiceConfigAndSupportedCaids) {
    ServiceConfig cfg;
    cfg.supportedCaids = {0x0604, 0x1801};
    OscamCasService service(cfg);

    EXPECT_TRUE(service.isSystemIdSupported(0x0604));
    EXPECT_TRUE(service.isSystemIdSupported(0x1801));
    EXPECT_FALSE(service.isSystemIdSupported(0x0500));

    service.addSupportedCaid(0x0500);
    EXPECT_TRUE(service.isSystemIdSupported(0x0500));
}

