# 1. Resumen

WLED (Aircoookie/WLED) es un firmware de código abierto en C++ para microcontroladores ESP32/ESP8266 que convierte una placa barata en un controlador de tiras LED direccionables (WS2812, SK6812, APA102, etc.) con control web, efectos visuales, y sincronización en tiempo real desde fuentes externas (PC, apps, Hyperion, HomeAssistant, etc.). Es, de facto, el estándar de la comunidad de iluminación LED casera: cualquier app de Ambilight de terceros que "soporte WLED" en realidad habla uno de los protocolos UDP que este firmware implementa.

El hardware objetivo es explícitamente ESP32/ESP8266 — WLED *es* el firmware que correría en ese microcontrolador, no un cliente externo. No fue diseñado para hablar con controladores RGBIC comerciales de Aliexpress (esos llevan su propio firmware cerrado, normalmente controlado por apps tipo "Govee"/"Magic Home" vía Bluetooth o WiFi propietario).

El proyecto está muy activo (commits recientes, ramas de desarrollo para HUB75, ESP-NOW, mDNS, etc., visibles en el código explorado).

Vale la pena estudiarlo, pero no por su firmware en sí (no aplica a Android ni a un controlador comercial), sino por su protocolo de streaming UDP y su modelo de segmentos, que son ideas de diseño completamente portables a un sistema cliente-servidor propio. Calificación como fuente de referencia de protocolo: **8/10**. Como modelo de arquitectura para nuestro caso (Android TV + controlador RGBIC barato): **4/10**, porque la mayor parte del código es específico de ESP32 (GPIO, RMT, PWM, FastLED) y de los protocolos de descubrimiento que solo otros nodos WLED entienden.

# 2. Arquitectura

```
                    +------------------+
                    |  PC / App/Hyperion|
                    |  (fuente de color) |
                    +---------+--------+
                              |
                 UDP unicast/broadcast
        (WARLS / DRGB / DNRGB / DDP / Art-Net / E1.31)
                              |
                              v
                +--------------------------+
                |     ESP32 / ESP8266      |
                |   firmware WLED          |
                |  - udp.cpp (recepción)   |
                |  - e131.cpp (DDP/ArtNet) |
                |  - FX.h (Segment model)  |
                |  - bus_manager (salida)  |
                +-----------+--------------+
                            |
                  bus físico (RMT/PWM/SPI)
                            |
                            v
                 +----------------------+
                 |  WS2812 / SK6812 /   |
                 |  APA102 / HUB75      |
                 +----------------------+
```

Por qué esta arquitectura: WLED asume que el "cerebro de protocolo" vive en el mismo chip que maneja la tira físicamente. Esto tiene sentido para ESP32 (suficiente RAM/CPU, acceso directo a GPIO con periférico RMT) pero es exactamente el patrón que **no** queremos copiar 1:1: en OpenAmbientCore el "cerebro" (captura de pantalla + procesamiento de color por zonas) vive en el Android TV, y el controlador RGBIC barato solo necesita recibir un comando final de color — es un dispositivo "tonto" en comparación con un nodo WLED. Aun así, el contrato de red (qué se envía por UDP) es reutilizable aunque el receptor sea un MCU mucho más simple que no entiende segmentos completos, JSON, ni mDNS.

# 3. Estructura del proyecto

Carpeta núcleo: `wled00/`. Archivos relevantes para nuestro caso de estudio:

- **Protocolo de tiempo real / UDP**: `wled00/udp.cpp` — implementa el "UDP Realtime" (WARLS/DRGB/DRGBW/DNRGB/DNRGBW), el "UDP Notifier" interno (sincronización entre nodos WLED), TPM2.NET, ESP-NOW, y el envío saliente de DDP/Art-Net (`realtimeBroadcast()`).
- **E1.31 / DDP / Art-Net (recepción)**: `wled00/e131.cpp` — parsea paquetes DDP y Art-Net/sACN entrantes y los traduce a píxeles o a canales DMX (`handleE131Packet`, `handleDDPPacket`, `handleDMXData`).
- **Modelo de segmentos / zonas**: `wled00/FX.h` (clase `Segment`, líneas ~419 en adelante) y `wled00/FX_fcn.cpp` / `FX_2Dfcn.cpp` para la lógica de mapeo 1D/2D. No mirar `FX.cpp` línea por línea (son ~700KB de fórmulas de efectos individuales, irrelevantes para nuestro propósito).
- **Abstracción de bus físico**: `wled00/bus_manager.h` / `bus_manager.cpp` — clase `Bus` (interfaz abstracta) y subclases `BusDigital`, `BusPwm`, `BusOnOff`, `BusNetwork`, `BusHub75Matrix`.
- **API HTTP/JSON**: `wled00/json.cpp` (serialización/deserialización de estado: `deserializeState`, `serializeState`, `serializeInfo`) y `wled00/wled_server.cpp` (rutas HTTP, p. ej. `server.on(FPSTR(_json), ...)`, handler de `/json` con `AsyncCallbackJsonWebHandler`). La API legacy basada en query-string vive en `wled00/set.cpp` (`handleSet`).
- **Discovery / mDNS**: `wled00/wled.cpp` (líneas ~856-866, `MDNS.begin(cmDNS)`, `MDNS.addService("http","tcp",80)`, `MDNS.addService("wled","tcp",80)`) y `wled00/network.cpp` para resolución de hostname.
- **Nodos / descubrimiento entre dispositivos WLED**: estructura `NodeStruct.h` y el "Node discovery" dentro de `udp.cpp` (`sendSysInfoUDP()`, `refreshNodeList()`), que es un broadcast UDP propietario distinto de mDNS.

# 4. Hardware soportado

- MCUs: ESP8266 y toda la familia ESP32 (ESP32, ESP32-S2, ESP32-S3, ESP32-C3), visible en las macros de `udp.cpp` (`NODE_TYPE_ID_ESP32C3`, etc.) y en `bus_manager.h`.
- Tipos de tira/salida (constantes `TYPE_*` en `bus_manager.h`): WS2812 (1/2/3/4 canales, incluyendo variantes WWA con blanco cálido/frío), SK6812 RGBW, APA102/SPI ("2-pin" types), TM1814, UCS8903/8904, FW1906, WS2805, SM16825, salidas analógicas/PWM (1-5 canales), salida on/off simple, y matrices HUB75 (`BusHub75Matrix`).
- Protocolos de entrada soportados como fuente de color en tiempo real: UDP propio (WARLS/DRGB/DRGBW/DNRGB/DNRGBW), DDP, E1.31 (sACN), Art-Net, TPM2.NET, Hyperion (formato "raw RGB" sobre UDP, ver `udpRgbConnected` en `udp.cpp`), MQTT (`mqtt.cpp`, para comandos JSON, no para streaming de píxeles), HTTP/JSON, y ESP-NOW (sincronización inalámbrica directa entre placas WLED sin pasar por el router).

# 5. Protocolos

**WARLS** (`udpIn[0]==1`): cada "registro" de 4 bytes es `[índice_LED, R, G, B]`. Permite actualizar LEDs sueltos no contiguos en un mismo paquete (útil para diffs pequeños), pero limita a 255 LEDs por índice de 1 byte y no lleva blanco.

**DRGB** (`udpIn[0]==2`) y **DRGBW** (`udpIn[0]==3`): tras 2 bytes de cabecera (protocolo + timeout en segundos), el resto del paquete es una secuencia plana de tripletes/cuádruples RGB(W) en orden, empezando siempre en el LED 0. Es el formato más simple: "todo el frame, en orden".

**DNRGB/DNRGBW** (`udpIn[0]==4/5`): igual que DRGB pero con un offset inicial de 2 bytes antes de los datos de color — permite empezar a escribir en cualquier LED, útil para actualizar solo una porción de una tira larga sin reenviar todo el frame.

**Cabecera común** de estos 5 modos: 1 byte de tipo de protocolo + 1 byte de "timeout" en segundos (cuánto debe esperar el firmware antes de volver al modo normal si dejan de llegar paquetes — implementa el "watchdog" de tiempo real).

**DDP** (Distributed Display Protocol, recibido y emitido en `udp.cpp`/`e131.cpp`): cabecera con flags (PUSH, QUERY, STORAGE, TIME), tipo de dato (RGB24/RGBW32), número de secuencia (4 bits, para descartar paquetes fuera de orden), offset de canal (dónde empieza este paquete dentro del frame total) y longitud de datos — soporta frames grandes partidos en múltiples paquetes UDP, con el flag PUSH marcando "este es el último paquete, renderiza ya".

**E1.31/sACN y Art-Net**: protocolos DMX-sobre-Ethernet estándar de la industria de iluminación de espectáculos. WLED los traduce a universos DMX de 512 canales, mapeables a modos como RGB simple, RGB+dimmer, o incluso control de "efecto completo" por canales DMX (`DMX_MODE_EFFECT*` en `e131.cpp`).

