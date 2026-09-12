// jni/com_oscam_cas_OscamCasPlugin.cpp
//
// JNI implementation bridging Android TV Kotlin layer with native C++ engine.
// Provides multi-protocol OSCam support (DVBAPI TCP/UNIX, Cs378x, Radegast, Newcamd, CCcam, WebIF).
// Provides dual namespace JNI exports for both:
//   - .OscamNativeBridge
//   - com.oscam.cas.OscamNativeBridge
//
// Author: Eneko Lizarraga (eneko@lizarraga.eus)
// License: CC BY-NC-SA 4.0 (Non-commercial, Attribution Required)

#include "NativeBridge.h"
#include "../bridge/include/BridgeLogger.h"
#include "../bridge/include/SatellitePmtParser.h"

#include <chrono>
#include <cstring>
#include <sstream>
#include <vector>

#ifdef _WIN32
#  include <winsock2.h>
#  include <ws2tcpip.h>
#else
#  include <arpa/inet.h>
#  include <fcntl.h>
#  include <netdb.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <sys/select.h>
#  include <sys/socket.h>
#  include <unistd.h>
#  define INVALID_SOCKET (-1)
#  define SOCKET_ERROR   (-1)
#  define closesocket(s) ::close(s)
#endif

namespace oscam::jni {

NativeBridge::NativeBridge() {
    softwareDescrambler_ = std::make_unique<bridge::SoftwareDescrambler>();
}

NativeBridge& NativeBridge::getInstance() {
    static NativeBridge instance;
    return instance;
}

NativeBridge::~NativeBridge() {
    stop();
}

bool NativeBridge::initialize(const std::string& host, uint16_t port, const std::vector<uint16_t>& supportedCaids) {
    return initializeEx(host, port, static_cast<uint8_t>(ProtocolType::DVBAPI_TCP), "android_tv", "android_tv",
                        "0102030405060708091011121314", supportedCaids);
}

bool NativeBridge::initializeEx(const std::string& host, uint16_t port, uint8_t protocol,
                               const std::string& user, const std::string& password,
                               const std::string& desKey, const std::vector<uint16_t>& supportedCaids) {
    std::lock_guard<std::mutex> lock(mutex_);
    host_ = host;
    port_ = port;
    protocol_ = protocol;
    user_ = user;
    password_ = password;
    desKey_ = desKey;
    supportedCaids_ = supportedCaids;

    BRIDGE_LOGI("NativeBridge::initializeEx -> Host: %s, Port: %u, Proto: %u, CAIDs: %zu",
                host.c_str(), port, protocol, supportedCaids.size());

    OscamClientCallbacks cbs;
    cbs.onConnectionChanged = [this](bool connected) {
        ConnectionState state = connected ? ConnectionState::Connected : ConnectionState::Connecting;
        connectionState_ = state;
        BRIDGE_LOGI("NativeBridge: Connection status changed to: %s", connected ? "CONNECTED" : "CONNECTING...");
        notifyJavaConnectionChanged(state);
    };

    cbs.onControlWord = [this](uint16_t serviceId, uint8_t parity, const uint8_t* cw, size_t length) {
        stats_.cwReceivedCount++;
        stats_.lastCwTimeMs = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count() & 0xFFFFFFFF);

        BRIDGE_LOGD("NativeBridge: CW received for SID 0x%04X (parity: %u, len: %zu)",
                    serviceId, parity, length);

        if (softwareDescrambler_ && length >= 8) {
            std::array<uint8_t, 8> cw8{};
            std::memcpy(cw8.data(), cw, 8);
            softwareDescrambler_->setControlWord(serviceId, parity, cw8);
        }

        std::vector<uint8_t> cwVec(cw, cw + length);
        notifyJavaCwReceived(static_cast<int32_t>(serviceId), cwVec);
    };

    cbs.onError = [this](const std::string& reason) {
        {
            std::lock_guard<std::mutex> lk(mutex_);
            lastError_ = reason;
        }
        connectionState_ = ConnectionState::Error;
        BRIDGE_LOGE("NativeBridge: OSCam client error: %s", reason.c_str());
        notifyJavaConnectionChanged(ConnectionState::Error);
    };

    connManager_ = std::make_shared<OscamConnectionManager>(cbs);

    ServerProfile profile;
    profile.name = "Active Server";
    profile.protocol = static_cast<ProtocolType>(protocol_);
    profile.host = host_;
    profile.port = port_;
    profile.user = user_;
    profile.password = password_;
    profile.desKey = desKey_;
    profile.caid = supportedCaids_.empty() ? 0x1810 : supportedCaids_[0];
    profile.enabled = true;
    profile.isPrimary = true;

