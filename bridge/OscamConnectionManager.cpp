// bridge/OscamConnectionManager.cpp
//
// Implementation of multi-protocol connection manager for OSCam.
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#include "include/OscamConnectionManager.h"
#include "include/BridgeLogger.h"

#include <chrono>

namespace oscam {

OscamConnectionManager::OscamConnectionManager(OscamClientCallbacks callbacks)
    : callbacks_(std::move(callbacks)) {
}

OscamConnectionManager::~OscamConnectionManager() {
    stop();
}

void OscamConnectionManager::setServers(const std::vector<ServerProfile>& profiles) {
    std::lock_guard<std::mutex> lock(mutex_);
    servers_ = profiles;
    activeIndex_ = 0;
    // Find first enabled primary server
    for (size_t i = 0; i < servers_.size(); ++i) {
        if (servers_[i].enabled && servers_[i].isPrimary) {
            activeIndex_ = i;
            break;
        }
    }
}

std::shared_ptr<IOscamClient> OscamConnectionManager::createClientForProfile(const ServerProfile& profile) {
    OscamClientCallbacks cbs;
    cbs.onControlWord = [this](uint16_t sid, uint8_t parity, const uint8_t* cw, size_t len) {
        stats_.cwReceivedCount++;
        stats_.lastCwTimeMs = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count() & 0xFFFFFFFF);
        if (callbacks_.onControlWord) {
            callbacks_.onControlWord(sid, parity, cw, len);
        }
    };

    cbs.onConnectionChanged = [this](bool conn) {
        connected_ = conn;
        if (callbacks_.onConnectionChanged) {
            callbacks_.onConnectionChanged(conn);
        }
    };

    cbs.onError = [this](const std::string& err) {
        {
            std::lock_guard<std::mutex> lk(mutex_);
            lastError_ = err;
        }
        BRIDGE_LOGE("OscamConnectionManager: Error from active client: %s", err.c_str());
        if (callbacks_.onError) {
            callbacks_.onError(err);
        }
    };

    cbs.onServerInfo = [this](const std::string& info) {
        if (callbacks_.onServerInfo) {
            callbacks_.onServerInfo(info);
        }
    };