**API HTTP/JSON**: endpoint `/json` (servido en `wled_server.cpp`) acepta `GET` (devuelve estado completo) y `POST` con un cuerpo JSON que pasa por `deserializeState()`/`deserializeConfig()` en `json.cpp`. El JSON de estado incluye brillo global (`bri`), transición (`transition`), y un array `seg[]` con un objeto por segmento (inicio/fin, color, modo de efecto, velocidad, intensidad, paleta, opacidad). También existe una API legacy basada en query string (`handleSet` en `set.cpp`, p. ej. `/win&A=128`) heredada de versiones anteriores a la API JSON.

# 6. Latencia

Sí es pensado para tiempo real: el bucle de paquetes UDP de streaming se procesa fuera del ciclo normal de renderizado de efectos y dispara `strip.show()` inmediatamente tras aplicar los píxeles recibidos (ver el final de cada rama en `handleNotifications()` en `udp.cpp`). No hay cola ni "smoothing" de color en el camino de streaming externo: cada paquete sobrescribe directamente el buffer de píxeles y se muestra.

Sí existe un mecanismo de timeout/watchdog: cada paquete UDP de realtime lleva un byte de "duración" que define cuánto tiempo el firmware se queda en modo "realtime lock" (`realtimeLock()`) antes de volver a efectos normales si deja de recibir tráfico — no es interpolación, es solo expiración de modo.

Para DDP/E1.31/Art-Net hay lógica de control de secuencia (`e131SkipOutOfSequence`) que descarta paquetes que llegan tarde/fuera de orden, evitando "saltos hacia atrás" visuales, pero tampoco interpola entre frames.

Las transiciones suaves (`transitionDelay`, blending de paletas) existen para cambios de **modo/efecto/color desde la UI o notificaciones internas**, no para el streaming de píxeles en bruto — en ese camino la filosofía es "cuanto más directo, mejor" para minimizar latencia.

No hay un número de paquetes/segundo documentado en el código en sí (depende del emisor); el límite real es el tamaño máximo de paquete UDP que el firmware acepta (`UDP_IN_MAXSIZE = 1472`, es decir, MTU típica de Ethernet menos cabeceras).

# 7. Renderizado

El modelo de la tira no es "una tira plana de N LEDs" únicamente: es una colección de **segmentos** (clase `Segment` en `FX.h`), donde cada segmento tiene su propio rango (`start`/`stop`, y `startY`/`stopY` para 2D), agrupamiento (`grouping`, `spacing` — para tratar varios LEDs físicos como uno lógico), desplazamiento (`offset`), opacidad, color(es), modo de efecto, velocidad/intensidad/paleta, y banderas de orientación (`reverse`, `mirror`, `transpose` para 2D).

Cada segmento tiene su propio buffer de píxeles (`pixels` en `Segment`) que se combina (blending) sobre el buffer final de la tira física a través del `BusManager`. Esto permite, por ejemplo, tener tres segmentos con tres efectos distintos corriendo simultáneamente sobre la misma tira física.

El soporte 2D (matrices, paneles HUB75) se modela como un caso particular: un segmento puede tener alto/ancho (`virtualWidth()`/`virtualHeight()`) y un modo de mapeo (`map1D2D`) que decide cómo un efecto pensado para una tira 1D se "despliega" sobre una matriz (barra vertical, arco circular, esquina rectangular, etc.) — la conversión vive en `FX_2Dfcn.cpp`.

La conversión final de "color lógico de segmento" a "señal eléctrica del bus" ocurre en `bus_manager.cpp`/`bus_manager.h`, donde la clase abstracta `Bus` expone `setPixelColor()`/`show()` y las subclases (`BusDigital`, `BusPwm`, `BusNetwork`, `BusHub75Matrix`) implementan el detalle físico (protocolo de la tira, PWM, salida en red, matriz HUB75).

# 8. Descubrimiento

Tres mecanismos distintos, cada uno para un propósito distinto:

1. **mDNS** (Bonjour/Zeroconf), en `wled.cpp`: el dispositivo se anuncia como `<hostname>.local` y publica dos servicios, `_http._tcp` (puerto 80, para que cualquier cliente HTTP/navegador lo encuentre) y `_wled._tcp` (con un TXT record con la MAC), pensado para que apps de terceros (y otros nodos WLED) descubran instancias WLED en la red local sin conocer la IP.
2. **UDP broadcast propietario de "node list"**: `sendSysInfoUDP()`/`refreshNodeList()` en `udp.cpp` — cada nodo WLED emite periódicamente un paquete broadcast UDP con su IP, nombre, tipo de placa y versión; otros nodos WLED mantienen un mapa (`NodesMap`) de unidades vistas recientemente, con expiración (`age`). Esto es independiente de mDNS y solo lo entienden otros WLED.
3. **IP manual / configuración estática**: en última instancia, todo (UI web, apps, integraciones Home Assistant) puede simplemente apuntar a una IP fija si el descubrimiento automático falla o no es deseado.

