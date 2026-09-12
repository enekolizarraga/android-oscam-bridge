// chipset/include/ChipsetDetector.h
//
// Detector automático de SoC basado en propiedades del sistema Android.
// Inspecciona ro.board.platform, ro.hardware y ro.product.board.
// Devuelve un puntero inteligente a IChipsetAdapter correspondiente
// o nullptr / UnsupportedChipsetException si la plataforma no está soportada.
//
// Autor: android-oscam-bridge (Fase 2)

#pragma once

#include "IChipsetAdapter.h"
#include <functional>
#include <memory>
#include <string>

namespace oscam::chipset {

using SystemPropertyGetter = std::function<std::string(const std::string& key)>;

class ChipsetDetector {
public:
    /**
     * @brief Detecta el SoC actual leyendo las propiedades del sistema Android.
     * @param customGetter Función opcional para leer propiedades (utilizado para tests/mocks).
     * @return std::unique_ptr<IChipsetAdapter> instanciado o nullptr si no está soportado.
     */
    static std::unique_ptr<IChipsetAdapter> detect(
        const SystemPropertyGetter& customGetter = nullptr);

    /**
     * @brief Clasifica el ChipsetType a partir de cadenas de plataforma y hardware.
     */
    static ChipsetType identify(const std::string& platform,
                                const std::string& hardware,
                                const std::string& board = "");

    /**
     * @brief Lee una propiedad del sistema de Android de forma portable.
     * En Android nativo usa __system_property_get; en host lee variables de entorno
     * o retorna cadena vacía.
     */
    static std::string getSystemProperty(const std::string& key);
};

} // namespace oscam::chipset