    connManager_->setServers({profile});
    return true;
}

bool NativeBridge::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!connManager_) {
        lastError_ = "Connection manager is not initialized.";
        BRIDGE_LOGE("NativeBridge::start -> %s", lastError_.c_str());
        return false;
    }
    connectionState_ = ConnectionState::Connecting;
    notifyJavaConnectionChanged(ConnectionState::Connecting);
    bool ok = connManager_->start();
    BRIDGE_LOGI("NativeBridge::start -> ConnectionManager start result: %d", ok ? 1 : 0);
    return ok;
}

void NativeBridge::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (connManager_) {
        connManager_->stop();
        connManager_.reset();
    }
    connectionState_ = ConnectionState::Disconnected;
    notifyJavaConnectionChanged(ConnectionState::Disconnected);
    BRIDGE_LOGI("NativeBridge::stop -> ConnectionManager stopped.");
}

bool NativeBridge::testConnection(const std::string& host, uint16_t port, int32_t timeoutMs) {
    std::string outRes;
    return testConnectionEx(host, port, static_cast<uint8_t>(ProtocolType::DVBAPI_TCP),
                            "android_tv", "android_tv", "0102030405060708091011121314",
                            timeoutMs, outRes);
}

bool NativeBridge::testConnectionEx(const std::string& host, uint16_t port, uint8_t protocol,
                                   const std::string& user, const std::string& password,
                                   const std::string& desKey, int32_t timeoutMs, std::string& outResult) {
    ServerProfile profile;
    profile.host = host;
    profile.port = port;
    profile.protocol = static_cast<ProtocolType>(protocol);
    profile.user = user;
    profile.password = password;
    profile.desKey = desKey;

    bool ok = OscamConnectionManager::testServer(profile, timeoutMs, outResult);
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ok) {
        lastError_ = outResult;
    } else {
        lastError_.clear();
    }
    return ok;
}

std::string NativeBridge::queryWebIfStatus(const std::string& host, uint16_t port,
                                          const std::string& user, const std::string& password) {
    webif::WebIfConfig cfg;
    cfg.host = host;
    cfg.port = port;
    cfg.user = user;
    cfg.password = password;
    cfg.timeoutSec = 3;

    webif::OscamWebIfClient client(cfg);
    webif::OscamServerStatus status;
    if (client.queryStatus(status)) {
        std::ostringstream ss;
        ss << "Status: OK | Version: " << status.version;
        if (!status.activeCaid.empty()) ss << " | CAID: " << status.activeCaid;
        if (!status.activeReader.empty()) ss << " | Reader: " << status.activeReader;
        if (status.lastEcmTimeMs > 0) ss << " | Time: " << status.lastEcmTimeMs << "ms";
        return ss.str();
    }
    return "WebIF: Offline / Unreachable";
}

bool NativeBridge::failoverNext() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (connManager_) {
        return connManager_->failoverNext();
    }
    return false;
}

std::string NativeBridge::getActiveServerDescription() const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (connManager_) {
        return connManager_->getActiveServerDescription();
    }
    return "No active connection manager";
}

size_t NativeBridge::descrambleBuffer(uint8_t* buffer, size_t size) {
    if (!softwareDescrambler_ || !buffer) return 0;
    return softwareDescrambler_->descrambleBuffer(buffer, size);
}

void NativeBridge::setSoftwareCw(uint16_t pid, int parity, const uint8_t* cw) {
    if (!softwareDescrambler_ || !cw) return;
    std::array<uint8_t, 8> key{};
    std::memcpy(key.data(), cw, 8);
    softwareDescrambler_->setControlWord(pid, parity, key);
}

ConnectionState NativeBridge::getConnectionState() const noexcept {
    return connectionState_.load();
}

std::string NativeBridge::getLastError() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return lastError_;
}

void NativeBridge::setJavaCallback(JavaVM* vm, jobject globalCallbackRef) {
    std::lock_guard<std::mutex> lock(mutex_);
    jvm_ = vm;
    javaCallbackRef_ = globalCallbackRef;

    if (jvm_ && javaCallbackRef_) {
        JNIEnv* env = nullptr;
        if (jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            jclass cls = env->GetObjectClass(javaCallbackRef_);
            onStateChangedMethod_ = env->GetMethodID(cls, "onConnectionStateChanged", "(I)V");
            onCwReceivedMethod_ = env->GetMethodID(cls, "onControlWordReceived", "(I[B)V");
        }
    }
}

