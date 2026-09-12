// jni/com_oscam_cas_OscamCasPlugin.cpp
//
// JNI implementation bridging Android TV Kotlin layer with native C++ engine.
// Maps UI/Service requests to DvbapiClient, feeds software stream descrambler,
// and dispatches asynchronous callbacks to the JVM.
//
// Author: android-oscam-bridge

#include "NativeBridge.h"
#include "../bridge/include/BridgeLogger.h"

#include <chrono>
#include <cstring>
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
    std::lock_guard<std::mutex> lock(mutex_);
    host_ = host;
    port_ = port;
    supportedCaids_ = supportedCaids;

    BRIDGE_LOGI("NativeBridge::initialize -> Host: %s, Port: %u, CAIDs: %zu",
                host.c_str(), port, supportedCaids.size());

    dvbapi::ConnectionConfig cfg;
    cfg.host = host_;
    cfg.port = port_;
    cfg.connectTimeoutSec = 4;
    cfg.recvTimeoutSec = 8;
    cfg.maxReconnectAttempts = 0; // Continuous reconnection with backoff
    cfg.initialBackoffMs = 1000;
    cfg.maxBackoffMs = 30000;

    dvbapi::DvbapiCallbacks cbs;
    cbs.OnConnectionChanged = [this](bool connected) {
        ConnectionState state = connected ? ConnectionState::Connected : ConnectionState::Connecting;
        connectionState_ = state;
        BRIDGE_LOGI("NativeBridge: Connection status changed to: %s", connected ? "CONNECTED" : "CONNECTING...");
        notifyJavaConnectionChanged(state);
    };

    cbs.OnCaSetDescr = [this](const dvbapi::CaDescr& descr) {
        stats_.cwReceivedCount++;
        stats_.lastCwTimeMs = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count() & 0xFFFFFFFF);

        BRIDGE_LOGD("NativeBridge: CW received for index %u (parity: %u, length: %zu)",
                    descr.index, descr.parity, descr.cw.size());

        // Update software descrambler for external streams/recordings
        if (softwareDescrambler_ && descr.cw.size() >= 8) {
            std::array<uint8_t, 8> cw8{};
            std::memcpy(cw8.data(), descr.cw.data(), 8);
            softwareDescrambler_->setControlWord(static_cast<uint16_t>(descr.index), descr.parity, cw8);
        }

        notifyJavaCwReceived(static_cast<int32_t>(descr.index), descr.cw);
    };

    cbs.OnFatalError = [this](const std::string& reason) {
        {
            std::lock_guard<std::mutex> lk(mutex_);
            lastError_ = reason;
        }
        connectionState_ = ConnectionState::Error;
        BRIDGE_LOGE("NativeBridge: Fatal dvbapi error: %s", reason.c_str());
        notifyJavaConnectionChanged(ConnectionState::Error);
    };

    dvbapiClient_ = std::make_shared<dvbapi::DvbapiClient>(cfg, std::move(cbs));
    return true;
}

bool NativeBridge::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!dvbapiClient_) {
        lastError_ = "Dvbapi client is not initialized.";
        BRIDGE_LOGE("NativeBridge::start -> %s", lastError_.c_str());
        return false;
    }
    connectionState_ = ConnectionState::Connecting;
    notifyJavaConnectionChanged(ConnectionState::Connecting);
    dvbapiClient_->start();
    BRIDGE_LOGI("NativeBridge::start -> Client started.");
    return true;
}

void NativeBridge::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (dvbapiClient_) {
        dvbapiClient_->stop();
        dvbapiClient_.reset();
    }
    connectionState_ = ConnectionState::Disconnected;
    notifyJavaConnectionChanged(ConnectionState::Disconnected);
    BRIDGE_LOGI("NativeBridge::stop -> Client stopped.");
}

bool NativeBridge::testConnection(const std::string& host, uint16_t port, int32_t timeoutMs) {
    BRIDGE_LOGI("NativeBridge::testConnection testing %s:%u (timeout %d ms)...",
                host.c_str(), port, timeoutMs);

    struct addrinfo hints;
    std::memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    struct addrinfo* res = nullptr;
    std::string portStr = std::to_string(port);
    if (::getaddrinfo(host.c_str(), portStr.c_str(), &hints, &res) != 0 || res == nullptr) {
        std::lock_guard<std::mutex> lock(mutex_);
        lastError_ = "Could not resolve host: " + host;
        BRIDGE_LOGE("testConnection: getaddrinfo failed for %s", host.c_str());
        return false;
    }

#ifdef _WIN32
    SOCKET sock = ::socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (sock == INVALID_SOCKET) {
        ::freeaddrinfo(res);
        return false;
    }
    u_long nonBlocking = 1;
    ::ioctlsocket(sock, FIONBIO, &nonBlocking);
#else
    int sock = ::socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (sock < 0) {
        ::freeaddrinfo(res);
        return false;
    }
    int flags = ::fcntl(sock, F_GETFL, 0);
    ::fcntl(sock, F_SETFL, flags | O_NONBLOCK);
#endif

    int connRes = ::connect(sock, res->ai_addr, static_cast<int>(res->ai_addrlen));
    ::freeaddrinfo(res);

    bool success = false;
    if (connRes == 0) {
        success = true;
    } else {
        fd_set writeFds;
        FD_ZERO(&writeFds);
#if defined(_MSC_VER)
#  pragma warning(push)
#  pragma warning(disable: 4548)
#endif
        FD_SET(sock, &writeFds);
#if defined(_MSC_VER)
#  pragma warning(pop)
#endif

        struct timeval tv;
        tv.tv_sec = timeoutMs / 1000;
        tv.tv_usec = (timeoutMs % 1000) * 1000;

        int sel = ::select(static_cast<int>(sock + 1), nullptr, &writeFds, nullptr, &tv);
        if (sel > 0 && FD_ISSET(sock, &writeFds)) {
            int sockErr = 0;
#ifdef _WIN32
            int errLen = sizeof(sockErr);
            ::getsockopt(sock, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&sockErr), &errLen);
#else
            socklen_t errLen = sizeof(sockErr);
            ::getsockopt(sock, SOL_SOCKET, SO_ERROR, &sockErr, &errLen);
#endif
            success = (sockErr == 0);
        }
    }

    closesocket(sock);

    std::lock_guard<std::mutex> lock(mutex_);
    if (!success) {
        lastError_ = "Connection refused or timeout reached.";
    } else {
        lastError_.clear();
    }
    BRIDGE_LOGI("NativeBridge::testConnection result: %s", success ? "OK" : "FAILED");
    return success;
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
// JNI Exports for com.oscam.cas.OscamNativeBridge
// ---------------------------------------------------------------------------

