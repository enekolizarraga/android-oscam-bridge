// hal/native/include/OscamCasService.h
//
// Servicio principal del HAL CAS (punto de entrada ante el framework de Android).
//
// Autor: android-oscam-bridge (Fase 3)

#pragma once

#include "OscamTypes.h"
#include "OscamCasPlugin.h"
#include "../../../chipset/include/IChipsetAdapter.h"
#include "../../../bridge/include/DvbapiClient.h"

#include <vector>
#include <memory>
#include <mutex>
#include <unordered_set>

namespace oscam::hal {

struct ServiceConfig {
    std::string oscamHost{"127.0.0.1"};
    uint16_t oscamPort{9000};
    std::vector<uint16_t> supportedCaids{0x0604, 0x1801, 0x0500, 0x0100};
};

/**
 * @brief Servicio de fábrica que implementa IOscamCasService y despacha instancias
 * de OscamCasPlugin para cada CA_system_id solicitado.
 */
class OscamCasService {
public:
    explicit OscamCasService(ServiceConfig config = {});
    ~OscamCasService();

    bool initialize();

    bool isSystemIdSupported(int32_t caSystemId) const noexcept;

    std::shared_ptr<OscamCasPlugin> createPlugin(
        int32_t caSystemId,
        std::shared_ptr<IOscamPluginListener> listener);

    void addSupportedCaid(uint16_t caid);

    /**
     * @brief Recarga la configuración del servidor en caliente sin reiniciar el HAL.
     */
    bool reloadConfig(const ServiceConfig& newConfig);

    std::shared_ptr<chipset::IChipsetAdapter> getChipsetAdapter() const noexcept {
        return chipsetAdapter_;
    }

private:
    ServiceConfig config_;
    std::unordered_set<uint16_t> supportedCaids_;

    std::shared_ptr<chipset::IChipsetAdapter> chipsetAdapter_;
    std::shared_ptr<dvbapi::DvbapiClient> dvbapiClient_;
    std::vector<std::shared_ptr<OscamCasPlugin>> activePlugins_;
    std::shared_ptr<class ConfigWatcher> configWatcher_;
    mutable std::mutex serviceMutex_;
};

} // namespace oscam::hal