void NativeBridge::clearJavaCallback(JNIEnv* env) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (env && javaCallbackRef_) {
        env->DeleteGlobalRef(javaCallbackRef_);
        javaCallbackRef_ = nullptr;
    }
    onStateChangedMethod_ = nullptr;
    onCwReceivedMethod_ = nullptr;
}

void NativeBridge::notifyJavaConnectionChanged(ConnectionState state) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!jvm_ || !javaCallbackRef_ || !onStateChangedMethod_) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    jint getEnvRes = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

    if (getEnvRes == JNI_EDETACHED) {
#if defined(ANDROID) || defined(__ANDROID__)
        if (jvm_->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        }
#else
        if (jvm_->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) == JNI_OK) {
            attached = true;
        }
#endif
    }

    if (env && onStateChangedMethod_) {
        env->CallVoidMethod(javaCallbackRef_, onStateChangedMethod_, static_cast<jint>(state));
    }

    if (attached) {
        jvm_->DetachCurrentThread();
    }
}

void NativeBridge::notifyJavaCwReceived(int32_t sessionHandle, const std::vector<uint8_t>& cw) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!jvm_ || !javaCallbackRef_ || !onCwReceivedMethod_) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    jint getEnvRes = jvm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

    if (getEnvRes == JNI_EDETACHED) {
#if defined(ANDROID) || defined(__ANDROID__)
        if (jvm_->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        }
#else
        if (jvm_->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) == JNI_OK) {
            attached = true;
        }
#endif
    }

    if (env && onCwReceivedMethod_) {
        jbyteArray byteArray = env->NewByteArray(static_cast<jsize>(cw.size()));
        if (byteArray) {
            env->SetByteArrayRegion(byteArray, 0, static_cast<jsize>(cw.size()),
                                    reinterpret_cast<const jbyte*>(cw.data()));
            env->CallVoidMethod(javaCallbackRef_, onCwReceivedMethod_, sessionHandle, byteArray);
            env->DeleteLocalRef(byteArray);
        }
    }

    if (attached) {
        jvm_->DetachCurrentThread();
    }
}

} // namespace oscam::jni

// ---------------------------------------------------------------------------
// Unified JNI Bridge Implementation Handlers
// ---------------------------------------------------------------------------

static JavaVM* gJavaVM = nullptr;

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gJavaVM = vm;
    BRIDGE_LOGI("JNI_OnLoad initialized successfully (Dual-Package Native Engine)");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        oscam::jni::NativeBridge::getInstance().clearJavaCallback(env);
    }
    gJavaVM = nullptr;
    BRIDGE_LOGI("JNI_OnUnload executed successfully");
}

static jboolean impl_nativeInit(JNIEnv* env, jstring host, jint port, jintArray caids) {
    if (!host) return JNI_FALSE;
    const char* hChars = env->GetStringUTFChars(host, nullptr);
    std::string hostStr(hChars);
    env->ReleaseStringUTFChars(host, hChars);

    std::vector<uint16_t> caidVec;
    if (caids) {
        jsize len = env->GetArrayLength(caids);
        jint* body = env->GetIntArrayElements(caids, nullptr);
        if (body) {
            for (jsize i = 0; i < len; ++i) caidVec.push_back(static_cast<uint16_t>(body[i]));
            env->ReleaseIntArrayElements(caids, body, JNI_ABORT);
        }
    }
    return oscam::jni::NativeBridge::getInstance().initialize(hostStr, static_cast<uint16_t>(port), caidVec) ? JNI_TRUE : JNI_FALSE;
}

