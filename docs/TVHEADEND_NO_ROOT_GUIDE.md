# Guía Integral: Modo TVHeadend Embebido (Sin Root) y Streaming DVB-IPTV

Esta guía explica en detalle cómo utilizar **Android TV CAS Bridge** en cualquier Smart TV o dispositivo Android TV / Google TV **sin necesidad de rootear el televisor ni modificar el firmware de fábrica**.

---

## 1. ¿Por qué existe el Modo Sin Root (TVHeadend Embebido)?

### El Problema del Hardware CI Tradicional en Smart TVs Comerciales
Para emular un **módulo físico Common Interface (CAM / PCMCIA)** directamente en las ranuras CI/CI+ de la televisión, el software necesita interactuar con los controladores del kernel Linux (`/dev/dvb/adapter0/ca0`, `/dev/ci0`, etc.) y las librerías propietarias del fabricante (SoC MediaTek, Realtek, Amlogic o Novatek).

Sin embargo:
1. Prácticamente **todas las Smart TVs modernas** (Sony Bravia, Philips Ambilight, TCL, Xiaomi Mi TV, Chromecast con Google TV, Fire TV) vienen con el gestor de arranque bloqueado (*locked bootloader*) y **SELinux en modo Enforcing**.
2. Rootear una Smart TV moderna suele ser extremadamente difícil o peligroso (riesgo de *brick*, anulación de certificados DRM Widevine L1 para Netflix/Prime Video, y pérdida total de garantía).

### La Solución: TVHeadend Autónomo en Espacio de Usuario
Para resolver este dilema de forma elegante y 100% segura, **Android TV CAS Bridge incorpora su propio servidor de streaming DVB-IPTV tipo TVHeadend en segundo plano**.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       SMART TV / ANDROID TV (SIN ROOT)                     │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                     Android-OSCam-Bridge App                          │  │
│  │                                                                       │  │
│  │  1. Sintonización / Canales  --> TvContract / Sat Transponders / SAT>IP│  │
│  │  2. Cliente CCcam / OSCam    --> Autenticación C++ 2.3.0 / DVBAPI     │  │
│  │  3. Descifrador DVB-CSA      --> Motor nativo en tiempo real          │  │
│  │  4. Servidor TVHeadend       --> Emite MPEG-TS limpio en HTTP         │  │
│  └──────────────────┬───────────────────────────────┬────────────────────┘  │
│                     │                               │                       │
│       http://127.0.0.1:9191/playlist.m3u            │                       │
│       http://127.0.0.1:9191/epg.xml                 │                       │
│                     │                               │                       │
│  ┌──────────────────▼────────────┐     ┌────────────▼────────────────────┐  │
│  │  TiviMate / Kodi / VLC Player │     │  Red Local / WiFi Doméstica     │  │
│  │  (En la propia Smart TV)      │     │  (Móvil, Tablet, PC con VLC)    │  │
│  └───────────────────────────────┘     └─────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Ventajas clave:**
- **0% Riesgo, 100% Sin Root**: No requiere permisos de superusuario, ni desbloquear el bootloader, ni tocar SELinux.
- **Todo incluido en la app**: OSCam dvbapi, CCcam 2.3.0, descifrador DVB-CSA y servidor de streaming funcionan dentro de la aplicación.
- **Multidispositivo**: Puedes ver los canales descodificados en la propia televisión o compartirlos en toda la casa simultáneamente.

---

## 2. Comparativa: Modo Módulo CI vs. Modo TVHeadend

| Característica | Modo Módulo CI (Hardware) | Modo TVHeadend Embebido (Sin Root) |
| :--- | :--- | :--- |
| **Requiere Root** | **SÍ** (acceso a `/dev/dvb` y HAL) | **NO (100% Libre de Root)** |
| **Compatibilidad** | Televisores rooteados o Android Boxes abiertas | **Cualquier TV** (Sony, Philips, TCL, Xiaomi, Chromecast) |
| **Riesgo para DRM / Garantía** | Alto (puede perder Widevine L1) | **Cero riesgo (app estándar Android)** |
| **Reproductor de Canales** | Sintonizador de fábrica de la TV | **TiviMate, Kodi, VLC o la propia app** |
| **Streaming Multi-habitación** | No (solo la propia pantalla) | **Sí** (puedes ver en el salón, móvil y PC a la vez) |
| **Guía EPG con Picos y Logos** | Básica según la TV | **XMLTV profesional completo** |

---

## 3. Guía Paso a Paso de Instalación y Uso

### Paso 1: Instalar la Aplicación en la Smart TV
1. Descarga el APK compilado (`app-release.apk` o `app-debug.apk`).
2. Pásalo a la Smart TV usando un pendrive USB o mediante la app **Send Files to TV** / **adb install app-release.apk**.
3. Abre la aplicación en tu Android TV. Verás que los servicios de segundo plano se inician automáticamente y se muestra la IP de tu televisor (por ejemplo, `192.168.1.17`).

### Paso 2: Configurar tus Servidores CCcam u OSCam desde el Móvil o PC
1. Abre el navegador web en tu ordenador, móvil o tablet conectado a la misma red WiFi.
2. Accede a la consola web:
   ```
   http://<IP_DE_TU_TELE>:8080
   Ejemplo: http://192.168.1.17:8080
   ```
3. En la pestaña **Servidores y Proveedores**:
   - Para **CCcam**: Elige protocolo `CCCAM`, pon la IP/Host del servidor, el puerto (ej: `12000`), usuario y contraseña.
   - Para **OSCam**: Elige `DVBAPI` (puerto `9000`) o `NEWCAMD`.