# 9. Diseño

Patrones reales identificables en el código (no necesariamente con esos nombres en WLED, pero el patrón está presente):

- **Strategy / polimorfismo de salida física**: la clase abstracta `Bus` con métodos virtuales puros (`show()`, `setPixelColor()`) y subclases concretas por tipo de hardware es un Strategy/Bridge clásico — desacopla "qué color debe tener el píxel N" de "cómo se manda esa señal a ese hardware específico".
- **Composición de segmentos sobre buffer único**: cada `Segment` es casi un "mini lienzo" independiente con su propio efecto, color y buffer de píxeles, que luego se combinan (blend) en el buffer final del `BusManager` — similar a capas en edición de imagen.
- **Versión de compatibilidad incremental en el protocolo binario**: el paquete UDP "Notifier" lleva un byte de "compatibilityVersionByte" (`udpOut[11]` en `notify()`, `udp.cpp`) que indica qué campos adicionales contiene el paquete, permitiendo que firmwares viejos y nuevos convivan en la misma red sin romper el parsing — es un patrón de evolución de protocolo binario hacia atrás compatible, bastante elegante para un firmware embebido.
- **Command/State machine implícita de "modo realtime"**: `realtimeLock()`/`exitRealtime()` actúan como una máquina de estados de dos modos (efectos locales vs. control externo en tiempo real) con timeout automático, evitando que un emisor externo "secuestre" la tira para siempre si se desconecta sin avisar.
- **Buffer de doble propósito en segmentos durante transición**: la struct `Transition` dentro de `Segment` guarda una copia del segmento anterior (`_oldSegment`) mientras dura el cross-fade — un patrón de "snapshot" para animaciones de transición sin bloquear el hilo principal.

# 10. Lo bueno

- El **modelo de segmentos** (rango + agrupamiento + espaciado + offset + orientación, todo independiente del efecto que corre encima) es la idea más reutilizable de todo el repo: es exactamente la abstracción que necesitamos para mapear "zonas de la pantalla" a "rangos físicos de la tira" en nuestro propio sistema, incluyendo casos raros como LEDs agrupados de 2-en-2 o tiras que dan la vuelta a una esquina.
- El **principio de los protocolos UDP de streaming** (DRGB/DNRGB/DDP): un paquete simple, sin handshake, con un byte de cabecera que indica el formato y, en el caso de DNRGB/DDP, un offset para actualizaciones parciales — minimalismo deliberado para baja latencia.
- El **timeout embebido en el propio paquete** (byte de duración en WARLS/DRGB/DNRGB) es una idea muy simple y efectiva para que el receptor sepa cuándo "soltar" el control sin necesidad de un mensaje explícito de "fin de sesión".
- La **versión de compatibilidad en el protocolo binario** (un byte que indica qué tan "moderno" es el paquete) es una técnica sólida si en algún momento decidimos versionar nuestro propio protocolo binario.
- El **desacople Bus/Segment**: separar "qué quiero mostrar" (segmento/color) de "cómo lo mando al hardware" (bus) es una buena referencia de capas incluso fuera de C++.

# 11. Lo malo