extern "C" {

static JavaVM* gJavaVM = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    gJavaVM = vm;
    BRIDGE_LOGI("JNI_OnLoad initialized successfully");
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

JNIEXPORT jboolean JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeInit(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring host,
    jint port,
    jintArray caids) {
    if (!host) {
        return JNI_FALSE;
    }

    const char* hostChars = env->GetStringUTFChars(host, nullptr);
    std::string hostStr(hostChars);
    env->ReleaseStringUTFChars(host, hostChars);

    std::vector<uint16_t> caidVec;
    if (caids) {
        jsize len = env->GetArrayLength(caids);
        jint* body = env->GetIntArrayElements(caids, nullptr);
        if (body) {
            for (jsize i = 0; i < len; ++i) {
                caidVec.push_back(static_cast<uint16_t>(body[i]));
            }
            env->ReleaseIntArrayElements(caids, body, JNI_ABORT);
        }
    }

    bool res = oscam::jni::NativeBridge::getInstance().initialize(hostStr, static_cast<uint16_t>(port), caidVec);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeStart(JNIEnv* /*env*/, jobject /*thiz*/) {
    return oscam::jni::NativeBridge::getInstance().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeStop(JNIEnv* /*env*/, jobject /*thiz*/) {
    oscam::jni::NativeBridge::getInstance().stop();
}

JNIEXPORT jint JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeGetStatus(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(oscam::jni::NativeBridge::getInstance().getConnectionState());
}

JNIEXPORT jboolean JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeTestConnection(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring host,
    jint port,
    jint timeoutMs) {
    if (!host) {
        return JNI_FALSE;
    }

    const char* hostChars = env->GetStringUTFChars(host, nullptr);
    std::string hostStr(hostChars);
    env->ReleaseStringUTFChars(host, hostChars);

    bool res = oscam::jni::NativeBridge::getInstance().testConnection(hostStr, static_cast<uint16_t>(port), timeoutMs);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeGetStats(JNIEnv* env, jobject /*thiz*/) {
    const auto& stats = oscam::jni::NativeBridge::getInstance().getStats();
    jlong statsArray[5];
    statsArray[0] = static_cast<jlong>(stats.ecmSentCount.load());
    statsArray[1] = static_cast<jlong>(stats.cwReceivedCount.load());
    statsArray[2] = static_cast<jlong>(stats.emmSentCount.load());
    statsArray[3] = static_cast<jlong>(stats.lastCwTimeMs.load());
    statsArray[4] = static_cast<jlong>(stats.reconnectCount.load());

    jlongArray res = env->NewLongArray(5);
    if (res) {
        env->SetLongArrayRegion(res, 0, 5, statsArray);
    }
    return res;
}

JNIEXPORT jstring JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeGetLastError(JNIEnv* env, jobject /*thiz*/) {
    std::string err = oscam::jni::NativeBridge::getInstance().getLastError();
    return env->NewStringUTF(err.c_str());
}

JNIEXPORT void JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeRegisterCallback(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject callback) {
    if (!callback) return;
    jobject globalRef = env->NewGlobalRef(callback);
    oscam::jni::NativeBridge::getInstance().setJavaCallback(gJavaVM, globalRef);
}

JNIEXPORT void JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeUnregisterCallback(JNIEnv* env, jobject /*thiz*/) {
    oscam::jni::NativeBridge::getInstance().clearJavaCallback(env);
}

JNIEXPORT jint JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeDescrambleBuffer(
    JNIEnv* env,
    jobject /*thiz*/,
    jbyteArray buffer,
    jint offset,
    jint length) {
    if (!buffer || length <= 0) return 0;

    jbyte* bufPtr = env->GetByteArrayElements(buffer, nullptr);
    if (!bufPtr) return 0;

    size_t processed = oscam::jni::NativeBridge::getInstance().descrambleBuffer(
        reinterpret_cast<uint8_t*>(bufPtr + offset), static_cast<size_t>(length));

    env->ReleaseByteArrayElements(buffer, bufPtr, 0);
    return static_cast<jint>(processed);
}

JNIEXPORT void JNICALL
Java_com_oscam_cas_OscamNativeBridge_nativeSetSoftwareCw(
    JNIEnv* env,
    jobject /*thiz*/,
    jint pid,
    jint parity,
    jbyteArray cw) {
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

} // extern "C"