4. Pulsa **Probar Conexión** para verificar el handshake criptográfico auténtico.
5. Pulsa **Guardar y Aplicar Cambios**.

### Paso 3: Escanear Canales o Cargar Transpondedores Satélite
1. En la consola web, ve a la pestaña **Canales y Transpondedores** -> **Escáner TV y Validador**.
2. Selecciona la fuente deseada:
   - *Sintonizador Android TV (TvContract)*: Detecta los canales ya sintonizados en tu tele.
   - *Astra 19.2°E, Hotbird 13°E o Hispasat 30°W*: Carga las frecuencias satelitales preconfiguradas.
3. El escáner identificará automáticamente qué sistema de cifrado utiliza cada canal (**Nagravision, Viaccess, NDS VideoGuard, Conax, Seca**) y validará qué canales puede descifrar tu servidor.
4. Selecciona los canales deseados y pulsa **Importar Seleccionados a la Base de Datos**.

---

## 4. Cómo Reproducir los Canales en tu Smart TV

Una vez configurados los servidores y canales, el servidor TVHeadend integrado emite de forma continua la lista de reproducción y la guía EPG en tu red local:
- **Lista de Canales M3U**: `http://<IP_TELE>:9191/playlist.m3u` (o `http://127.0.0.1:9191/playlist.m3u` en la misma tele).
- **Guía de Programación EPG**: `http://<IP_TELE>:9191/epg.xml` (o `http://127.0.0.1:9191/epg.xml`).

### Opción A: TiviMate IPTV Player (La Mejor Experiencia en Android TV)
**TiviMate** es el reproductor más fluido y parecido a un decodificador satélite profesional para Android TV:
1. Instala **TiviMate** desde Google Play Store en tu Smart TV.
2. Abre TiviMate y selecciona **Añadir lista** -> **Lista M3U**.
3. Introduce la URL de la lista:
   ```
   http://127.0.0.1:9191/playlist.m3u
   ```
4. En el apartado de **Guía TV (EPG)**, introduce:
   ```
   http://127.0.0.1:9191/epg.xml
   ```
5. ¡Listo! Tendrás tu guía con números de canal, nombres, grupos por satélite y reproducción instantánea con descodificación en tiempo real.

### Opción B: Kodi (PVR IPTV Simple Client)
1. Abre Kodi en tu televisor o dispositivo Android TV.
2. Ve a **Add-ons** -> **Mis Add-ons** -> **Clientes PVR** -> **PVR IPTV Simple Client** y pulsa **Configurar**.
3. En la pestaña **General**:
   - Ubicación: *Ruta remota (dirección de internet)*
   - URL de la lista de reproducción M3U: `http://127.0.0.1:9191/playlist.m3u`
4. En la pestaña **Ajustes de EPG**:
   - Ubicación: *Ruta remota (dirección de internet)*
   - URL de XMLTV: `http://127.0.0.1:9191/epg.xml`
5. Pulsa OK y activa el add-on. Ve a la sección **TV** en el menú principal de Kodi.

### Opción C: VLC Media Player (En la Tele, Móvil, Tablet o PC)
1. Abre **VLC**.
2. Selecciona **Medio** (o *Navegar* en móvil) -> **Abrir emisión de red...** (Open Network Stream).
3. Escribe la URL de la lista con la IP de tu tele:
   ```
   http://192.168.1.17:9191/playlist.m3u
   ```
4. Podrás cambiar de canal libremente desde la lista de reproducción.

---

## 5. Endpoints de la API TVHeadend Integrada

El servidor en el puerto `9191` expone los endpoints estándar compatibles con el ecosistema DVB y TVHeadend:

| Endpoint | Método | Descripción |
| :--- | :--- | :--- |
| `/playlist.m3u` | `GET` | Lista M3U dinámica con etiquetas `#EXTINF`, grupos de satélite y badges CAS. |
| `/epg.xml` | `GET` | Guía electrónica de programas en formato estándar XMLTV. |
| `/stream/channel/{sid}` | `GET` | Flujo continuo MPEG-TS (`video/mp2t`) descifrado al vuelo mediante DVB-CSA. |
| `/play?url={url}` | `GET` | Proxy de descodificación para fuentes externas (SAT>IP, IPTV o grabaciones `.ts`). |
| `/api/serverinfo` | `GET` | Información del servidor emulando TVHeadend 4.3 (capacidades, versión). |
| `/api/channel/grid` | `GET` | Cuadrícula de canales en formato JSON compatible con clientes TVHeadend. |
| `/status` | `GET` | Estadísticas en tiempo real (clientes activos, bytes emitidos, ECMs/CWs resueltos). |

---

## 6. Ejecución en Segundo Plano y Autoinicio al Encender la TV

La aplicación está diseñada para funcionar de forma completamente transparente y desatendida:
- **Foreground Service con Notificación Persistente**: Evita que el gestor de memoria de Android TV cierre el proceso mientras reproduces un canal.
- **WakeLock y WifiLock**: Mantienen la CPU y la tarjeta de red activas incluso si la pantalla se apaga o entra en reposo (*Standby*).
- **Auto-inicio al encender la tele (`BOOT_COMPLETED`)**: En cuanto el televisor arranca, el servidor TVHeadend, el cliente CCcam/OSCam y la consola web se inician solos en segundo plano sin que tengas que abrir la app manualmente.