- Todo el manejo de paquetes usa **arrays de bytes con offsets mágicos hardcodeados** (`udpIn[3+ofs]`, etc.) en lugar de structs serializadas o un esquema (protobuf/flatbuffers/JSON). Es comprensible en un ESP8266 con poquísima RAM, pero es frágil y propenso a errores de desfase — no es un patrón a copiar en una app Android donde sobra RAM y CPU para usar serialización segura (protobuf, Cap'n Proto, o incluso JSON/MessagePack).
- El parsing de protocolos de red (`udp.cpp`, `e131.cpp`) está fuertemente acoplado al estado global del firmware (variables globales como `bri`, `strip`, `realtimeMode`) — funciona en un firmware monolítico de un solo hilo, pero sería un antipatrón en una arquitectura Android con múltiples componentes/coroutines.
- La compatibilidad retroactiva por "versionByte" obliga a mantener ramas `if (version > N)` que se acumulan con los años (visible en `parseNotifyPacket`) — funcional, pero ilustra el costo de no versionar con un esquema más explícito desde el principio.
- Gran parte del código de discovery (mDNS + node-list UDP propietario) solo tiene sentido si **ambos extremos son firmware WLED**; no resuelve nuestro problema de descubrir un controlador RGBIC genérico de un fabricante distinto.
- No hay cifrado ni autenticación en ninguno de los protocolos UDP de streaming — aceptable para una red doméstica de confianza, pero algo a tener en cuenta si nuestro diseño alguna vez se expone más allá de la LAN.

# 12. Qué podemos reutilizar

- **Modelo de zonas/segmentos**: representar cada zona de captura de pantalla (ej. "borde superior", "borde izquierdo") como un segmento lógico con su propio rango de LEDs físicos, agrupamiento y orientación, independientemente de cómo se calculó el color (en nuestro caso, por `MediaProjection` + downsampling, no por un efecto de firmware).
- **Formato de paquete UDP simple para streaming**: diseñar nuestro propio paquete binario minimalista similar a DRGB/DNRGB — 1-2 bytes de cabecera (tipo + flags) seguido de un array plano de RGB en orden, con variante "offset" tipo DNRGB para actualizar solo una porción de la tira sin reenviar todo el frame. Esto es perfecto para un controlador RGBIC barato que probablemente solo entiende su propio protocolo nativo (a menudo basado en BLE GATT con un formato propietario), así que el formato real dependerá del fabricante — pero el *principio* de paquete simple sin handshake aplica igual.
- **Timeout/watchdog embebido**: incluir en nuestro protocolo (o en la lógica del lado Android) un mecanismo de "abandonar modo ambilight si no llega un frame nuevo en X ms", igual que `realtimeLock()`.
- **Versión de protocolo en la cabecera**: aunque nuestro protocolo sea propio, reservar un byte de versión desde el día uno evita el problema de WLED de tener que adivinar capacidades por tamaño de paquete.
- **Descarte de paquetes fuera de secuencia**: la idea de número de secuencia + descarte de paquetes "viejos" (de `e131.cpp`) es útil si nuestro transporte (BLE o WiFi UDP) puede entregar paquetes desordenados.

# 13. Compatibilidad con nuestro proyecto

Hay que ser honestos: WLED es firmware que **corre dentro** de un ESP32/ESP8266 — no es un protocolo de red independiente del hardware, ni un controlador genérico. Si el usuario de OpenAmbientCore usa un **ESP32 propio** flasheado con WLED en lugar de un controlador RGBIC comercial, entonces sí podemos hablar directamente los protocolos UDP de WLED (DRGB/DNRGB/DDP) desde la app Android, y WLED hace de "puente" entre nuestra app y la tira física — en ese escenario WLED es 100% compatible y reduce nuestro trabajo a "implementar un cliente DDP/DRGB en Kotlin".

Pero si el hardware real objetivo es un **controlador RGBIC barato de Aliexpress** (el caso típico mencionado en el contexto del proyecto), ese controlador casi nunca corre WLED ni entiende ninguno de sus protocolos UDP — habla su propio protocolo BLE GATT propietario (o, en el mejor caso, un protocolo WiFi tipo "Magic Home"/Govee, también propietario). En ese caso WLED no es directamente utilizable; solo sirve como **referencia de diseño de protocolo** (formato de paquete, modelo de segmentos, timeout), no como pieza de software a integrar.

Recomendación práctica: diseñar OpenAmbientCore para soportar dos "backends" de salida — (a) un backend WLED/DDP-DRGB nativo, útil para power-users con su propio ESP32, reusando el protocolo tal cual; y (b) un backend específico por fabricante de controlador RGBIC (BLE GATT) para el caso de uso principal del proyecto. El "modelo de segmentos" y el "formato de paquete simple con timeout" de WLED son la parte que vale la pena llevar a *ambos* backends, no solo al primero.

# 14. Resultado

★★★★☆☆☆☆☆☆ (4/10 como pieza de software a integrar; 8/10 como referencia de diseño de protocolo y modelo de segmentos) — WLED no es algo que vayamos a ejecutar ni empotrar, pero su protocolo UDP de streaming (DRGB/DNRGB/DDP) y su modelo de segmentos con agrupamiento/offset/orientación son la mejor plantilla de diseño disponible en código abierto para construir nuestro propio protocolo y modelo de zonas, especialmente si en el futuro soportamos ESP32 propios además de controladores RGBIC comerciales.
