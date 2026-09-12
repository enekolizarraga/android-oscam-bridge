// tests/OscamConnectionManagerTest.cpp
//
// Unit tests for OscamConnectionManager multi-protocol orchestration and profile routing.
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#include <gtest/gtest.h>
#include "OscamConnectionManager.h"

using namespace oscam;

TEST(OscamConnectionManagerTest, ProfileSetupAndProtocolInspection) {
    OscamClientCallbacks cbs;
    OscamConnectionManager manager(cbs);

    ServerProfile p1;
    p1.name = "OSCam DVBAPI Local";
    p1.protocol = ProtocolType::DVBAPI_UNIX;
    p1.host = "/tmp/camd.socket";
    p1.port = 0;
    p1.isPrimary = true;
    p1.enabled = true;

    ServerProfile p2;
    p2.name = "OSCam Cs378x LAN";
    p2.protocol = ProtocolType::CS378X;
    p2.host = "192.168.1.50";
    p2.port = 13000;
    p2.isPrimary = false;
    p2.enabled = true;

    manager.setServers({p1, p2});

    EXPECT_EQ(manager.getActiveProtocol(), ProtocolType::DVBAPI_UNIX);
    EXPECT_FALSE(manager.isConnected());
}

TEST(OscamConnectionManagerTest, FailoverCycling) {
    OscamClientCallbacks cbs;
    OscamConnectionManager manager(cbs);

    ServerProfile p1;
    p1.name = "Primary Radegast";
    p1.protocol = ProtocolType::RADEGAST;
    p1.host = "192.168.1.10";
    p1.port = 678;
    p1.enabled = true;
    p1.isPrimary = true;

    ServerProfile p2;
    p2.name = "Secondary Newcamd";
    p2.protocol = ProtocolType::NEWCAMD;
    p2.host = "192.168.1.11";
    p2.port = 10000;
    p2.enabled = true;
    p2.isPrimary = false;

    manager.setServers({p1, p2});
    EXPECT_EQ(manager.getActiveProtocol(), ProtocolType::RADEGAST);

    bool failoverOk = manager.failoverNext();
    EXPECT_TRUE(failoverOk);
    EXPECT_EQ(manager.getActiveProtocol(), ProtocolType::NEWCAMD);
}

