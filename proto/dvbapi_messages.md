## Referencia de opcodes dvbapi usados en este proyecto

Extraído de `oscam-dvbapi.c` y verificado contra las implementaciones de
TVHeadend y VDR-sc. Los valores hexadecimales son los que van en el wire
(big-endian); los nombres en C++ se definen en `DvbapiProtocol.h`.

---

### Mensajes Bridge → OSCam

#### `DVBAPI_CLIENT_INFO` = `0xFFFF0000`

Primer mensaje enviado al conectar. Anuncia la versión del protocolo y el
nombre del cliente.

```
[ uint32 opcode=0xFFFF0000 ][ uint16 protover ][ uint8 namelen ][ uint8[namelen] name ]
```

**Protocolo version 3**: soporta `CA_SET_DESCR_MODE` (algoritmos alternativos
como AES-128). Usar siempre versión 3.

---

#### `DVBAPI_CA_SET_PID` = `0x40086f87`

Registra (o desregistra) un PID de ECM/servicio en un slot CA del adaptador.

```
[ uint32 opcode=0x40086F87 ][ uint8 adapter ][ uint32 pid ][ int32 index ]
```

- `index = -1`: desregistrar el PID (liberar el slot).
- `index >= 0`: número de slot CA (normalmente 0).
- Enviar este mensaje **antes** de `DMX_SET_FILTER`.

---

#### `DVBAPI_DMX_SET_FILTER` = `0x403C6F2B`

Programa un filtro de sección en el demultiplexor para que OSCam reciba las
secciones ECM.

```
[ uint32 opcode ][ uint8 adapter ][ uint8 demux ][ uint8 filter_num ]
[ uint16 pid ]
[ uint8[16] filter ][ uint8[16] mask ][ uint8[16] mode ]
[ uint32 timeout ][ uint32 flags ]
```

Longitud total: **65 bytes**.

- `filter[0]` = `0x80` → ECM par  
- `filter[0]` = `0x81` → ECM impar  
- `mask[0]` = `0xFE` → capturar ambos (0x80 y 0x81)
- `flags` = `1` (`DMX_IMMEDIATE_START`)

---

#### `DVBAPI_DMX_STOP` = `0x00006F2A`

Detiene y libera un filtro DMX previamente configurado.

```
[ uint32 opcode=0x00006F2A ][ uint8 adapter ][ uint8 demux ][ uint8 filter_num ][ uint16 pid ]
```

Longitud total: **9 bytes**.

Enviar este mensaje al cambiar de canal o cerrar la sesión.

---

### Mensajes OSCam → Bridge

#### `DVBAPI_SERVER_INFO` = `0xFFFF0001`

Respuesta de OSCam al `CLIENT_INFO`. Anuncia el nombre y versión del servidor.

```
[ uint32 opcode=0xFFFF0001 ][ uint16 protover ][ uint8 namelen ][ uint8[namelen] name ]
```

---

#### `DVBAPI_CA_SET_DESCR` = `0x40106F86`

Entrega el Control Word descifrado. Mensaje más importante del protocolo.

```
[ uint32 opcode=0x40106F86 ][ int32 index ][ int32 parity ][ uint8[8] cw ]
```

Longitud total: **20 bytes**.

- `index`: slot CA (mismo que el enviado en `CA_SET_PID`)
- `parity = 0`: even key (CW par)
- `parity = 1`: odd key (CW impar)
- `cw`: 8 bytes del Control Word DVB-CSA

El descrambler hardware necesita los dos CW (even + odd) para descifrar
el transport stream. Normalmente OSCam los envía consecutivamente.

---

#### `DVBAPI_CA_SET_DESCR_MODE` = `0x400C6F88`

Anuncia el algoritmo de descrambling antes de enviar el CW (protocolo v3+).

```
[ uint32 opcode=0x400C6F88 ][ int32 index ][ uint32 algo ][ uint32 mode ]
```

Longitud total: **16 bytes**.

Valores de `algo`:
- `0` = DVB-CSA (estándar)
- `1` = AES-128 CBC
- `2` = AES-128 ECB
- `3` = DES

Si tu sistema CA es estándar DVB-CSA, este mensaje puede llegar o no;
el bridge debe ignorarlo sin romperse si no lo necesita.

---

### Flujo típico de resolución de ECM

```
Bridge → OSCam:  CLIENT_INFO  (handshake)
OSCam  → Bridge: SERVER_INFO  (handshake OK)

Bridge → OSCam:  CA_SET_PID  (adapter=0, pid=0x0600, index=0)
Bridge → OSCam:  DMX_SET_FILTER  (adapter=0, demux=0, filter=0, pid=0x0600)

[OSCam detecta el ECM en el PID filtrado]
[OSCam usa el reader (tarjeta) para descifrar]

OSCam  → Bridge: CA_SET_DESCR_MODE  (opcional, si proto v3)
OSCam  → Bridge: CA_SET_DESCR  (parity=0, cw=even)
OSCam  → Bridge: CA_SET_DESCR  (parity=1, cw=odd)

[El bridge inyecta los CW en el hardware descrambler]
[El transport stream se descifra en tiempo real]
```

---

### Notas de implementación

- OSCam espera recibir `CA_SET_PID` **antes** de que llegue el ECM real.
  En la implementación del HAL (Fase 3), debes enviarlo en `openSession()`
  tan pronto como conoces el PID del servicio.

- El filtro `DMX_SET_FILTER` debe configurarse con los bytes exactos del
  ECM de tu sistema CA. Si usas Nagravision, `table_id=0x80/0x81`. Si usas
  Conax, `table_id=0x80/0x81` también. Irdeto usa `table_id=0x80`. Verifica
  con `dvbsnoop -s ecm` en el adaptador DVB.

- **DEVICE-SPECIFIC**: En Android TV, el proceso que llama a `DMX_SET_FILTER`
  suele ser el Tuner HAL propio del SoC (no el framework). En la Fase 2
  veremos cómo el `IChipsetAdapter` expone el filtrado DMX de cada SoC.
