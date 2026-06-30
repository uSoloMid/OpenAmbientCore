# 1. Resumen

`psxde/sp108e-led-controller` no es una libreria de control ni una app. Es una nota de hardware hacking para reutilizar el controlador SP108E flasheandolo con firmware alternativo como WLED, Tasmota o ESPHome.

El problema que resuelve es practico: el SP108E trae un ESP8285 y un STM32F0 que normalmente corren firmware cerrado. Con una modificacion fisica se puede mantener el STM32 en reset, exponer DATA/CLK desde el ESP y convertir la caja barata en un controlador abierto.

Hardware objetivo: SP108E v2, y comparacion con SP501E y Wemos D1 Mini. Esta poco activo, pero no necesita mucha actividad: es una ficha de hardware. Vale la pena como referencia de "hackear el controlador barato" mas que como arquitectura de software. Calificacion: **5/10** como repo; **8/10** como pista de hardware.

# 2. Arquitectura

Arquitectura original:

```
App LED Shop
  |
  | WiFi/BLE propietario
  v
ESP8285 en SP108E
  |
  v
STM32F0 auxiliar
  |
  | DATA + CLK
  v
Tira direccionable
```

Arquitectura modificada:

```
WLED / ESPHome / Tasmota
  |
  v
ESP8285
  |
  | GPIO2 -> DATA
  | GPIO13 -> CLK
  v
Tira APA102/WS2812/etc.
```

La decision es radical: en vez de reverse-engineering del protocolo cerrado, se reemplaza el firmware. Es util si aceptamos abrir/soldar el hardware; no sirve si queremos una app Android que controle dispositivos stock.

# 3. Estructura del proyecto

- `README.md`: casi todo el valor. Explica identificacion de version, modificacion fisica, pines y ejemplo `platform_override.ini` para WLED.
- Imagenes: referencias visuales del PCB y conexiones.

No hay logica BLE, WiFi, protocolo, UI, procesamiento ni discovery.

# 4. Hardware soportado

Comparativa documentada:

- SP108E: ESP8285, 2 MB flash, DATA, CLK, level shifter, requiere hardware mod.
- SP501E: ESP, DATA sin CLK, salidas analogicas 5 canales RGB+CCT, no requiere mod para algunos hacks.
- Wemos D1 Mini: referencia generica, mas flash, sin caja ni level shifter.

Transportes tras flashear dependen del firmware elegido:

- WLED: WiFi, UDP, HTTP/JSON, MQTT opcional, mDNS.
- ESPHome: WiFi, API nativa, MQTT opcional.
- Tasmota: WiFi, MQTT/HTTP.

# 5. Protocolos

El repo no documenta el protocolo stock del SP108E. Propone saltarselo. Una vez flasheado con WLED, el protocolo pasa a ser WLED UDP/JSON/DDP/E1.31, ya analizado en `wled.md`.

# 6. Latencia

Con firmware stock: no analizada.

Con WLED: si puede ser tiempo real via UDP/DDP. La latencia depende del ESP8285 y la red WiFi. Para tiras cortas puede funcionar bien; para muchas zonas/pixeles, ESP32 seria mejor.

# 7. Renderizado

El renderizado no ocurre en este repo. Si se usa WLED, WLED representa segmentos y LEDs individuales. Si se usa ESPHome/Tasmota, normalmente color global o efectos simples.

# 8. Descubrimiento

No aplica al repo. Con WLED se usaria mDNS/UDP/API; con ESPHome, discovery propio; con Tasmota, mDNS/MQTT/manual.

# 9. Diseno

No hay arquitectura software. La decision de diseno es hardware-first: reemplazar firmware cerrado por firmware abierto. Es una forma valida de reducir complejidad de software a cambio de trabajo fisico.

# 10. Lo bueno

- Muestra que algunos controladores baratos ya traen un ESP utilizable.
- Permite convertir hardware cerrado en receptor abierto compatible con WLED.
- Barato: reutiliza caja, alimentacion y level shifter.

# 11. Lo malo

- Requiere soldar y modificar placa.
- No aplica a usuarios que no quieran abrir el controlador.
- No ayuda a controlar dispositivos stock.
- ESP8285/2 MB es limitado frente a ESP32.

# 12. Que podemos reutilizar

- Idea de "modo avanzado": recomendar firmware abierto cuando el usuario acepte modificar hardware.
- Separar estrategia `StockControllerDriver` vs `FlashedOpenFirmwareDriver`.
- Considerar SP108E como hardware barato candidato para WLED si el presupuesto manda.

# 13. Compatibilidad con nuestro proyecto

Para OpenAmbientCore hay dos caminos:

```
Android TV -> WiFi UDP -> SP108E flasheado con WLED -> tira
```

o:

```
Android TV -> BLE/WiFi propietario -> SP108E stock -> tira
```

Este repo solo ayuda con el primer camino. Para menos de $500 MXN puede ser atractivo si se consigue el SP108E barato, pero exige soldadura. Para una app masiva de Android TV, soportar stock BLE/BanlanX sera mas amigable.

# 14. Resultado

★★★★☆ Excelente pista de hardware barato hackeable.

★★☆☆☆ Casi nulo como arquitectura de app.

★★★☆☆ Util si decidimos soportar un perfil "WLED DIY".