static jboolean impl_nativeInitEx(JNIEnv* env, jstring host, jint port, jint protocol,
                                 jstring user, jstring password, jstring desKey, jintArray caids) {
    if (!host) return JNI_FALSE;
    const char* hChars = env->GetStringUTFChars(host, nullptr);
    std::string hostStr(hChars);
    env->ReleaseStringUTFChars(host, hChars);

    std::string userStr = user ? env->GetStringUTFChars(user, nullptr) : "android_tv";
    std::string passStr = password ? env->GetStringUTFChars(password, nullptr) : "android_tv";
    std::string desStr  = desKey ? env->GetStringUTFChars(desKey, nullptr) : "0102030405060708091011121314";

    std::vector<uint16_t> caidVec;
    if (caids) {
        jsize len = env->GetArrayLength(caids);
        jint* body = env->GetIntArrayElements(caids, nullptr);
        if (body) {
            for (jsize i = 0; i < len; ++i) caidVec.push_back(static_cast<uint16_t>(body[i]));
            env->ReleaseIntArrayElements(caids, body, JNI_ABORT);
        }
    }

    bool ok = oscam::jni::NativeBridge::getInstance().initializeEx(
        hostStr, static_cast<uint16_t>(port), static_cast<uint8_t>(protocol),
        userStr, passStr, desStr, caidVec);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static jboolean impl_nativeStart() {
    return oscam::jni::NativeBridge::getInstance().start() ? JNI_TRUE : JNI_FALSE;
}

static void impl_nativeStop() {
    oscam::jni::NativeBridge::getInstance().stop();
}

static jint impl_nativeGetStatus() {
    return static_cast<jint>(oscam::jni::NativeBridge::getInstance().getConnectionState());
}

static jboolean impl_nativeTestConnection(JNIEnv* env, jstring host, jint port, jint timeoutMs) {
    if (!host) return JNI_FALSE;
    const char* h = env->GetStringUTFChars(host, nullptr);
    std::string hostStr(h);
    env->ReleaseStringUTFChars(host, h);
    return oscam::jni::NativeBridge::getInstance().testConnection(hostStr, static_cast<uint16_t>(port), timeoutMs) ? JNI_TRUE : JNI_FALSE;
}

static jstring impl_nativeTestConnectionEx(JNIEnv* env, jstring host, jint port, jint protocol,
                                          jstring user, jstring password, jstring desKey, jint timeoutMs) {
    if (!host) return env->NewStringUTF("Host is null");
    const char* h = env->GetStringUTFChars(host, nullptr);
    std::string hostStr(h);
    env->ReleaseStringUTFChars(host, h);

    std::string userStr = user ? env->GetStringUTFChars(user, nullptr) : "";
    std::string passStr = password ? env->GetStringUTFChars(password, nullptr) : "";
    std::string desStr  = desKey ? env->GetStringUTFChars(desKey, nullptr) : "";

    std::string result;
    oscam::jni::NativeBridge::getInstance().testConnectionEx(
        hostStr, static_cast<uint16_t>(port), static_cast<uint8_t>(protocol),
        userStr, passStr, desStr, timeoutMs, result);
    return env->NewStringUTF(result.c_str());
}

static jstring impl_nativeQueryWebIfStatus(JNIEnv* env, jstring host, jint port, jstring user, jstring password) {
    if (!host) return env->NewStringUTF("Host is null");
    const char* h = env->GetStringUTFChars(host, nullptr);
    std::string hostStr(h);
    env->ReleaseStringUTFChars(host, h);

    std::string userStr = user ? env->GetStringUTFChars(user, nullptr) : "";
    std::string passStr = password ? env->GetStringUTFChars(password, nullptr) : "";

    std::string info = oscam::jni::NativeBridge::getInstance().queryWebIfStatus(
        hostStr, static_cast<uint16_t>(port), userStr, passStr);
    return env->NewStringUTF(info.c_str());
}

static jboolean impl_nativeFailoverNext() {
    return oscam::jni::NativeBridge::getInstance().failoverNext() ? JNI_TRUE : JNI_FALSE;
}

static jstring impl_nativeGetActiveServerDescription(JNIEnv* env) {
    std::string desc = oscam::jni::NativeBridge::getInstance().getActiveServerDescription();
    return env->NewStringUTF(desc.c_str());
}

static jlongArray impl_nativeGetStats(JNIEnv* env) {
    const auto& stats = oscam::jni::NativeBridge::getInstance().getStats();
    jlong statsArray[6];
    statsArray[0] = static_cast<jlong>(stats.ecmSentCount.load());
    statsArray[1] = static_cast<jlong>(stats.cwReceivedCount.load());
    statsArray[2] = static_cast<jlong>(stats.emmSentCount.load());
    statsArray[3] = static_cast<jlong>(stats.lastCwTimeMs.load());
    statsArray[4] = static_cast<jlong>(stats.reconnectCount.load());
    statsArray[5] = static_cast<jlong>(stats.failoverCount.load());

    jlongArray res = env->NewLongArray(6);
    if (res) {
        env->SetLongArrayRegion(res, 0, 6, statsArray);
    }
    return res;
}

static jstring impl_nativeGetLastError(JNIEnv* env) {
    std::string err = oscam::jni::NativeBridge::getInstance().getLastError();
    return env->NewStringUTF(err.c_str());
}

static void impl_nativeRegisterCallback(JNIEnv* env, jobject callback) {
    if (!callback) return;
    jobject gRef = env->NewGlobalRef(callback);
    oscam::jni::NativeBridge::getInstance().setJavaCallback(gJavaVM, gRef);
}

static void impl_nativeUnregisterCallback(JNIEnv* env) {
    oscam::jni::NativeBridge::getInstance().clearJavaCallback(env);
}

static jint impl_nativeDescrambleBuffer(JNIEnv* env, jbyteArray buffer, jint offset, jint length) {
    if (!buffer || length <= 0) return 0;
    jbyte* bufPtr = env->GetByteArrayElements(buffer, nullptr);
    if (!bufPtr) return 0;
    size_t processed = oscam::jni::NativeBridge::getInstance().descrambleBuffer(
        reinterpret_cast<uint8_t*>(bufPtr + offset), static_cast<size_t>(length));
    env->ReleaseByteArrayElements(buffer, bufPtr, 0);
    return static_cast<jint>(processed);
}

static void impl_nativeSetSoftwareCw(JNIEnv* env, jint pid, jint parity, jbyteArray cw) {
    if (!cw) return;
    jsize len = env->GetArrayLength(cw);
    if (len < 8) return;
    jbyte* cwPtr = env->GetByteArrayElements(cw, nullptr);
    if (cwPtr) {
        oscam::jni::NativeBridge::getInstance().setSoftwareCw(
            static_cast<uint16_t>(pid), static_cast<int>(parity),
            reinterpret_cast<const uint8_t*>(cwPtr));
        env->ReleaseByteArrayElements(cw, cwPtr, JNI_ABORT);
    }
}

static jboolean impl_nativeIsPmtScrambled(JNIEnv* env, jbyteArray pmtData) {
    if (!pmtData) return JNI_FALSE;
    jsize len = env->GetArrayLength(pmtData);
    if (len < 16) return JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(pmtData, nullptr);
    if (!bytes) return JNI_FALSE;
    auto optInfo = oscam::bridge::SatellitePmtParser::parsePmtSection(
        reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(pmtData, bytes, JNI_ABORT);
    if (optInfo) {
        return optInfo->isScrambled() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

// ---------------------------------------------------------------------------
// 1. Exports for com.lizarragaeus.oscambridge.OscamNativeBridge
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeInit(
    JNIEnv* env, jobject /*thiz*/, jstring host, jint port, jintArray caids) {
    return impl_nativeInit(env, host, port, caids);
}

JNIEXPORT jboolean JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeInitEx(
    JNIEnv* env, jobject /*thiz*/, jstring host, jint port, jint protocol,
    jstring user, jstring password, jstring desKey, jintArray caids) {
    return impl_nativeInitEx(env, host, port, protocol, user, password, desKey, caids);
}

JNIEXPORT jboolean JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/) {
    return impl_nativeStart();
}

JNIEXPORT void JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    impl_nativeStop();
}

JNIEXPORT jint JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeGetStatus(JNIEnv* /*env*/, jobject /*thiz*/) {
    return impl_nativeGetStatus();
}

JNIEXPORT jboolean JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeTestConnection(
    JNIEnv* env, jobject /*thiz*/, jstring host, jint port, jint timeoutMs) {
    return impl_nativeTestConnection(env, host, port, timeoutMs);
}

JNIEXPORT jstring JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeTestConnectionEx(
    JNIEnv* env, jobject /*thiz*/, jstring host, jint port, jint protocol,
    jstring user, jstring password, jstring desKey, jint timeoutMs) {
    return impl_nativeTestConnectionEx(env, host, port, protocol, user, password, desKey, timeoutMs);
}

JNIEXPORT jstring JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeQueryWebIfStatus(
    JNIEnv* env, jobject /*thiz*/, jstring host, jint port, jstring user, jstring password) {
    return impl_nativeQueryWebIfStatus(env, host, port, user, password);
}

JNIEXPORT jboolean JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeFailoverNext(JNIEnv* /*env*/, jobject /*thiz*/) {
    return impl_nativeFailoverNext();
}

JNIEXPORT jstring JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeGetActiveServerDescription(JNIEnv* env, jobject /*thiz*/) {
    return impl_nativeGetActiveServerDescription(env);
}

JNIEXPORT jlongArray JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeGetStats(JNIEnv* env, jobject /*thiz*/) {
    return impl_nativeGetStats(env);
}

JNIEXPORT jstring JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeGetLastError(JNIEnv* env, jobject /*thiz*/) {
    return impl_nativeGetLastError(env);
}

JNIEXPORT void JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeRegisterCallback(
    JNIEnv* env, jobject /*thiz*/, jobject callback) {
    impl_nativeRegisterCallback(env, callback);
}

JNIEXPORT void JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeUnregisterCallback(JNIEnv* env, jobject /*thiz*/) {
    impl_nativeUnregisterCallback(env);
}

JNIEXPORT jint JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeDescrambleBuffer(
    JNIEnv* env, jobject /*thiz*/, jbyteArray buffer, jint offset, jint length) {
    return impl_nativeDescrambleBuffer(env, buffer, offset, length);
}

JNIEXPORT void JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeSetSoftwareCw(
    JNIEnv* env, jobject /*thiz*/, jint pid, jint parity, jbyteArray cw) {
    impl_nativeSetSoftwareCw(env, pid, parity, cw);
}

JNIEXPORT jboolean JNICALL
Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeIsPmtScrambled(
    JNIEnv* env, jobject /*thiz*/, jbyteArray pmtData) {
    return impl_nativeIsPmtScrambled(env, pmtData);
}

// ---------------------------------------------------------------------------
// 2. Backward compatibility exports for com.oscam.cas.OscamNativeBridge
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeInit(
    JNIEnv* env, jobject thiz, jstring host, jint port, jintArray caids) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeInit(env, thiz, host, port, caids);
}

JNIEXPORT jboolean JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeInitEx(
    JNIEnv* env, jobject thiz, jstring host, jint port, jint protocol,
    jstring user, jstring password, jstring desKey, jintArray caids) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeInitEx(env, thiz, host, port, protocol, user, password, desKey, caids);
}

