# Tutorial Definitivo: Compilación, Instalación y Configuración Web de Android TV OSCam Bridge

Guía oficial paso a paso en español para compilar, instalar en tu Smart TV con Android TV / Google TV, y configurar completamente desde la consola web (`http://<IP_DE_LA_TELE>:8080`) el puente de acceso condicional para canales satelitales (DVB-S/S2/S2X) y terrestres (DVB-T/T2).

---

## Índice de Contenidos
1. [Introducción y Arquitectura](#1-introducción-y-arquitectura)
2. [Términos de Licencia y Créditos](#2-términos-de-licencia-y-créditos)
3. [Requisitos Previos de Compilación](#3-requisitos-previos-de-compilación)
4. [Compilación Paso a Paso](#4-compilación-paso-a-paso)
   - [4.1 Compilación del Motor Nativo C++ (CMake)](#41-compilación-del-motor-nativo-c-cmake)
   - [4.2 Compilación del APK de Android (Gradle)](#42-compilación-del-apk-de-android-gradle)
5. [Instalación en la Televisión Android TV](#5-instalación-en-la-televisión-android-tv)
   - [5.1 Despliegue por ADB (Red o USB)](#51-despliegue-por-adb-red-o-usb)
   - [5.2 Otorgar Permisos de Sistema y Segundo Plano](#52-otorgar-permisos-de-sistema-y-segundo-plano)
   - [5.3 Integración con Acceso Root / Magisk (Opcional para HAL protegido)](#53-integración-con-acceso-root--magisk-opcional-para-hal-protegido)
6. [Configuración Completa desde la Página Web de Control](#6-configuración-completa-desde-la-página-web-de-control)
   - [6.1 Acceso a la Consola Web (`http://<IP_TELE>:8080`)](#61-acceso-a-la-consola-web-httpip_tele8080)
   - [6.2 Explicación Detallada de Parámetros de Servidor](#62-explicación-detallada-de-parámetros-de-servidor)
   - [6.3 Los 7 Protocolos Disponibles](#63-los-7-protocolos-disponibles)
   - [6.4 Pruebas de Conexión en Vivo (Ping Test)](#64-pruebas-de-conexión-en-vivo-ping-test)
   - [6.5 Failover Automático y Servidor Primario](#65-failover-automático-y-servidor-primario)
   - [6.6 Guardado en Caliente (Hot-Reload)](#66-guardado-en-caliente-hot-reload)
7. [Bypass Inteligente para Canales en Abierto (FTA / Clear)](#7-bypass-inteligente-para-canales-en-abierto-fta--clear)
   - [7.1 ¿Por qué es crítico no lanzar el bridge en canales en abierto?](#71-por-qué-es-crítico-no-lanzar-el-bridge-en-canales-en-abierto)
   - [7.2 Cómo funciona el detector automático de tablas PMT y CAID](#72-cómo-funciona-el-detector-automático-de-tablas-pmt-y-caid)
8. [Configuración del Servidor OSCam en tu Servidor Doméstico](#8-configuración-del-servidor-oscam-en-tu-servidor-doméstico)
   - [8.1 Archivo `oscam.conf`](#81-archivo-oscamconf)
   - [8.2 Archivo `oscam.user`](#82-archivo-oscamuser)
   - [8.3 Archivo `oscam.server`](#83-archivo-oscamserver)
9. [Preguntas Frecuentes y Diagnóstico de Problemas](#9-preguntas-frecuentes-y-diagnóstico-de-problemas)

---

## 1. Introducción y Arquitectura

**Android OSCam Bridge** (`com.lizarragaeus.oscambridge`) es un middleware de grado profesional diseñado para televisores con **Android TV** y **Google TV** (TCL, Sony Bravia, Philips, Xiaomi, Hisense, etc.).

Su función principal es conectar el hardware de sintonización nativo del televisor (Tuner HAL y MediaCas Framework) con servidores de tarjetas de abonado domésticas (OSCam, CCcam, Newcamd, Camd35, Radegast), permitiendo descifrar las emisiones satelitales DVB-S2 y terrestres DVB-T2 en tiempo real directamente en la aplicación nativa de TV del fabricante, con aceleración por hardware del procesador (Amlogic, MediaTek, Realtek, Broadcom, Synaptics).

```
                     ┌────────────────────────────────────────────────────────┐
                     │                   SMART TV (Android TV)                │
                     │                                                        │
                     │  [App TV Oficial]        [Canal Sintonizado]           │
                     │  (TCL TV, Sony, etc.)             │                    │
                     │         │                         │                    │
                     │         ▼                         ▼                    │
                     │  [Android TIF]           ¿Está Codificado?             │
                     │         │                 /              \             │
                     │         │            NO (FTA)          SÍ (Scrambled)  │
                     │         │               │                     │        │
                     │         │          DIRECTO A PANTALLA         │        │
                     │         │         (Bypass total bridge)       ▼        │
                     │         ▼                                [MediaCas]    │
                     │  [OscamCasPlugin] ◄───────────────────────────┘        │
                     │         │                                              │
                     │         ▼ (Control Word inyectada al SoC)              │
                     │  [Hardware Descrambler] ──► Decodificación VPU         │
                     │         │                                              │
                     │         ▼ (Multi-protocolo TCP / UNIX Socket)          │
                     └─────────┼──────────────────────────────────────────────┘
                               │
                               │  DVBAPI / CCcam / Newcamd / Cs378x / Radegast
                               ▼
                ┌───────────────────────────────┐
                │ Servidor OSCam Doméstico      │
                │ (Raspberry Pi, Linux Server)  │
                │  - Tarjeta física de abonado  │
                └───────────────────────────────┘
```

---

## 2. Términos de Licencia y Créditos

Este software está protegido bajo los términos de la licencia internacional **Creative Commons Atribución-NoComercial-CompartirIgual 4.0 (CC BY-NC-SA 4.0)**.

> [!CAUTION]
> **PROHIBIDA SU VENTA O COMERCIALIZACIÓN**: Este código fuente y sus binarios resultantes **NO pueden venderse, revenderse, empaquetarse con fines comerciales ni utilizarse en servicios de pago**. 
> Si utilizas, modificas o redistribuyes este código, **debes otorgar créditos de manera obligatoria y visible** a:
> **Autor original:** Eneko Lizarraga (`lizarragaeus`)  
> **Identificador de paquete:** `com.lizarragaeus.oscambridge`  
> Consulta el archivo [`LICENSE.md`](file:///c:/Users/lizarragapc/Documents/android-oscam-bridge/LICENSE.md) para más detalles legales.

---

## 3. Requisitos Previos de Compilación

Para compilar el proyecto en tu ordenador (Linux, macOS o Windows) necesitas tener instalado:

1. **Java Development Kit (JDK):** Versión 17 o superior (OpenJDK 17 recomendado).
2. **Android SDK:** Con plataformas instaladas desde `android-28` hasta `android-34`.
3. **Android NDK:** Versión `r25c`, `r26b` o superior.
4. **CMake:** Versión 3.18 o superior.
5. **Git:** Para clonar y gestionar el repositorio.
6. **Android Studio (Opcional):** Versión Flamingo / Hedgehog / Iguana / Jellyfish o compilación directa por terminal con Gradle.

---

## 4. Compilación Paso a Paso

### 4.1 Compilación del Motor Nativo C++ (CMake)

El motor nativo de descodificación y los clientes de red residen en `bridge/`, `chipset/`, `hal/` y `jni/`.

#### En Linux / macOS (o WSL2 en Windows):
```bash
# 1. Clonar el repositorio y entrar en el directorio
git clone https://github.com/lizarragaeus/android-oscam-bridge.git
cd android-oscam-bridge

# 2. Configurar la compilación cruzada con el NDK para arquitectura ARM64 (Android TV)
export ANDROID_NDK=/ruta/a/tu/android-ndk-r25c

cmake -B build-android-arm64 \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DCMAKE_BUILD_TYPE=Release

# 3. Compilar todas las librerías nativas y binarios
cmake --build build-android-arm64 -j$(nproc)
```

#### Para televisores de 32 bits (arquitectura `armeabi-v7a`):
```bash
cmake -B build-android-arm32 \
    -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=armeabi-v7a \
    -DANDROID_PLATFORM=android-28 \
    -DCMAKE_BUILD_TYPE=Release

cmake --build build-android-arm32 -j$(nproc)
```

#### Ejecutar las pruebas unitarias nativas (en PC host):
```bash
cmake -B build-tests -DCMAKE_BUILD_TYPE=Debug
cmake --build build-tests --target cs378x_tests conn_manager_tests
ctest --test-dir build-tests --output-on-failure
```

---

### 4.2 Compilación del APK de Android (Gradle)

El APK de gestión (`com.lizarragaeus.oscambridge`) integra el servidor web embebido (puerto 8080), el servicio en segundo plano `OscamCasBinderService`, y la integración con el sistema de televisión `OscamTvInputService`.

1. Abre una terminal en la raíz del proyecto.
2. Ejecuta el empaquetado Gradle:

```bash
# En Linux / macOS:
./gradlew assembleRelease

# En Windows (PowerShell):
.\gradlew.bat assembleRelease
```

El instalador final generado se ubicará en:
`build/outputs/apk/release/android-oscam-bridge-release.apk`

---

## 5. Instalación en la Televisión Android TV

### 5.1 Despliegue por ADB (Red o USB)

1. En tu televisor Android TV o Google TV, dirígete a:
   `Ajustes` -> `Preferencias del dispositivo` -> `Información` -> Pulsa 7 veces sobre `Compilación de SO de Android TV` para habilitar las **Opciones de desarrollador**.
2. Entra en `Opciones de desarrollador` y activa la casilla **Depuración por USB** y **Depuración de red**.
3. Averigua la dirección IP de tu televisor en `Ajustes` -> `Red e Internet` (por ejemplo `192.168.1.150`).
4. Desde tu ordenador, conéctate e instala el APK:

```bash
# Conectar con la TV
adb connect 192.168.1.150:5555

# Instalar el paquete compilado
adb install -r build/outputs/apk/release/android-oscam-bridge-release.apk
```

---

### 5.2 Otorgar Permisos de Sistema y Segundo Plano

Para garantizar que Android TV no detenga el servicio cuando el televisor entre en reposo o al cambiar de aplicación:

```bash
# Evitar optimizaciones de batería que suspendan el servicio
adb shell dumpsys deviceidle whitelist +com.lizarragaeus.oscambridge

# Habilitar inicio automático tras encendido de la televisión
adb shell pm grant com.lizarragaeus.oscambridge android.permission.RECEIVE_BOOT_COMPLETED
```

---

### 5.3 Integración con Acceso Root / Magisk (Opcional para HAL protegido)

En la inmensa mayoría de televisores (TCL, Philips, Xiaomi), la aplicación funciona directamente mediante las APIs estándar de **Android TV Input Framework (TIF)** sin necesidad de root.

Sin embargo, si tu televisor tiene SELinux restrictivo y cuenta con acceso root o Magisk, puedes inyectar el módulo HAL nativo copiando la librería a la partición de vendor:

```bash
adb root
adb remount
adb push build-android-arm64/hal/native/liboscam_cas_plugin.so /vendor/lib64/
adb shell chmod 644 /vendor/lib64/liboscam_cas_plugin.so
```

---

## 6. Configuración Completa desde la Página Web de Control

> [!TIP]
> **NO necesitas editar ficheros de configuración a mano ni recompilar el código**.
> Toda la gestión de servidores, direcciones IP, puertos, usuarios, contraseñas, claves DES, protocolos y tiempos de espera se realiza en tiempo real a través de la interfaz web integrada.

### 6.1 Acceso a la Consola Web (`http://<IP_TELE>:8080`)

1. Abre cualquier navegador web (Chrome, Firefox, Safari, Edge) en tu ordenador, móvil o tablet conectado a la misma red WiFi o cableada que la tele.
2. Introduce la URL:
   ```text
   http://<DIRECCION_IP_DE_TU_TELEVISOR>:8080
   ```
   *(Ejemplo: `http://192.168.1.150:8080`)*
3. Verás de inmediato el panel de control profesional de **Android TV CAS Bridge Master Console**.

---

### 6.2 Explicación Detallada de Parámetros de Servidor

En la pestaña **"📡 Servers & Multi-Protocol Matrix"** dispones de los siguientes campos totalmente editables para cada servidor:

| Campo | Descripción | Valor por Defecto / Ejemplo |
|---|---|---|
| **Profile Name** | Nombre identificativo del servidor para tu propia organización interna. | `Servidor Salón`, `Movistar+ Backup`, etc. |
| **Protocol** | Protocolo de red utilizado para comunicarse con el servidor OSCam. | `CCCAM`, `DVBAPI`, `CS378X`, etc. |
| **Host / IP Address / Socket Path** | Dirección IP local de tu servidor OSCam (o ruta del socket UNIX). | `192.168.1.100` o `/tmp/camd.socket` |
| **Port** | Puerto TCP en el que escucha el servicio correspondiente. | Auto-asignado según protocolo (ej. `12000`, `9000`, `10000`). |
| **Username** | Nombre de usuario configurado en el `oscam.user` del servidor. | `android_tv` |
| **Password** | Contraseña del usuario correspondiente. | `android_tv` |
| **DES Key (14 bytes hex)** | Clave DES para cifrado en protocolo Newcamd. | `0102030405060708091011121314` |
| **Target CAID** | Identificador hexadecimal del sistema de acceso condicional objetivo. | `0x1810` (Nagra/Movistar), `0x1830` (HD+), `0x0100` (Seca) |
| **Connect Timeout (s)** | Tiempo máximo en segundos para establecer la conexión TCP antes de declarar timeout. | `4` segundos |
| **Recv Timeout (s)** | Tiempo máximo en segundos de espera de una respuesta CW antes de reintentar. | `8` segundos |
| **Reconnect Interval (ms)** | Milisegundos entre intentos de reconexión tras una pérdida de enlace. | `2000` ms |
| **Active (Casilla de verificación)** | Activa o desactiva este servidor sin necesidad de borrar sus datos. | Activado (`[x]`) |
| **Primary (Selector de radio)** | Marca cuál es el servidor principal prioritario en caso de haber varios. | Seleccionado (`(o)`) |

---

### 6.3 Los 7 Protocolos Disponibles

Puedes elegir entre 7 métodos de conexión según la configuración de tu servidor:

1. **`CCCAM` (CCcam v2.3.0)**:
   - Puerto por defecto: `12000`.
   - Cifrado de sesión nativo CCcam con intercambio de claves.
   - Requiere: Host, Puerto, Usuario, Contraseña.

2. **`DVBAPI` (OSCam dvbapi TCP)**:
   - Puerto por defecto: `9000`.
   - Protocolo nativo de OSCam para clientes locales por red. Máxima compatibilidad y velocidad.
   - Requiere: Host, Puerto. Usuario opcional.

3. **`DVBAPI_UNIX` (OSCam dvbapi Socket UNIX)**:
   - Puerto: `0` (desactivado automáticamente).
   - Diseñado para cuando OSCam corre dentro de la propia Smart TV (en Termux o chroot Linux) compartiendo `/tmp/camd.socket`. Cero latencia de red.
   - Requiere: Ruta de socket (ej. `/tmp/camd.socket`).

4. **`CS378X` (Camd35 sobre TCP)**:
   - Puerto por defecto: `13000`.
   - Protocolo clásico de OSCam con cifrado AES-128 nativo por paquete. Muy robusto frente a firewalls.
   - Requiere: Host, Puerto, Usuario, Contraseña.

5. **`RADEGAST` (Radegast v3)**:
   - Puerto por defecto: `678`.
   - Protocolo ultraligero sin sobrecarga de cifrado, con latencias de respuesta mínimas (menos de 20 ms).
   - Requiere: Host, Puerto.

6. **`NEWCAMD` (Newcamd v5.25)**:
   - Puerto por defecto: `10000`.
   - Cifrado 3DES con clave DES compartida. Estándar por puerto individual por tarjeta/CAID.
   - Requiere: Host, Puerto, Usuario, Contraseña, Clave DES (14 bytes hex).

7. **`OSCAM_WEBIF` (OSCam Web Interface REST)**:
   - Puerto por defecto: `8888`.
   - Conexión vía HTTP a la interfaz web de OSCam para consulta de estadísticas, estado de lectores y CWs.
   - Requiere: Host, Puerto, Usuario, Contraseña de WebIF.

---

### 6.4 Pruebas de Conexión en Vivo (Ping Test)

En cada tarjeta de servidor verás un botón **"Ping Test"**:
- Al pulsar **"Ping Test"**, la consola web ejecuta una prueba real contra el servidor utilizando las credenciales, el puerto y el protocolo seleccionados.
- Si el servidor responde correctamente, el indicador se pondrá en verde mostrando la latencia exacta en milisegundos (ejemplo: `✓ Reachable (DVBAPI Latency: 42 ms)`).
- Si hay un error de credenciales, puerto bloqueado o servidor apagado, se indicará en rojo la causa exacta para diagnosticarlo al instante.

---

### 6.5 Failover Automático y Servidor Primario

Puedes añadir todos los servidores que desees (por ejemplo, tu servidor principal por DVBAPI y un servidor de respaldo por CCcam o Newcamd):
- El servidor marcado como **Primary** recibirá siempre las peticiones de ECMs en primer lugar.
- Si el servidor primario pierde la conexión o sufre cortes, el orquestador nativo (`OscamConnectionManager`) conmuta en milisegundos al siguiente servidor activo sin interrumpir la emisión en la pantalla.

---

### 6.6 Guardado en Caliente (Hot-Reload)

Una vez hayas configurado tus servidores:
1. Pulsa el botón verde **"Save & Hot-Reload Servers"** en la parte inferior.
2. La configuración se guarda de forma persistente en el almacenamiento de la televisión (`/data/vendor/oscam/config.json` y Android DataStore).
3. El motor nativo detecta el cambio al instante y actualiza las conexiones **sin necesidad de reiniciar la televisión ni detener el canal que estás viendo**.

---

## 7. Bypass Inteligente para Canales en Abierto (FTA / Clear)

### 7.1 ¿Por qué es crítico no lanzar el bridge en canales en abierto?

En emisiones vía satélite (DVB-S2 como Astra 19.2°E o Hotbird 13°E) y en televisión terrestre (DVB-T/T2), coexisten dos tipos de canales:
1. **Canales en Abierto (Free-To-Air / FTA)**: Canales como TVE 1, TVE 2, 24h, cadenas autonómicas, ARD, ZDF, Servus TV, etc., cuya señal se emite completamente sin cifrar.
2. **Canales Codificados (Scrambled)**: Canales que requieren tarjeta de suscripción y acceso condicional para descifrar la señal de vídeo y audio mediante Control Words (CW).

> [!IMPORTANT]
> **El problema habitual de otros sistemas:**  
> Si un programa intercepta indiscriminadamente todos los canales y fuerza la creación de filtros DMX y sesiones MediaCas en canales que no están codificados, el hardware del televisor se satura intentando descifrar algo que ya viene en claro, produciendo congelaciones de imagen, zapping lentísimo, pantalla en negro y mensajes falsos de "Canal codificado".

---

### 7.2 Cómo funciona el detector automático de tablas PMT y CAID

Para solucionar esto de raíz, este proyecto implementa un **bypass inteligente de canal libre**:

1. **Inspección de la tabla PMT (Program Map Table):**
   - Cuando sintonizas cualquier canal, el parser nativo de bajo nivel (`SatellitePmtParser`) analiza la sección binaria de la tabla PMT emitida en el flujo MPEG-TS de la antena.
   - El parser busca la presencia de descriptores de acceso condicional (**CA Descriptors con Tag `0x09`**), tanto a nivel global de programa como a nivel individual de streams de vídeo o audio.
2. **Determinación del estado:**
   - **Canal en abierto (`isScrambled() == false`):**  
     Si la PMT no contiene descriptores CA (o el campo `free_CA_mode` indica 0), el software detecta inmediatamente que el canal es FTA.  
     **El bridge NO se lanza, no se conecta a OSCam y no instala ningún filtro.**  
     Cualquier sesión previa de descodificación se libera y el flujo de la antena viaja directamente al decodificador de vídeo por hardware del televisor. Resultado: zapping instantáneo, fluidez nativa del fabricante y cero consumo de recursos.
   - **Canal codificado (`isScrambled() == true`):**  
     Si se detectan descriptores CA (como Nagra `0x1810`, HD+ `0x1830`, Seca `0x0100`, etc.), el televisor no puede descodificarlo por sí mismo. En ese instante exacto, y **solo en ese momento**, se activa la sesión MediaCas del bridge, se localiza el ECM PID adecuado, se solicita la clave a OSCam y se inyecta la Control Word en el chipset.

---

## 8. Configuración del Servidor OSCam en tu Servidor Doméstico

Para que tu servidor OSCam (en una Raspberry Pi, PC o servidor Linux) acepte las conexiones procedentes de la Smart TV, añade las siguientes secciones en tus archivos de configuración de OSCam:

### 8.1 Archivo `oscam.conf`

```ini
[global]
logfile                       = /var/log/oscam.log
clienttimeout                 = 4000
fallbacktimeout               = 2000
clientmaxidle                 = 120
bindwait                      = 120

# 1. Módulo DVBAPI por red TCP (para protocolo DVBAPI en la TV)
[dvbapi]
enabled                       = 1
au                            = 1
pmt_mode                      = 0
request_mode                  = 0
listen_port                   = 9000
user                          = android_tv
boxtype                       = pc

# 2. Módulo CCcam (para protocolo CCCAM en la TV)
[cccam]
port                          = 12000
version                       = 2.3.0
reshare                       = 1
stealth                       = 1

# 3. Módulo Camd35 / cs378x TCP (para protocolo CS378X en la TV)
[cs378x]
port                          = 13000

# 4. Módulo Newcamd (para protocolo NEWCAMD en la TV)
[newcamd]
port                          = 10000@1810:000000,004106;10001@1830:000000
key                           = 0102030405060708091011121314
allowed                       = 192.168.0.0-192.168.255.255
keepalive                     = 1

# 5. Interfaz Web (para protocolo OSCAM_WEBIF en la TV)
[webif]
httpport                      = 8888
httpuser                      = admin
httppwd                       = admin
httpallowed                   = 127.0.0.1,192.168.0.0-192.168.255.255
```

---

### 8.2 Archivo `oscam.user`

Crea el usuario que utilizará la aplicación del televisor:

```ini
[account]
user                          = android_tv
pwd                           = android_tv
group                         = 1
au                            = 1
caid                          = 1810,1830,0100,0500,0B00,09CD
cccmaxhops                    = 2
cccreshare                    = 1
```

---

### 8.3 Archivo `oscam.server`

Configura tu lector de tarjeta física local de abonado:

```ini
[reader]
label                         = lector_tarjeta_local
protocol                      = pcsc
device                        = 0
caid                          = 1810
ecmwhitelist                  = 1810:8E,64
detect                        = cd
mhz                           = 368
cardmhz                       = 368
ident                         = 1810:000000,004106,004001
group                         = 1
emmcache                      = 1,3,2,0
```

---

## 9. Preguntas Frecuentes y Diagnóstico de Problemas

### P: ¿Puedo cambiar la IP o el protocolo del servidor sin reiniciar la televisión?
**R:** Sí. Entra en `http://<IP_TELE>:8080`, modifica los valores en la pestaña *Servers*, pulsa *Save & Hot-Reload Servers* y la conexión cambiará de inmediato sin interrumpir el canal.

### P: ¿Qué pasa si se me cae el servidor principal?
**R:** Si configuras más de un servidor (por ejemplo, uno primario por DVBAPI y otro secundario por Newcamd), el sistema detecta la falta de respuesta y salta automáticamente al servidor de reserva.

### P: ¿Por qué en los canales de televisión pública no veo actividad de ECMs en OSCam?
**R:** Es el comportamiento correcto gracias al **Bypass FTA Inteligente**. Los canales en abierto no necesitan descodificación, por lo que el bridge no solicita nada a OSCam, permitiendo que la tele los reproduzca a máxima velocidad sin sobrecargar tu servidor.

### P: ¿Dónde puedo consultar los registros (logs) en tiempo real?
**R:** En la consola web (`http://<IP_TELE>:8080`), accede a la pestaña **"📜 Live Logcat"** para ver los eventos del bridge en directo con colores de diagnóstico y opción de descarga del archivo `.log`.

