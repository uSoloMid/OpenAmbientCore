# Sintesis de arquitectura

Esta primera tanda cubre nueve referencias:

- WLED
- Hyperion NG
- UniLED
- ha-banlanx
- SP108E hardware hack
- Govee BLE / Govee reversing
- ELK-BLEDOM
- OpenRGB
- Home Assistant integrations

La lectura importante no es "que codigo copiar", sino que responsabilidad asigna cada proyecto a cada capa.

# Mapa preliminar

```
                         Ambilight DIY
                              |
        +---------------------+----------------------+
        |                     |                      |
     Captura              Procesamiento          Salida
        |                     |                      |
  MediaProjection       Zone mapping             BLE barato
  HDMI grabber          Black border             UDP/WiFi
  ADB/screencap         Smoothing                HTTP/JSON
  Camera                Priority mux             MQTT
        |                     |                      |
        v                     v                      v
   Android TV          AmbientFrame           ControllerDriver
                                                   |
        +---------------------+--------------------+------------------+
        |                     |                    |                  |
      WLED                BanlanX/SP             Govee            ELK-BLEDOM
   UDP/DDP/JSON          BLE propietario       BLE/Cloud          BLE simple
        |                     |                    |                  |
        v                     v                    v                  v
 ESP32/ESP8266       SP601/SP611/SP63x       H61xx/H70xx       RGB generic
        |                     |                    |                  |
        v                     v                    v                  v
 Direccionable       RGB/RGBW/RGBIC          RGB/RGBIC          RGB global
```

# Ranking por utilidad para OpenAmbientCore

1. **Hyperion NG**: mejor referencia para pipeline Ambilight: captura, zonas, black border, smoothing, prioridad y salida desacoplada.
2. **UniLED**: mejor referencia para modelar controladores baratos BLE por familia y capacidad.
3. **WLED**: mejor referencia para salida WiFi en tiempo real usando UDP/DDP y modelo de segmentos.
4. **Home Assistant integrations**: buen patron de discovery, lifecycle, unique IDs, coordinators y fallback manual.
5. **OpenRGB**: buen patron multi-driver y modelo device/zone/LED, aunque centrado en PC.
6. **Govee**: util para BLE comercial y segmentacion propietaria; menos ideal por costo/cloud.
7. **ha-banlanx**: pequeno pero valioso como evidencia cruda de protocolo BanlanX.
8. **ELK-BLEDOM**: util para salida BLE RGB global ultra barata.
9. **SP108E hardware hack**: excelente si aceptamos flashear hardware; no sirve para stock controllers.

# Patrones comunes encontrados

- **Core desacoplado del hardware**: Hyperion y OpenRGB coinciden en que el motor no debe saber detalles del controlador.
- **Driver por familia, no por transporte**: UniLED y Govee muestran que "BLE" no alcanza; se necesita BanlanX, Govee, ELK, Zengge, etc.
- **Discovery imperfecto**: BLE name, UUID, manufacturer data, mDNS e IP manual deben convivir.
- **Capability model**: cada dispositivo debe declarar si soporta color global, brillo, efectos, segmentos, LED individual, white channel, CCT, speed, length, etc.
- **Tiempo real solo con protocolos pensados para frames**: WLED/Hyperion si; HA/Govee/ELK/BanlanX basico no necesariamente.
- **Smoothing debe estar antes del driver**: el driver no debe decidir estetica de video.
- **Fallback manual es obligatorio**: muchos clones BLE no se identifican de forma confiable.

# Arquitectura recomendada preliminar

```
Android TV App
  |
  +-- CaptureService
  |     MediaProjection
  |
  +-- FramePipeline
  |     downscale
  |     black border detector
  |     zone sampler
  |     color correction
  |     temporal smoothing
  |
  +-- DeviceLayer
  |     discovery
  |     pairing
  |     capabilities
  |     diagnostics
  |
  +-- OutputScheduler
        rate limit
        frame dropping
        reconnect policy
        driver selection
        |
        +-- WledUdpDriver
        +-- BanlanxBleDriver
        +-- GoveeBleDriver
        +-- ElkBledomBleDriver
        +-- GenericHttpDriver
```

# Decision tecnica mas importante

No conviene empezar intentando enviar LED individual por BLE a todos los controladores baratos. La estrategia mas limpia es soportar tres niveles:

- **Nivel 1: Color global BLE**: ELK-BLEDOM, BanlanX basico, Govee no segmentado. Muy barato, baja fidelidad.
- **Nivel 2: Zonas/segmentos propietarios**: Govee segmentado, algunos BanlanX RGBIC. Mejor Ambilight, pero modelo-especifico.
- **Nivel 3: Frame streaming real**: WLED por UDP/DDP en ESP32/ESP8266. Mejor resultado tecnico.

# Respuesta preliminar a "$500 MXN"

Si tuviera que disenar el mejor Ambilight DIY barato en 2026, tomaria:

- De **Hyperion**: pipeline de zonas, black-border y smoothing.
- De **WLED**: salida UDP/DDP para el modo de mejor latencia.
- De **UniLED**: catalogo de familias y modelo de capacidades para controladores BLE baratos.
- De **HA**: discovery, unique ID, fallback manual y diagnostics.
- De **OpenRGB**: abstraccion `Device -> Zone -> LED -> Mode`.
- De **ELK/BanlanX/Govee**: drivers de compatibilidad para hardware comercial barato, aceptando que muchos seran "modo degradado".

La apuesta mas fuerte por calidad/precio seria:

```
Android TV
  -> MediaProjection
  -> 8-16 zonas perimetrales
  -> WiFi UDP
  -> ESP32 con WLED
  -> WS2812B / SK6812
```

Si el presupuesto o la disponibilidad obliga a usar controladores stock:

```
Android TV
  -> MediaProjection
  -> promedio / pocas zonas
  -> BLE
  -> BanlanX/SP o ELK-BLEDOM
```

Ese segundo camino es mas barato de entrada, pero menos "Ambilight real".

