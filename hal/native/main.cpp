// hal/native/main.cpp
//
// Entry point for the vendor.oscam.cas-service daemon.
// Registers the service with the Android Service Manager (ABinderProcess)
// and handles signal termination.
//
// Author: android-oscam-bridge

#include "include/OscamCasService.h"
#include "../../bridge/include/BridgeLogger.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>

using namespace oscam::hal;

int main(int /*argc*/, char** /*argv*/) {
    BRIDGE_LOGI("Starting vendor.oscam.cas-service daemon...");

    ABinderProcess_setThreadPoolMaxThreadCount(4);
    ABinderProcess_startThreadPool();

    OscamCasService service;
    if (!service.initialize()) {
        BRIDGE_LOGE("Failed to initialize OscamCasService");
        return 1;
    }

    BRIDGE_LOGI("vendor.oscam.cas-service successfully initialized and running.");
    ABinderProcess_joinThreadPool();

    return 0;
}

