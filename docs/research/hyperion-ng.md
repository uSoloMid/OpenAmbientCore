# 1. Resumen

Hyperion NG (`hyperion-project/hyperion.ng`) es una plataforma completa de Ambilight. Captura imagen desde varias fuentes, la procesa por zonas, aplica correcciones/smoothing/prioridades y envia colores a dispositivos LED por USB, serial, red, SPI, WLED, Philips Hue, Home Assistant y otros.

Fue creado para PC, Raspberry Pi, Linux boxes, capturadoras HDMI, grabbers de pantalla y controladores LED externos. Esta activo y es probablemente la referencia mas importante para arquitectura Ambilight madura. Calificacion: **10/10** como arquitectura conceptual; **5/10** como base directa para Android TV, porque es C++/Qt y muy grande.

# 2. Arquitectura

```
Grabber
  | pantalla / video / audio / network input
  v
Hyperion core
  |
  | PriorityMuxer
  | BlackBorderDetector
  | ImageProcessor
  | ImageToLedsMap
  | ColorAdjustment
  | LinearColorSmoothing
  v
LedDeviceWrapper
  |
  | LedDeviceFactory
  v
LedDevice backend
  |
  | USB / Serial / UDP / TCP / HTTP / WLED / Hue / HA
  v
Controlador / tira LED
```

Eligieron esta arquitectura porque Ambilight real tiene muchas entradas y muchas salidas. La parte brillante es que el procesamiento no conoce el hardware; produce un vector de colores y lo entrega a un `LedDevice`.

# 3. Estructura del proyecto

- `libsrc/grabber/*`: captura desde Amlogic, DirectX/DDA, DRM, framebuffer, V4L2, X11/XCB, macOS, Qt, audio.
- `libsrc/hyperion/ImageProcessor.cpp`: conversion de imagen a LEDs.
- `libsrc/hyperion/ImageToLedsMap.cpp`: mapeo de regiones de pantalla a indices LED.
- `libsrc/blackborder/*`: deteccion de bordes negros.
- `libsrc/hyperion/PriorityMuxer.cpp`: decide que fuente gana cuando hay varias entradas.
- `libsrc/hyperion/LinearColorSmoothing.cpp`: suavizado temporal.
- `libsrc/leddevice/*`: abstraccion de dispositivos, factory y backends.
- `libsrc/leddevice/dev_net/*`: dispositivos por red, incluyendo WLED/Home Assistant/Philips Hue/Cololight/etc.
- `libsrc/jsonserver`, `libsrc/flatbufserver`, `libsrc/protoserver`: APIs de entrada/control.
- `assets/webconfig`: UI web de configuracion.

# 4. Hardware soportado

Amplio: tiras via serial/Adalight, USB HID, FTDI, dispositivos de red, WLED, Philips Hue, Nanoleaf/Cololight-like, Home Assistant, FadeCandy, AtmoOrb, y muchos controladores historicos.

Transportes: USB, HID, serial, SPI/FTDI, UDP, TCP, HTTP, mDNS/SSDP para discovery en algunos casos. No es un proyecto BLE-first.

# 5. Protocolos

Hyperion habla varios protocolos:

- JSON-RPC para control/configuracion.
- FlatBuffers y Protobuf para entrada de imagen/color de baja sobrecarga.
- Boblight/Proto/Adalight/TPM2 segun dispositivo.
- HTTP/REST para integraciones tipo Hue/HA/WLED.
- UDP/TCP segun backend.

La leccion es no casar el core a un protocolo. El core debe emitir `LedFrame`; cada backend decide si eso se serializa como BLE, UDP, JSON, DDP o comandos propietarios.

# 6. Latencia

Si es tiempo real. Esta pensado para video. Usa captura continua, procesamiento por frame, prioridad de fuentes y smoothing configurable. La tasa depende de grabber, resolucion de procesamiento, cantidad de LEDs y dispositivo.

Hay buffering implicito por threads/wrappers y suavizado temporal. No busca comprimir frames genericos; reduce costo bajando resolucion/mapeando regiones.

# 7. Renderizado

Representa LEDs como una lista ordenada de regiones/zones alrededor de la pantalla. `ImageToLedsMap` traduce coordenadas de imagen a LED. `ImageProcessor` promedia/extrae color por region. Luego ajustes y smoothing producen el frame final.

Soporta LED individual, zonas perimetrales, matrices en algunos contextos y dispositivos con segmentos via backend.

# 8. Descubrimiento

Depende del dispositivo:

- mDNS para servicios de red.
- SSDP en ciertos backends.
- bus USB/HID/serial enumerado por sistema.
- IP/manual host en muchos dispositivos.
- discovery propio por backend.

# 9. Diseno

Patrones claros:

- Factory: `LedDeviceFactory`.
- Adapter/Strategy: cada `LedDevice` implementa salida especifica.
- Wrapper/thread boundary: `LedDeviceWrapper`.
- Pipeline: grabber -> processor -> muxer -> smoothing -> output.
- State/priority model: fuentes compiten por prioridad.
- Schema-driven config: JSON schemas para UI/config.
- Qt signals/slots: arquitectura event-driven.

# 10. Lo bueno

- Separacion ejemplar entre captura, procesamiento y salida.
- Mapeo de zonas como entidad central.
- Black border detection.
- PriorityMuxer para manejar fuentes multiples.
- Smoothing como dispositivo virtual/intermedio.
- Backends de salida intercambiables.

# 11. Lo malo

- Muy grande para Android TV.
- Mucha complejidad historica.
- Qt/C++ no se traslada directo a Kotlin/Android.
- Soporte BLE no es el centro.
- La UI/config es potente pero pesada para una app pequena.

# 12. Que podemos reutilizar

- Pipeline conceptual.
- `LedLayout` con zonas normalizadas.
- `FrameProcessor` independiente de transportes.
- `OutputDevice`/`LedDevice` como interface.
- Smoothing y black-border como modulos opcionales.
- Prioridad de fuentes: screen capture, efecto manual, test pattern, idle.

# 13. Compatibilidad con nuestro proyecto

Hyperion es la referencia para:

```
MediaProjection -> downscale -> black border -> zones -> smoothing -> output
```

No copiariamos su codigo, pero si su forma de pensar: el hardware no debe saber nada de MediaProjection; recibe un frame ya reducido. Para Android TV, una version pequena seria:

```
CaptureService
  -> FrameSampler
  -> ZoneMapper
  -> TemporalSmoother
  -> DeviceRouter
  -> BLE/WiFi driver
```

# 14. Resultado

★★★★★ La mejor referencia de arquitectura Ambilight.

★★★★★ Excelente para procesamiento, zonas y smoothing.

★★☆☆☆ Demasiado grande para copiar como estructura directa.