JNIEXPORT jboolean JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeStart(JNIEnv* env, jobject thiz) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeStart(env, thiz);
}

JNIEXPORT void JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeStop(JNIEnv* env, jobject thiz) {
    Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeStop(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeGetStatus(JNIEnv* env, jobject thiz) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeGetStatus(env, thiz);
}

JNIEXPORT jboolean JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeTestConnection(
    JNIEnv* env, jobject thiz, jstring host, jint port, jint timeoutMs) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeTestConnection(env, thiz, host, port, timeoutMs);
}

JNIEXPORT jstring JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeTestConnectionEx(
    JNIEnv* env, jobject thiz, jstring host, jint port, jint protocol,
    jstring user, jstring password, jstring desKey, jint timeoutMs) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeTestConnectionEx(env, thiz, host, port, protocol, user, password, desKey, timeoutMs);
}

JNIEXPORT jstring JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeQueryWebIfStatus(
    JNIEnv* env, jobject thiz, jstring host, jint port, jstring user, jstring password) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeQueryWebIfStatus(env, thiz, host, port, user, password);
}

JNIEXPORT jboolean JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeFailoverNext(JNIEnv* env, jobject thiz) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeFailoverNext(env, thiz);
}

JNIEXPORT jstring JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeGetActiveServerDescription(JNIEnv* env, jobject thiz) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeGetActiveServerDescription(env, thiz);
}

