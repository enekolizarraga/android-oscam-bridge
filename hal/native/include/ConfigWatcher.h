// hal/native/include/ConfigWatcher.h
//
// Monitor de archivos en tiempo real para recarga de configuración en caliente (Hot-Reload).
// Permite modificar el servidor OSCam, puerto y CAIDs desde la interfaz de la televisión
// sin necesidad de recompilar código ni reiniciar el sistema.
//
// Autor: android-oscam-bridge

#pragma once

#include "OscamCasService.h"

#include <string>
#include <thread>
#include <atomic>
#include <functional>

namespace oscam::hal {

/**
 * @brief Monitor de cambios de configuración usando inotify en Android/Linux.
 */
class ConfigWatcher {
public:
    using OnConfigReloadCallback = std::function<void(const ServiceConfig& newConfig)>;

    explicit ConfigWatcher(
        std::string configFilePath,
        OnConfigReloadCallback onReloadCallback);

    ~ConfigWatcher();

    /**
     * @brief Inicia el hilo de monitoreo en segundo plano.
     */
    bool start();

    /**
     * @brief Detiene el monitoreo.
     */
    void stop();

    /**
     * @brief Carga y parsea el archivo de configuración inmediatamente.
     */
    static std::optional<ServiceConfig> loadFromFile(const std::string& path);

private:
    void watcherThreadLoop();

    std::string configFilePath_;
    OnConfigReloadCallback onReloadCallback_;
    std::atomic<bool> running_{false};
    std::thread workerThread_;
    int inotifyFd_{-1};
    int watchDescriptor_{-1};
};

} // namespace oscam::hal

