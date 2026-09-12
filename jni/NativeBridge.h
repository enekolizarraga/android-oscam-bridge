// jni/NativeBridge.h
//
// C++ JNI bridge interface between Kotlin/Android layer and native OSCam CAS engine.
// Enables the Foreground Service and TV Settings UI to control the dvbapi client,
// monitor real-time metrics, perform connectivity diagnostics, and descramble
// external streams in software.
//
// Author: android-oscam-bridge

#pragma once

#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <mutex>
#include <atomic>
#include <cstdint>

#include "../bridge/include/DvbapiClient.h"
#include "../bridge/include/BridgeLogger.h"
#include "../bridge/include/SoftwareDescrambler.h"
#include "../hal/native/include/OscamCasService.h"

namespace oscam::jni {

/**
 * @brief Connection states dispatched to Kotlin/Java layer.
 */
enum class ConnectionState : int32_t {
    Disconnected = 0,
    Connecting   = 1,
    Connected    = 2,
    Error        = 3
};

/**
 * @brief Operating metrics of the native bridge for UI diagnostic.
 */
struct NativeBridgeStats {
    std::atomic<uint64_t> ecmSentCount{0};
    std::atomic<uint64_t> cwReceivedCount{0};
    std::atomic<uint64_t> emmSentCount{0};
    std::atomic<uint32_t> lastCwTimeMs{0};
    std::atomic<uint32_t> reconnectCount{0};
};

/**
 * @brief Native singleton manager for bridge lifecycle and JNI dispatch.
 */
class NativeBridge {
public:
    static NativeBridge& getInstance();

    NativeBridge(const NativeBridge&) = delete;
    NativeBridge& operator=(const NativeBridge&) = delete;

    /**
     * @brief Initializes the native bridge with server network params and CAIDs.
     */
    bool initialize(const std::string& host, uint16_t port, const std::vector<uint16_t>& supportedCaids);

    /**
     * @brief Starts the background dvbapi client thread.
     */
    bool start();

    /**
     * @brief Stops the client and releases network resources.
     */
    void stop();

    /**
     * @brief Tests TCP connectivity to the OSCam server with a specified timeout.
     */
    bool testConnection(const std::string& host, uint16_t port, int32_t timeoutMs);

    /**
     * @brief Returns current connection state.
     */
    ConnectionState getConnectionState() const noexcept;

    /**
     * @brief Returns the last recorded error message.
     */
    std::string getLastError() const;

    /**
     * @brief Returns statistics of CWs and packets.
     */
    const NativeBridgeStats& getStats() const noexcept { return stats_; }

    /**
     * @brief Descrambles an in-memory buffer of MPEG-TS packets using software DVB-CSA.
     */
    size_t descrambleBuffer(uint8_t* buffer, size_t size);

    /**
     * @brief Sets software CW for external stream descrambling.
     */
    void setSoftwareCw(uint16_t pid, int parity, const uint8_t* cw);

    /**
     * @brief Registers the Java callback listener for asynchronous events.
     */
    void setJavaCallback(JavaVM* vm, jobject globalCallbackRef);

    /**
     * @brief Cleans up Java callback global reference.
     */
    void clearJavaCallback(JNIEnv* env);

    /**
     * @brief Notifies Java about connection state changes.
     */
    void notifyJavaConnectionChanged(ConnectionState state);

    /**
     * @brief Notifies Java when a Control Word has been resolved.
     */
    void notifyJavaCwReceived(int32_t sessionHandle, const std::vector<uint8_t>& cw);

private:
    NativeBridge();
    ~NativeBridge();

    mutable std::mutex mutex_;
    std::string host_{"127.0.0.1"};
    uint16_t port_{9000};
    std::vector<uint16_t> supportedCaids_;

    std::shared_ptr<dvbapi::DvbapiClient> dvbapiClient_;
    std::shared_ptr<hal::OscamCasService> casService_;
    std::unique_ptr<bridge::SoftwareDescrambler> softwareDescrambler_;

    std::atomic<ConnectionState> connectionState_{ConnectionState::Disconnected};
    std::string lastError_;

    NativeBridgeStats stats_;

    // JNI references
    JavaVM* jvm_{nullptr};
    jobject javaCallbackRef_{nullptr};
    jmethodID onStateChangedMethod_{nullptr};
    jmethodID onCwReceivedMethod_{nullptr};
};

} // namespace oscam::jni