    switch (profile.protocol) {
        case ProtocolType::DVBAPI_TCP:
        case ProtocolType::DVBAPI_UNIX: {
            dvbapi::ConnectionConfig cfg;
            cfg.host = profile.host;
            cfg.port = profile.port;
            cfg.connectTimeoutSec = profile.connectTimeoutSec;
            cfg.recvTimeoutSec = profile.recvTimeoutSec;
            cfg.reconnectIntervalMs = profile.reconnectIntervalMs;
            cfg.isUnixSocket = (profile.protocol == ProtocolType::DVBAPI_UNIX);

            dvbapi::DvbapiCallbacks dvbCbs;
            dvbCbs.OnCaSetDescr = [cbs](const dvbapi::CaDescr& d) {
                if (cbs.onControlWord) {
                    cbs.onControlWord(static_cast<uint16_t>(d.index), static_cast<uint8_t>(d.parity), d.cw, 8);
                }
            };
            dvbCbs.OnConnectionChanged = cbs.onConnectionChanged;
            dvbCbs.OnFatalError = cbs.onError;
            return std::make_shared<dvbapi::DvbapiClient>(cfg, dvbCbs);
        }
        case ProtocolType::CS378X: {
            cs378x::Cs378xConfig cfg;
            cfg.host = profile.host;
            cfg.port = profile.port;
            cfg.user = profile.user;
            cfg.password = profile.password;
            cfg.caid = profile.caid;
            cfg.connectTimeoutSec = profile.connectTimeoutSec;
            cfg.recvTimeoutSec = profile.recvTimeoutSec;
            cfg.reconnectIntervalMs = profile.reconnectIntervalMs;
            return std::make_shared<cs378x::Cs378xClient>(cfg, cbs);
        }
        case ProtocolType::RADEGAST: {
            radegast::RadegastConfig cfg;
            cfg.host = profile.host;
            cfg.port = profile.port;
            cfg.caid = profile.caid;
            cfg.connectTimeoutSec = profile.connectTimeoutSec;
            cfg.recvTimeoutSec = profile.recvTimeoutSec;
            cfg.reconnectIntervalMs = profile.reconnectIntervalMs;
            return std::make_shared<radegast::RadegastClient>(cfg, cbs);
        }
        case ProtocolType::NEWCAMD: {
            newcamd::NewcamdConfig cfg;
            cfg.host = profile.host;
            cfg.port = profile.port;
            cfg.user = profile.user;
            cfg.password = profile.password;
            cfg.desKey = newcamd::NewcamdClient::parseDesKeyHex(profile.desKey);
            cfg.caid = profile.caid;
            cfg.connectTimeoutSec = profile.connectTimeoutSec;
            cfg.recvTimeoutSec = profile.recvTimeoutSec;
            cfg.reconnectIntervalMs = profile.reconnectIntervalMs;

            newcamd::NewcamdCallbacks nCbs;
            nCbs.onControlWord = cbs.onControlWord;
            nCbs.onConnectionChanged = cbs.onConnectionChanged;
            nCbs.onError = cbs.onError;
            return std::make_shared<newcamd::NewcamdClient>(cfg, nCbs);
        }
        case ProtocolType::CCCAM: {
            cccam::CCcamConfig cfg;
            cfg.host = profile.host;
            cfg.port = profile.port;
            cfg.user = profile.user;
            cfg.password = profile.password;
            cfg.caid = profile.caid;
            cfg.connectTimeoutSec = profile.connectTimeoutSec;
            cfg.recvTimeoutSec = profile.recvTimeoutSec;
            cfg.reconnectIntervalMs = profile.reconnectIntervalMs;

            cccam::CCcamCallbacks cCbs;
            cCbs.onControlWord = cbs.onControlWord;
            cCbs.onConnectionChanged = cbs.onConnectionChanged;
            cCbs.onError = cbs.onError;
            return std::make_shared<cccam::CCcamClient>(cfg, cCbs);
        }
        default:
            return nullptr;
    }
}

bool OscamConnectionManager::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (running_.exchange(true)) return true;

    if (servers_.empty()) {
        ServerProfile defaultProfile;
        servers_.push_back(defaultProfile);
    }

    const auto& profile = servers_[activeIndex_];
    BRIDGE_LOGI("OscamConnectionManager: Starting client for '%s' (%s at %s:%u)",
                profile.name.c_str(), protocolTypeToString(profile.protocol),
                profile.host.c_str(), profile.port);

    activeClient_ = createClientForProfile(profile);
    if (!activeClient_) {
        lastError_ = "Failed to create client for protocol: " + std::string(protocolTypeToString(profile.protocol));
        running_ = false;
        return false;
    }

    return activeClient_->start();
}

void OscamConnectionManager::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!running_.exchange(false)) return;

    if (activeClient_) {
        activeClient_->stop();
        activeClient_.reset();
    }
    connected_ = false;
    BRIDGE_LOGI("OscamConnectionManager: Stopped all clients.");
}

bool OscamConnectionManager::isConnected() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return activeClient_ ? activeClient_->isConnected() : false;
}

ProtocolType OscamConnectionManager::getActiveProtocol() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (activeClient_) return activeClient_->getProtocolType();
    if (activeIndex_ < servers_.size()) return servers_[activeIndex_].protocol;
    return ProtocolType::DVBAPI_TCP;
}

std::string OscamConnectionManager::getActiveServerDescription() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (activeIndex_ < servers_.size()) {
        const auto& p = servers_[activeIndex_];
        return p.name + " [" + protocolTypeToString(p.protocol) + "] " + p.host + ":" + std::to_string(p.port);
    }
    return "No server configured";
}

bool OscamConnectionManager::sendEcm(uint16_t serviceId, uint16_t caid, uint32_t providerId,
                                     const uint8_t* ecmData, size_t length) {
    stats_.ecmSentCount++;
    std::shared_ptr<IOscamClient> client;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        client = activeClient_;
    }

    if (!client || !client->isConnected()) {
        return false;
    }
    return client->sendEcm(serviceId, caid, providerId, ecmData, length);
}