JNIEXPORT jlongArray JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeGetStats(JNIEnv* env, jobject thiz) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeGetStats(env, thiz);
}

JNIEXPORT jstring JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeGetLastError(JNIEnv* env, jobject thiz) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeGetLastError(env, thiz);
}

JNIEXPORT void JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeRegisterCallback(
    JNIEnv* env, jobject thiz, jobject callback) {
    Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeRegisterCallback(env, thiz, callback);
}

JNIEXPORT void JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeUnregisterCallback(JNIEnv* env, jobject thiz) {
    Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeUnregisterCallback(env, thiz);
}

JNIEXPORT jint JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeDescrambleBuffer(
    JNIEnv* env, jobject thiz, jbyteArray buffer, jint offset, jint length) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeDescrambleBuffer(env, thiz, buffer, offset, length);
}

JNIEXPORT void JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeSetSoftwareCw(
    JNIEnv* env, jobject thiz, jint pid, jint parity, jbyteArray cw) {
    Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeSetSoftwareCw(env, thiz, pid, parity, cw);
}

JNIEXPORT jboolean JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeIsPmtScrambled(
    JNIEnv* env, jobject thiz, jbyteArray pmtData) {
    return Java_com_lizarragaeus_oscambridge_OscamNativeBridge_nativeIsPmtScrambled(env, thiz, pmtData);
}

} // extern "C"
