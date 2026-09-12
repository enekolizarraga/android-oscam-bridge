// hal/native/ConfigWatcher.cpp
//
// Implementación del monitor de configuración en caliente (Hot-Reload).
//
// Autor: android-oscam-bridge

#include "include/ConfigWatcher.h"
#include "../../bridge/include/BridgeLogger.h"

#include <fstream>
#include <sstream>
#include <chrono>
#include <cstring>

#if !defined(_WIN32)
#  include <sys/inotify.h>
#  include <sys/select.h>
#  include <unistd.h>
#  include <fcntl.h>
#endif

namespace oscam::hal {

ConfigWatcher::ConfigWatcher(
    std::string configFilePath,
    OnConfigReloadCallback onReloadCallback)
    : configFilePath_(std::move(configFilePath))
    , onReloadCallback_(std::move(onReloadCallback)) {
}

ConfigWatcher::~ConfigWatcher() {
    stop();
}

bool ConfigWatcher::start() {
    if (running_.load()) {
        return true;
    }

    running_.store(true);
    workerThread_ = std::thread(&ConfigWatcher::watcherThreadLoop, this);
    BRIDGE_LOGI("ConfigWatcher: Hilo de monitoreo iniciado para '%s'", configFilePath_.c_str());
    return true;
}

void ConfigWatcher::stop() {
    if (!running_.load()) {
        return;
    }

    running_.store(false);

#if !defined(_WIN32)
    if (inotifyFd_ >= 0) {
        if (watchDescriptor_ >= 0) {
            inotify_rm_watch(inotifyFd_, watchDescriptor_);
            watchDescriptor_ = -1;
        }
        close(inotifyFd_);
        inotifyFd_ = -1;
    }
#endif

    if (workerThread_.joinable()) {
        workerThread_.join();
    }
    BRIDGE_LOGI("ConfigWatcher: Hilo de monitoreo detenido");
}

std::optional<ServiceConfig> ConfigWatcher::loadFromFile(const std::string& path) {
    std::ifstream file(path);
    if (!file.is_open()) {
        return std::nullopt;
    }

    std::stringstream buffer;
    buffer << file.rdbuf();
    std::string content = buffer.str();

    ServiceConfig cfg;

    // Parser JSON simplificado y robusto para evitar dependencias externas en HAL
    auto findStringField = [&content](const std::string& key) -> std::optional<std::string> {
        std::string pattern = "\"" + key + "\"";
        size_t pos = content.find(pattern);
        if (pos == std::string::npos) return std::nullopt;
        pos = content.find(':', pos);
        if (pos == std::string::npos) return std::nullopt;
        pos = content.find('"', pos);
        if (pos == std::string::npos) return std::nullopt;
        size_t end = content.find('"', pos + 1);
        if (end == std::string::npos) return std::nullopt;
        return content.substr(pos + 1, end - pos - 1);
    };

    auto findIntField = [&content](const std::string& key) -> std::optional<int> {
        std::string pattern = "\"" + key + "\"";
        size_t pos = content.find(pattern);
        if (pos == std::string::npos) return std::nullopt;
        pos = content.find(':', pos);
        if (pos == std::string::npos) return std::nullopt;
        size_t valStart = content.find_first_of("0123456789", pos);
        if (valStart == std::string::npos) return std::nullopt;
        size_t valEnd = content.find_first_not_of("0123456789", valStart);
        std::string valStr = content.substr(valStart, valEnd - valStart);
        return std::stoi(valStr);
    };

    auto host = findStringField("server_host");
    if (host && !host->empty()) {
        cfg.oscamHost = *host;
    }

    auto port = findIntField("server_port");
    if (port && *port > 0 && *port <= 65535) {
        cfg.oscamPort = static_cast<uint16_t>(*port);
    }

    // Parse "servers" array if present
    size_t srvArrPos = content.find("\"servers\"");
    if (srvArrPos != std::string::npos) {
        size_t startBracket = content.find('[', srvArrPos);
        size_t endBracket = content.find(']', startBracket);
        if (startBracket != std::string::npos && endBracket != std::string::npos) {
            std::string arrContent = content.substr(startBracket, endBracket - startBracket + 1);
            size_t objStart = 0;
            while ((objStart = arrContent.find('{', objStart)) != std::string::npos) {
                size_t objEnd = arrContent.find('}', objStart);
                if (objEnd == std::string::npos) break;
                std::string srvStr = arrContent.substr(objStart, objEnd - objStart + 1);

                auto srvStrField = [&srvStr](const std::string& key) -> std::optional<std::string> {
                    std::string pattern = "\"" + key + "\"";
                    size_t p = srvStr.find(pattern);
                    if (p == std::string::npos) return std::nullopt;
                    p = srvStr.find(':', p);
                    if (p == std::string::npos) return std::nullopt;
                    p = srvStr.find('"', p);
                    if (p == std::string::npos) return std::nullopt;
                    size_t e = srvStr.find('"', p + 1);
                    if (e == std::string::npos) return std::nullopt;
                    return srvStr.substr(p + 1, e - p - 1);
                };

                auto srvIntField = [&srvStr](const std::string& key) -> std::optional<int> {
                    std::string pattern = "\"" + key + "\"";
                    size_t p = srvStr.find(pattern);
                    if (p == std::string::npos) return std::nullopt;
                    p = srvStr.find(':', p);
                    if (p == std::string::npos) return std::nullopt;
                    size_t valS = srvStr.find_first_of("0123456789", p);
                    if (valS == std::string::npos) return std::nullopt;
                    size_t valE = srvStr.find_first_not_of("0123456789", valS);
                    return std::stoi(srvStr.substr(valS, valE - valS));
                };

                ServerConfig sc;
                sc.name = srvStrField("name").value_or("Server");
                sc.protocol = srvStrField("protocol").value_or("DVBAPI");
                sc.host = srvStrField("host").value_or("192.168.1.100");
                sc.port = static_cast<uint16_t>(srvIntField("port").value_or(9000));
                sc.user = srvStrField("user").value_or("android_tv");
                sc.password = srvStrField("password").value_or("android_tv");
                sc.desKey = srvStrField("des_key").value_or("0102030405060708091011121314");
                sc.caid = static_cast<uint16_t>(srvIntField("caid").value_or(0x1810));
                sc.connectTimeoutSec = srvIntField("connect_timeout_sec").value_or(4);
                sc.recvTimeoutSec = srvIntField("recv_timeout_sec").value_or(8);
                sc.reconnectIntervalMs = srvIntField("reconnect_interval_ms").value_or(2000);
                sc.isPrimary = (srvStr.find("\"is_primary\":true") != std::string::npos);

                cfg.servers.push_back(sc);
                objStart = objEnd + 1;
            }
        }
    }

    if (!cfg.servers.empty()) {
        const auto& active = cfg.servers.front();
        cfg.oscamHost = active.host;
        cfg.oscamPort = active.port;
        BRIDGE_LOGI("ConfigWatcher::loadFromFile: Parsed %zu servers (Active: %s %s:%u)",
                    cfg.servers.size(), active.protocol.c_str(), active.host.c_str(), active.port);
    } else {
        BRIDGE_LOGI("ConfigWatcher::loadFromFile: Loaded fallback config -> %s:%u",
                    cfg.oscamHost.c_str(), cfg.oscamPort);
    }

    return cfg;
}

void ConfigWatcher::watcherThreadLoop() {
#if !defined(_WIN32)
    inotifyFd_ = inotify_init1(IN_NONBLOCK);
    if (inotifyFd_ < 0) {
        BRIDGE_LOGE("ConfigWatcher: inotify_init1 falló");
        return;
    }

    watchDescriptor_ = inotify_add_watch(inotifyFd_, configFilePath_.c_str(), IN_CLOSE_WRITE | IN_MOVED_TO);
    if (watchDescriptor_ < 0) {
        // Si el archivo aún no existe, vigilar el directorio contenedor
        std::string dir = configFilePath_.substr(0, configFilePath_.find_last_of('/'));
        watchDescriptor_ = inotify_add_watch(inotifyFd_, dir.c_str(), IN_CLOSE_WRITE | IN_MOVED_TO);
    }

    while (running_.load()) {
        fd_set fds;
        FD_ZERO(&fds);
        FD_SET(inotifyFd_, &fds);

        struct timeval tv;
        tv.tv_sec = 2;
        tv.tv_usec = 0;

        int res = select(inotifyFd_ + 1, &fds, nullptr, nullptr, &tv);
        if (res > 0 && FD_ISSET(inotifyFd_, &fds)) {
            char buffer[1024];
            ssize_t bytesRead = read(inotifyFd_, buffer, sizeof(buffer));
            if (bytesRead > 0) {
                // Esperar 100ms para asegurar escritura completa
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                auto newCfg = loadFromFile(configFilePath_);
                if (newCfg && onReloadCallback_) {
                    BRIDGE_LOGI("ConfigWatcher: Detectado cambio en config! Recargando en caliente...");
                    onReloadCallback_(*newCfg);
                }
            }
        }
    }
#else
    // Fallback pasivo para pruebas en Windows host
    while (running_.load()) {
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
#endif
}

} // namespace oscam::hal