bool OscamConnectionManager::failoverNext() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (servers_.size() <= 1) return false;

    stats_.failoverCount++;
    size_t nextIndex = (activeIndex_ + 1) % servers_.size();
    while (nextIndex != activeIndex_ && !servers_[nextIndex].enabled) {
        nextIndex = (nextIndex + 1) % servers_.size();
    }

    if (nextIndex == activeIndex_) {
        BRIDGE_LOGW("OscamConnectionManager: No other enabled servers for failover.");
        return false;
    }

    BRIDGE_LOGI("OscamConnectionManager: Failing over from server #%zu to #%zu ('%s')",
                activeIndex_, nextIndex, servers_[nextIndex].name.c_str());

    if (activeClient_) {
        activeClient_->stop();
        activeClient_.reset();
    }

    activeIndex_ = nextIndex;
    activeClient_ = createClientForProfile(servers_[activeIndex_]);
    if (activeClient_) {
        return activeClient_->start();
    }
    return false;
}

bool OscamConnectionManager::testServer(const ServerProfile& profile, int timeoutMs, std::string& outResult) {
    switch (profile.protocol) {
        case ProtocolType::DVBAPI_TCP:
        case ProtocolType::DVBAPI_UNIX: {
            dvbapi::ConnectionConfig cfg;
            cfg.host = profile.host;
            cfg.port = profile.port;
            cfg.connectTimeoutSec = std::max(1, timeoutMs / 1000);
            cfg.maxReconnectAttempts = 1;
            cfg.isUnixSocket = (profile.protocol == ProtocolType::DVBAPI_UNIX);

            dvbapi::DvbapiCallbacks cbs;
            auto client = std::make_shared<dvbapi::DvbapiClient>(cfg, cbs);
            client->start();
            std::this_thread::sleep_for(std::chrono::milliseconds(std::min(timeoutMs, 2000)));
            bool ok = client->isConnected();
            client->stop();
            outResult = ok ? "DVBAPI connection successful" : "DVBAPI connection failed or timed out";
            return ok;
        }
        case ProtocolType::CS378X: {
            std::string err;
            bool ok = cs378x::Cs378xClient::testConnection(profile.host, profile.port, profile.user, profile.password, timeoutMs, err);
            outResult = ok ? "Cs378x handshake OK" : err;
            return ok;
        }
        case ProtocolType::RADEGAST: {
            std::string err;
            bool ok = radegast::RadegastClient::testConnection(profile.host, profile.port, timeoutMs, err);
            outResult = ok ? "Radegast port reachable" : err;
            return ok;
        }
        case ProtocolType::NEWCAMD: {
            std::string err;
            auto desKey = newcamd::NewcamdClient::parseDesKeyHex(profile.desKey);
            bool ok = newcamd::NewcamdClient::testConnection(profile.host, profile.port, profile.user, profile.password, desKey, timeoutMs, err);
            outResult = ok ? "Newcamd login successful" : err;
            return ok;
        }
        case ProtocolType::CCCAM: {
            std::string err;
            bool ok = cccam::CCcamClient::testConnection(profile.host, profile.port, profile.user, profile.password, timeoutMs, err);
            outResult = ok ? "CCcam handshake OK" : err;
            return ok;
        }
        case ProtocolType::OSCAM_WEBIF: {
            webif::WebIfConfig cfg;
            cfg.host = profile.host;
            cfg.port = profile.port;
            cfg.user = profile.user;
            cfg.password = profile.password;
            cfg.timeoutSec = std::max(1, timeoutMs / 1000);
            webif::OscamWebIfClient wClient(cfg);
            std::string ver;
            bool ok = wClient.ping(ver);
            outResult = ok ? ("WebIF OK: " + ver) : "Could not reach OSCam WebIF";
            return ok;
        }
    }
    outResult = "Unsupported protocol";
    return false;
}

std::string OscamConnectionManager::getLastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
}

} // namespace oscam

