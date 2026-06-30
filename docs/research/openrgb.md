# 1. Resumen

OpenRGB (`CalcProgrammer1/OpenRGB`) es una aplicacion multiplataforma para controlar hardware RGB de PC sin software propietario. Resuelve fragmentacion de fabricantes: placas madre, RAM, GPUs, teclados, ratones, tiras internas, controladores USB/HID/SMBus y mas.

Hardware objetivo: ecosistema PC RGB, no Android TV ni tiras baratas BLE. Esta muy activo y enorme. Vale la pena estudiarlo por su arquitectura de "muchos dispositivos, una API comun", no por Ambilight. Calificacion: **8/10** para arquitectura de drivers; **3/10** para nuestro hardware objetivo.

# 2. Arquitectura

```
OpenRGB UI / CLI / SDK client
  |
  v
ResourceManager
  |
  | detectors
  v
RGBController abstraction
  |
  v
Vendor controller
  |
  | USB / HID / SMBus / I2C / Serial / Network
  v
RGB zones / LEDs
```

Tambien puede correr como servidor:

```
External app -> OpenRGB SDK TCP -> OpenRGB -> hardware
```

Eligieron esta arquitectura porque el problema central es multi-vendor. Cada dispositivo tiene protocolo propio, pero todos se exponen como zones, modes, colors y LEDs.

# 3. Estructura del proyecto

- `Controllers/*`: drivers por fabricante/dispositivo. Es el equivalente a una coleccion de `ProtocolDriver`.
- `RGBController/*`: abstraccion comun de dispositivos RGB, zonas, modos y LEDs.
- `DeviceDetector.h`, `Detector.h`: discovery/deteccion de hardware.
- `ResourceManager.*`: registro, ciclo de vida y acceso a controladores.
- `NetworkProtocol.*`, `NetworkServer.*`, `NetworkClient.*`: SDK TCP para control remoto.
- `PluginManager.*`, `OpenRGBPluginInterface.h`: extensibilidad.
- `qt/*`: UI.
- `hidapi_wrapper`, `i2c_smbus`, `serial_port`, `net_port`: capas de transporte/acceso hardware.

# 4. Hardware soportado

Miles de variantes en categorias:

- Placas madre RGB.
- RAM RGB.
- GPUs.
- Teclados/ratones/mousepads/headsets.
- AIO/fans/controladores internos.
- Monitores y perifericos.

Transportes: USB, HID, SMBus/I2C, serial, red TCP SDK, algunos HTTP/red segun dispositivo. BLE no es el foco. MQTT/UDP Ambilight no es el centro.

# 5. Protocolos

Cada controlador vendor habla su protocolo. OpenRGB normaliza encima:

- SDK TCP propio para apps externas.
- Estructuras comunes: device, zone, LED, mode, color.
- Acceso bajo nivel via HID reports, USB control transfers, SMBus/I2C, serial y red.

La leccion: exponer una API interna comun aunque los protocolos inferiores sean caoticos.

# 6. Latencia

Puede actualizar LEDs en tiempo relativamente rapido y existen plugins de efectos/visualizacion, pero el core no esta optimizado como pipeline de video Ambilight. Depende del hardware: algunos dispositivos PC RGB tienen rate limits fuertes.

No hay un unico FPS garantizado. Buffering/interpolacion pertenecen a plugins o clientes externos.

# 7. Renderizado

Modelo fuerte de:

- Device.
- Zone.
- LED individual.
- Mode/effect.
- Color array.

Esto si es reutilizable conceptualmente: nuestro `LedLayout` puede parecerse a zones/LEDs, pero con coordenadas de pantalla.

# 8. Descubrimiento

- Enumeracion USB/HID.
- SMBus/I2C scan.
- Serial/network segun controlador.
- Detectores por vendor/product ID.
- Algunos dispositivos requieren permisos del sistema.

# 9. Diseno

- Adapter/Strategy por controlador.
- Factory/registry via detectores.
- Facade `ResourceManager`.
- Plugin architecture.
- Client/server SDK.
- UI separada del core.

# 10. Lo bueno

- Excelente separacion entre driver vendor y modelo comun.
- Modelo `zones + LEDs + modes`.
- SDK para control externo.
- Plugin system.
- Advertencia honesta sobre riesgo de reverse engineering hardware.

# 11. Lo malo

- Demasiado grande.
- Centrado en PC, permisos y buses no disponibles en Android TV.
- No ayuda con MediaProjection.
- BLE barato casi no aparece.

# 12. Que podemos reutilizar

- `Device -> Zone -> Led` como modelo generico.
- Registry de drivers.
- Separar discovery, protocol y output.
- Una API interna estable aunque los controladores cambien.
- Posible futuro: modo server local para integraciones externas.

# 13. Compatibilidad con nuestro proyecto

OpenRGB no sera backend principal. Sirve para disenar la capa:

```
DeviceRegistry
  -> discovered devices
  -> capabilities
  -> zones
  -> driver
```

Para Ambilight barato, Hyperion ensena el pipeline; UniLED ensena BLE barato; OpenRGB ensena el modelo multi-driver.

# 14. Resultado

★★★★☆ Excelente arquitectura multi-driver.

★★☆☆☆ Poco aplicable al hardware Android TV/BLE barato.

★★★★☆ Muy bueno para modelar zonas y capacidades.

