# 1. Resumen

Aqui hay dos repos complementarios: `Beshelmek/govee_ble_lights` como integracion Home Assistant y `egold555/Govee-Reverse-Engineering` como base documental de reversing. Govee es una familia comercial amplia: tiras, barras, lamparas, sensores, plugs y dispositivos RGBIC con BLE, API cloud y en algunos modelos LAN/API.

El problema resuelto es controlar dispositivos Govee sin depender siempre de la app oficial. El hardware objetivo incluye modelos H600x, H605x, H607x, H610x, H613x, H617x, H619x, H70xx y otros en los JSON de escenas; la documentacion de reversing incluye H6053, H6072, H6102, H6127, H6199, H619D, etc.

Vale la pena estudiarlo para protocolo BLE y escenas segmentadas, pero no es ideal para Ambilight barato en Mexico si el presupuesto es menor a $500 MXN: Govee suele ser mas caro que BanlanX/SP/ELK. Calificacion: **7/10**.

# 2. Arquitectura

Modo BLE:

```
Home Assistant
  |
  v
GoveeBluetoothLight
  |
  | build packet + checksum
  v
BLE characteristic
  |
  v
Govee device
  |
  v
RGB/RGBIC LEDs
```

Modo API cloud:

```
Home Assistant
  |
  v
GoveeAPI
  |
  | HTTPS + API key
  v
Govee cloud
  |
  v
Device
```

La arquitectura es doble porque Govee no tiene un unico camino universal. BLE sirve para control local, cloud sirve para modelos/capacidades oficiales pero agrega latencia y dependencia externa.

# 3. Estructura del proyecto

`govee_ble_lights`:

- `light.py`: entidades API y BLE, construccion de comandos BLE, escenas, brillo, color, segmentacion.
- `govee_api.py`: llamadas cloud.
- `config_flow.py`: seleccion API/BLE, discovery BLE y seleccion manual de modelo.
- `jsons/*.json`: catalogo de escenas/modelos; muy importante para entender parametros por SKU.
- `govee_utils.py`: empaquetado de payloads grandes en multiples paquetes.

`Govee-Reverse-Engineering`:

- `Products/*.md`: notas por modelo.
- `Products/H5080/*`, `H5082/*`: scripts de pairing/scan/control para productos concretos.

# 4. Hardware soportado

En la integracion BLE hay muchos JSON de modelos, incluyendo H6006, H6009, H6010, H6046, H6053, H6072, H6102, H6138, H6144, H617x, H618x, H6199, H61A*, H660*, H70xx, etc.

Transportes:

- BLE: si.
- Cloud HTTP API: si.
- LAN API: no es el foco de este repo.
- UDP/MQTT/USB/Serial: no para Govee en este repo.

# 5. Protocolos

BLE principal observado:

- Characteristic de control: `00010203-0405-0607-0809-0a0b0c0d2b11`.
- Paquete corto de 20 bytes: empieza con `0x33`, luego comando, payload, padding a 19 bytes y checksum XOR como byte 20.
- Comandos basicos: power `0x01`, brightness `0x04`, color `0x05`.
- Modos: manual `0x02`, microphone `0x06`, scenes `0x05`, segments `0x15`.
- Payload largo: se divide en multiples paquetes, usado para escenas con parametros base64 desde JSON.

API cloud:

- HTTPS con API key.
- Capacidades por device/SKU: power, brightness, colorRgb, colorTemperatureK, lightScene.

# 6. Latencia

BLE local puede ser relativamente rapido para comandos discretos, pero no esta disenado aqui como streaming de video. El limite de 20 bytes y el particionado de payloads hacen mala idea enviar muchos LEDs individuales a 30 FPS.

Cloud no sirve para Ambilight en tiempo real por latencia, rate limits y dependencia de Internet.

No hay smoothing de video. Hay escenas/efectos internos del dispositivo y segmentacion en modelos concretos.

# 7. Renderizado

Modelos no segmentados: color global RGB.

Modelos segmentados (`H6053`, `H6072`, `H6102`, `H6199` en el repo): comando de color en modo segmentos con mascara/parametros. Las escenas se representan como parametros propietarios codificados en los JSON de modelo.

La conversion ocurre en `light.py`: atributos HA -> payload Govee. No hay conversion de imagen a zonas.

# 8. Descubrimiento

- BLE discovery de Home Assistant.
- Manifest con filtro `local_name: Govee_*`.
- Seleccion manual de modelo, porque el nombre BLE no siempre basta.
- API mode requiere API key y listado cloud.

# 9. Diseno

- Adapter: entidad HA para API y entidad HA para BLE.
- Strategy parcial: dos caminos, cloud y BLE.
- Data-driven scenes: JSON por modelo.
- Retry connector para BLE.
- Poco aislamiento entre protocolo y entidad UI; se podria separar mejor.

# 10. Lo bueno

- Packet framing BLE claro: header, payload, padding, XOR.
- Catalogo JSON por modelo: buena idea para separar capacidades/escenas.
- Reconoce modelos segmentados.
- Modo BLE local y modo API cloud en la misma integracion.

# 11. Lo malo

- La logica BLE vive muy mezclada con Home Assistant.
- No es tiempo real.
- Govee cambia protocolos por modelo.
- Cloud API no encaja con Ambilight.
- Mucha dependencia de JSON de escenas opacos.

# 12. Que podemos reutilizar

- `ModelProfile` data-driven por SKU.
- Separar `CloudDriver` y `LocalBleDriver`.
- Packet builder con checksum.
- Idea de `SegmentedColorCommand` para RGBIC comerciales.

# 13. Compatibilidad con nuestro proyecto

Govee sirve si queremos soportar dispositivos comerciales ya comprados por usuarios, pero no seria mi primera opcion para el mejor DIY bajo $500 MXN.

Para OpenAmbientCore:

```
Zone colors -> si modelo segmentado: comando segmentos
Zone colors -> si no segmentado: color global promedio
```

No usaria cloud para Ambilight; solo BLE/local.

# 14. Resultado

★★★★☆ Bueno para BLE comercial y segmentacion propietaria.

★★★☆☆ Util como compatibilidad secundaria.

★★☆☆☆ Cloud/API no sirve para Ambilight real-time.

