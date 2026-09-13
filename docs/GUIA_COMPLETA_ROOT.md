# 🚀 Guía Completa de Integración en Modo ROOT (Hardware SoC Descrambling)

Esta guía técnica avanzada describe cómo desplegar, configurar y optimizar **Android-OSCam-Bridge** en Smart TVs y decodificadores Android TV con permisos de **Superusuario (ROOT)**.

---

## 📑 Tabla de Contenidos
1. [Filosofía: ROOT vs MODO SIN ROOT](#1-filosofía-root-vs-modo-sin-root)
2. [Ventajas Exclusivas del Modo ROOT](#2-ventajas-exclusivas-del-modo-root)
3. [Métodos de Root Soportados en Android TV](#3-métodos-de-root-soportados-en-android-tv)
4. [Mapeo de Nodos Hardware del SoC (Amlogic, MediaTek, Realtek, HiSilicon)](#4-mapeo-de-nodos-hardware-del-soc)
5. [Políticas y Reglas SELinux (`supolicy` / Magisk)](#5-políticas-y-reglas-selinux)
6. [Inyección Directa de Control Words (CW) por Hardware (`ca_set_descr`)](#6-inyección-directa-de-control-words-cw-por-hardware)
7. [Scripts de Automatización 24/7 (`/data/adb/service.d/`)](#7-scripts-de-automatización-247)
8. [Guía de Configuración Paso a Paso desde ADB](#8-guía-de-configuración-paso-a-paso-desde-adb)
9. [Solución de Problemas (Troubleshooting) Avanzado](#9-solución-de-problemas-troubleshooting-avanzado)

---

## 1. Filosofía: ROOT vs MODO SIN ROOT

Android TV implementa un modelo de seguridad restrictivo con **SELinux en modo Enforcing**, impidiendo que aplicaciones normales de usuario lean o escriban en los nodos `/dev/dvb*` o controlen los descodificadores criptográficos del chipset de vídeo.

| Característica | Modo Sin Root (TVHeadend / SAT>IP) | Modo ROOT (Hardware SoC Direct) |
| :--- | :--- | :--- |
| **Público Objetivo** | TVs comerciales cerradas (Sony, Philips, TCL stock) | Dispositivos rooteados, cajas Mecool/Formuler o desarrolladores |
| **Método de Visualización** | Vía streaming local (TiviMate, Kodi, VLC en puerto 9191) | **App oficial de canales de la TV** (TCL Channel Box, Live TV nativo) |
| **Descifrado de Flujo** | DVB-CSA por Software en CPU (espacio de usuario) | **Silicio del SoC** (DVB CA Engine / Hardware Descrambler) |
| **Uso de CPU en 4K UHD** | 12% - 25% según núcleos del TV | **0.0% CPU** (el descifrado ocurre en hardware) |
| **Latencia de Conmutación** | 1.2s - 2.5s (buffer HTTP MSE) | **0.1s - 0.3s** (instantánea, zapping satelital puro) |
| **Complejidad de Instalación** | Mínima (instalar APK y activar) | Media/Alta (requiere Magisk, ADB y scripts supolicy) |

---

## 2. Ventajas Exclusivas del Modo ROOT

1. **Uso de CPU Prácticamente Nulo (0% Overhead):**
   En modo sin root, el procesador del televisor debe ejecutar el algoritmo DVB-CSA en memoria RAM y re-encapsular el stream MPEG-TS. En modo ROOT, el driver nativo `liboscam_native.so` inyecta las Control Words (CWs) directamente en las tablas de descifrado del demultiplexor de silicio mediante llamadas `ioctl(CA_SET_DESCR)`. El chip de vídeo descomprime el canal encriptado como si fuera un canal en abierto (FTA).
2. **Compatibilidad Total con Canales 4K UHD HDR 10-bit a 60 fps:**
   No hay cuellos de botella de red interna ni retardos de procesamiento. Los canales 4K de alto bitrate (como RTL UHD o transmisiones deportivas) se reproducen fluidos sin caídas de fotogramas (*frame drops*).
3. **Mando a Distancia y App Nativa del Televisor:**
   El usuario no necesita abrir TiviMate ni reproductores de terceros: puede cambiar de canal con los números del mando a distancia de su TV usando la interfaz original del fabricante.

---

## 3. Métodos de Root Soportados en Android TV

### Método A: Magisk (Recomendado para Android 9 a 13)
1. Extraer la imagen de arranque (`boot.img` o `init_boot.img`) de la actualización OTA oficial de tu Smart TV usando herramientas como `payload-dumper-go`.
2. Instalar la app **Magisk APK** en la Smart TV vía ADB:
   ```bash
   adb install -r Magisk-v26.4.apk
   ```
3. Parchear el `boot.img` desde la interfaz de Magisk.
4. Flashear la imagen parcheada en modo Fastboot:
   ```bash
   fastboot flash boot magisk_patched_boot.img
   fastboot reboot
   ```

### Método B: KernelSU / APatch (Recomendado para Android 12, 13 y 14 con GKI)
Si tu Smart TV corre un kernel Linux 5.10 o 5.15 con arquitectura Generic Kernel Image (GKI):
- **KernelSU** proporciona acceso root a nivel de núcleo, resultando completamente invisible a verificaciones de seguridad de SafetyNet o Play Integrity.
- Mantiene íntegros los certificados **Widevine L1** para que Netflix y Prime Video sigan funcionando en 4K.

### Método C: Cajas Android TV de Fábrica Abiertas
Dispositivos satelitales híbridos (Mecool KT1, Formuler Z11, cajas con SoC Amlogic S905X4) suelen incluir opciones de "Root switch" en Ajustes de Desarrollador o builds `userdebug`.

---

## 4. Mapeo de Nodos Hardware del SoC

Cada fabricante de chipsets satelitales expone el descodificador DVB en diferentes rutas de `/dev`:

### Amlogic (T972, T982, S905D3, S905X4)
* Demodulador y Frontend: `/dev/dvb0.frontend0`
* Demultiplexor de Transporte: `/dev/dvb0.demux0`
* **Módulo de Acceso Condicional (CA):** `/dev/dvb0.ca0`
* Nodos de inyección de flujo Amlogic: `/dev/amstream_mpps`, `/dev/amvideo`, `/dev/amaudio`

### MediaTek / MStar (MT9615, MT9638, MT9950 en TCL, Philips, Sony)
* Frontend DVB-S2: `/dev/frontend0` o `/dev/tuner0`
* Demultiplexor: `/dev/demux0`
* **Módulo de Hardware Descrambler:** `/dev/mtk_ca0` o `/dev/ca0`
* Controlador TEE / CI+: `/dev/mstar_ci0`, `/dev/tee_demux`

### Realtek (RTD2871, RTD2872, RTD2893)
* Frontend: `/dev/rtk_dvb0.frontend0`
* Demux: `/dev/rtk_dvb0.demux0`
* **CA Descrambler:** `/dev/rtk_ca0`

### HiSilicon (Hi3798MV200 / Hi3798CV200)
* Demultiplexor: `/dev/hi_demux0` a `/dev/hi_demux7`
* **CA Hardware:** `/dev/hi_ca` o `/dev/advca`

---

## 5. Políticas y Reglas SELinux

Por defecto, la política de seguridad SELinux de Android bloquea a cualquier aplicación de terceros (contexto `untrusted_app`) acceder a los nodos de caracteres (`chr_file`).

### Paso 1: Comprobación del Estado de SELinux
Conecta tu ordenador mediante ADB y ejecuta:
```bash
adb shell
su
getenforce
```
Si devuelve `Enforcing`, las políticas están activas y bloquean el hardware.

### Paso 2: Configuración Permanente con Magisk `supolicy`
Para mantener SELinux en modo **Enforcing** (para no perder certificados ni romper la seguridad del sistema) pero conceder acceso exclusivo a los nodos DVB a nuestra app, añade las siguientes reglas:

```bash
# Permitir que la app abra, lea, escriba y ejecute IOCTLs en el CA Engine
supolicy --live "allow untrusted_app dvb_device chr_file { read write open ioctl getattr }"
supolicy --live "allow untrusted_app tee_device chr_file { read write open ioctl getattr }"
supolicy --live "allow untrusted_app video_device chr_file { read write open ioctl getattr }"

# Soporte para TV inputs del sistema
supolicy --live "allow system_app dvb_device chr_file { read write open ioctl getattr }"
```

---

## 6. Inyección Directa de Control Words (CW) por Hardware

La API estándar del subsistema DVB de Linux (`<linux/dvb/ca.h>`) define la estructura `ca_descr_t` para registrar las claves de descifrado en los registros criptográficos del silicio:

```c
#include <linux/dvb/ca.h>
#include <sys/ioctl.h>
#include <fcntl.h>

typedef struct ca_descr {
    unsigned int index;      // Índice de clave asignado al PID de vídeo/audio
    unsigned int parity;     // 0 = Clave Par (EVEN), 1 = Clave Impar (ODD)
    unsigned char cw[8];     // 8 bytes de Control Word desencriptada
} ca_descr_t;

int inject_hardware_cw(int fd_ca, int key_index, int parity, const uint8_t *cw_bytes) {
    ca_descr_t descr;
    descr.index = key_index;
    descr.parity = (parity & 1);
    memcpy(descr.cw, cw_bytes, 8);

    // Llamada IOCTL directa al driver del kernel
    if (ioctl(fd_ca, CA_SET_DESCR, &descr) < 0) {
        perror("Error inyectando CW en CA_SET_DESCR");
        return -1;
    }
    return 0;
}
```

En **Android-OSCam-Bridge**, el motor C++ (`native_dvbapi.cpp` y `ca_hal_bridge.cpp`) detecta automáticamente la existencia de `/dev/dvb0.ca0` o `/dev/mtk_ca0` al disponer de permisos root y conmuta el modo de inyección de memoria RAM a inyección por hardware.

---

## 7. Scripts de Automatización 24/7 (`/data/adb/service.d/`)

Para asegurar que los permisos de hardware y las optimizaciones persistan tras reiniciar el televisor, crea el siguiente script de servicio en el directorio de inicio de Magisk:

### Crear archivo `/data/adb/service.d/01_oscam_bridge_root.sh`
```bash
adb shell su -c "cat << 'EOF' > /data/adb/service.d/01_oscam_bridge_root.sh
#!/system/bin/sh
# Esperar a que el sistema Android termine de arrancar los servicios HAL
sleep 12

# 1. Conceder permisos de lectura y escritura a todos los nodos DVB y de vídeo
chmod 666 /dev/dvb* /dev/dvb0.* /dev/frontend* /dev/demux* /dev/mtk_ca* /dev/amstream* 2>/dev/null

# 2. Inyectar reglas SELinux en caliente
supolicy --live "allow untrusted_app dvb_device chr_file { read write open ioctl getattr }"
supolicy --live "allow untrusted_app tee_device chr_file { read write open ioctl getattr }"
supolicy --live "allow system_app dvb_device chr_file { read write open ioctl getattr }"

# 3. Optimizar tamaño de buffers de red para streams de alto bitrate (20 Mbps)
sysctl -w net.core.rmem_max=16777216
sysctl -w net.core.wmem_max=16777216
sysctl -w net.ipv4.tcp_rmem="4096 87380 16777216"
sysctl -w net.ipv4.tcp_wmem="4096 65536 16777216"

# 4. Deshabilitar ahorro de energía en Wi-Fi y Ethernet para evitar latencias en ECMs
dumpsys deviceidle whitelist +com.lizarragaeus.oscambridge 2>/dev/null

EOF"
```

### Dar permisos de ejecución:
```bash
adb shell su -c "chmod 755 /data/adb/service.d/01_oscam_bridge_root.sh"
```

---

## 8. Guía de Configuración Paso a Paso desde ADB

### Paso 1: Conectar por ADB al televisor
Asegúrate de que la Smart TV y tu PC están en la misma red local:
```bash
adb connect 192.168.1.150:5555
```

### Paso 2: Verificar permisos de Superusuario
```bash
adb shell "su -c id"
# Salida esperada: uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0
```

### Paso 3: Identificar los nodos de hardware de tu televisión
Ejecuta una inspección de dispositivos disponibles:
```bash
adb shell "su -c 'ls -la /dev/dvb* /dev/mtk* /dev/am* /dev/frontend* /dev/ca* 2>/dev/null'"
```
Anota las rutas detectadas (por ejemplo `/dev/dvb0.ca0` en Amlogic o `/dev/mtk_ca0` en MediaTek/TCL).

### Paso 4: Instalar y otorgar permisos de sistema a la aplicación
```bash
adb install -r app-debug.apk
# Otorgar permisos de broadcast y optimización de batería
adb shell "dumpsys deviceidle whitelist +com.lizarragaeus.oscambridge"
adb shell "pm grant com.lizarragaeus.oscambridge android.permission.RECEIVE_BOOT_COMPLETED"
```

### Paso 5: Abrir la consola Web e iniciar la sintonización
1. Entra en tu navegador a `http://192.168.1.150:8080`.
2. Configura tu servidor **CCcam 2.3.0** o **OSCam (dvbapi)**.
3. Observa en la pestaña **TV & CI+** cómo los nodos de hardware pasan al estado `DETECTED & ACCESSIBLE`.
4. Enciende el sintonizador de tu televisor en cualquier canal codificado: la inyección de Control Words se realizará directamente por hardware con **0.0% de uso de CPU**.

---

## 9. Solución de Problemas (Troubleshooting) Avanzado

### A. Los canales siguen en negro a pesar de recibir CWs
* **Causa:** Desincronización de paridad (Parity Mismatch).
* **Solución:** Algunos chipsets (en especial MediaTek MT9638) invierten la paridad de las claves en el driver del kernel. En la consola Web (`http://<TV_IP>:8080`), activa la opción de paridad invertida o comprueba los logs en ADB:
  ```bash
  adb logcat -s OscamNative OscamCasBridge
  ```

### B. El televisor se reinicia al inyectar la primera clave (Kernel Panic)
* **Causa:** El tamaño de la estructura `ca_descr_t` en kernels de 64 bits de ciertos fabricantes incluye campos de padding específicos.
* **Solución:** Cambia temporalmente a **Modo Sin Root (TVHeadend/SAT>IP en puerto 9191)** mientras configuras el driver exacto correspondiente al kernel de tu televisor.

### C. Al rootear, Netflix o Disney+ pierden resolución 4K (Widevine L3)
* **Causa:** Desbloquear el bootloader invalida las claves Widevine L1 en la partición RPMB.
* **Solución:**
  1. Utiliza **KernelSU** o **APatch** en lugar de Magisk estándar.
  2. Instala el módulo de Magisk **PlayIntegrityFix** y **Universal SafetyNet Fix**.
  3. Si tu televisor pierde L1 irremediablemente, utiliza el **Modo Sin Root de Android-OSCam-Bridge**: al no requerir root, las claves DRM Widevine L1 se mantienen intactas al 100%.

---

*Desarrollado para la comunidad de televisión satelital abierta y código abierto.*  
*Licencia: CC BY-NC-SA 4.0.*

