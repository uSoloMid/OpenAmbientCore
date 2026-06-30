# 1. Resumen

`ha-banlanx` (phhusson/ha-banlanx) es una integración custom de Home Assistant para controlar una tira LED gestionada por la app oficial "BanlanX" (controlador RGBIC barato, vendido en Aliexpress bajo SP108E/BanlanX/marca blanca) vía Bluetooth Low Energy. El proyecto resuelve un problema muy puntual: permitir prender/apagar, fijar brillo y lanzar uno de ~15 efectos predefinidos desde HA, sin depender de la app móvil del fabricante.

Es un proyecto personal de ingeniería inversa, no un producto. Todo el repo son 5 archivos (`light.py`, `__init__.py`, `manifest.json`, `README`, `REVERSE-NOTES`) y la lógica cabe en ~210 líneas. El propio autor admite en el README que "actualmente solo algunos efectos están soportados, y RGB no está soportado" y que es "fácil de implementar, solo pregunta". No hay señales de actividad reciente, tests, CI, ni manejo de errores robusto — es un prototipo funcional congelado en ese estado.

Vale la pena estudiarlo, pero no por su arquitectura (no tiene ninguna digna de imitar) sino por el **REVERSE-NOTES**: es la evidencia más directa y barata de cómo luce el protocolo BLE crudo de un controlador BanlanX/SP108E real, capturado con btsnoop desde la app oficial Android. Ese archivo vale más que el código.

Calificación como entregable de software: **3/10**. Calificación como fuente de evidencia de protocolo: **7/10**.

# 2. Arquitectura

```
┌────────────────────┐
│  Home Assistant     │
│  (light.py entity)  │
│  - async_turn_on()   │
│  - async_turn_off()  │
│  - effect_list       │
└─────────┬───────────┘
          │ bleak (BLE lib Python)
          ▼
┌────────────────────┐
│  BLE GATT layer      │
│  Service 0xffe0       │
│  Char   0xffe1 (write) │
└─────────┬───────────┘
          │ paquetes hex crudos (A0 ...)
          ▼
┌────────────────────┐
│ Controlador BanlanX  │
│ (chip BLE + driver    │
│  RGBIC, ej. SP108E)   │
└─────────┬───────────┘
          │ protocolo propietario LED
          ▼
┌────────────────────┐
│  Tira LED RGBIC        │
└────────────────────┘
```

Es la arquitectura mínima posible: una sola entidad HA habla directo por BLE al controlador, sin capa de abstracción de protocolo, sin cache de estado persistente, sin cola de comandos. Cada llamada a `write_msg()` abre conexión BLE si no existe y escribe el payload hex inmediatamente. Esto tiene sentido para el alcance del proyecto (una tira, un usuario, sin necesidad de escalar) pero es frágil: no hay reconexión automática robusta, no hay gestión de fallos de escritura, y el comentario del propio autor en el README ("la tira no soporta acceso BLE concurrente, mata la app del smartphone") confirma que el diseño no contempla compartir el dispositivo.

# 3. Estructura del proyecto

- `light.py` — **todo** el proyecto vive aquí: setup de la plataforma HA (`async_setup_platform`), discovery por bluetooth de HA (`bluetooth.async_register_callback`), la clase `BanlanxLight(LightEntity)` con el ciclo de vida turn_on/turn_off/brightness/effect, el diccionario `effects` (mapa nombre de efecto → lista de comandos hex), y la función de bajo nivel `write_msg()`/`ble_get_client()` que abre el `BleakClient` y escribe en la característica GATT.
- `__init__.py` — vacío salvo un docstring; no hay lógica de integración real, todo el `light.py` actúa como plataforma legacy de HA (`PLATFORM_SCHEMA`), no como integración basada en config_entry/coordinator moderno.
- `manifest.json` — declara el dominio `banlanx`, dependencia de `bluetooth_adapters`, y el filtro de discovery `service_uuid: 0000ffe0-...` usado por el sistema de bluetooth integrado de HA.
- `README` — instrucciones de instalación/uso y advertencias operativas (límite de un solo dispositivo, conflicto de conexión BLE concurrente, efectos parciales).
- `REVERSE-NOTES` — el archivo más valioso: notas crudas de la sesión de reversing con `btsnoop_hci.log`, listando servicios/características GATT descubiertas y los comandos hex probados manualmente vía `bluetoothctl`/`gatt`.

No existe separación entre "protocolo" y "entidad HA"; los bytes de comando están hardcodeados directamente en el diccionario `effects` y en las cadenas literales dentro de los métodos de la entidad.

# 4. Hardware soportado

El repo soporta una única variante de hardware: un controlador identificado solo por su MAC (`31:00:00:00:09:F5`) y por anunciar el servicio BLE `0000ffe0-0000-1000-8000-00805f9b34fb`. No se menciona explícitamente "SP108E" ni un modelo concreto; el autor simplemente lo describe como "una tira LED controlada por la app Android BanlanX". El README dice expresamente: "actualmente solo se soporta una tira LED... es bastante fácil de hacerlo [soportar más], así que pregunta" — es decir, no hay soporte multi-dispositivo, ni multi-modelo, ni descubrimiento dinámico de variantes de hardware.

No hay ninguna mención ni evidencia de variante WiFi en este repo. Todo el transporte es BLE puro (vía `bleak`). Las notas de reversing (`REVERSE-NOTES`) muestran un segundo servicio "vendor specific" (`5833ff01-...`) anunciado por el mismo dispositivo, que el código nunca llega a usar — posible indicio de que el mismo chip soporta un protocolo adicional (quizá más rico) que no fue investigado.

# 5. Protocolos

UUIDs identificados en `REVERSE-NOTES` y usados en `light.py`:

- Servicio principal usado por el código: `0000ffe0-0000-1000-8000-00805f9b34fb` (UUID corto estilo CC254x/HM-10, muy común en clones BLE baratos chinos).
- Característica de escritura usada por el código: `0000ffe1-0000-1000-8000-00805f9b34fb` (`write_gatt_char` sin esperar respuesta explícita, sin notify habilitado en el flujo de escritura).
- Descriptor CCCD `00002902-...` presente en ambos servicios (estándar BLE, para notificaciones).
- Segundo servicio detectado pero **no usado** por el código: `5833ff01-9b8b-5191-6142-22a4536ef123`, con características `5833ff02` (write) y `5833ff03` (notify, tiene CCCD). Esto sugiere un protocolo "vendor" más completo que el simple `ffe0/ffe1`, no explorado en este repo.

Formato de comando (deducido de `effects` y `REVERSE-NOTES`): paquetes hex cortos con cabecera fija `A0`, luego un byte de "opcode" (ej. `0x62`=power, `0x63`=set effect, `0x66`=brightness, `0x67`=speed, `0x68`=length, `0x69`=color RGB+brillo directo), luego un byte de longitud de payload, y el payload mismo. Ejemplos documentados:

- `A0 62 01 01` → encender (opcode 0x62, longitud 1, payload `01`=ON, `00`=OFF).
- `A0 66 01 <hex>` → brightness, payload de 1 byte (0-255).
- `A0 63 01 <hex>` → seleccionar efecto por código numérico.
- `A0 67 01 <hex>` → velocidad de efecto.
- `A0 68 01 <hex>` → longitud/tamaño de efecto.
- `A0 69 04 <R><G><B><brillo>` → color RGB directo + brillo en un solo paquete de 4 bytes de payload (esto es la única pista de "color directo", pero el código de `light.py` NUNCA lo usa — está comentado como TODO).

No se observa byte de checksum/CRC al final de los paquetes en las capturas documentadas — los ejemplos son consistentemente `A0 <opcode> <len> <payload>` sin bytes adicionales de validación. No hay evidencia de pairing BLE ni autenticación a nivel de aplicación: la conexión GATT es abierta y se escribe directo sin handshake (`BleakClient.connect()` simple).

# 6. Latencia

El repo no implementa streaming de color en tiempo real. Lo que existe es "set and forget": un comando de power, uno de brillo, y opcionalmente una secuencia de 1-3 comandos para seleccionar un efecto predefinido, cada vez que el usuario interactúa con la entidad en HA (al llamar `async_turn_on`). No hay bucle de actualización continua, no hay temporizador, no hay forma de empujar un buffer de color por frame.

El único campo que se acerca a "color directo" es el comando `A0 69 04 RR GG BB <brillo>` documentado en `REVERSE-NOTES`, pero:
1. Nunca se implementa en `light.py` (está marcado `#TODO: Handle RGB`).
2. No hay evidencia, ni en este repo ni en las notas, de que admita actualizaciones por zona/segmento o un framebuffer completo — parece ser un solo color global aplicado a toda la tira.

Sobre comandos/segundo viables: no hay throttling explícito en el código (no hay debounce, no hay cola), cada escritura es un `write_gatt_char` síncrono que espera a que la conexión esté activa. La latencia real dependerá del estricto BLE GATT write con respuesta (~20-100ms típico en hardware barato BLE 4.x), pero el propio repo nunca lo prueba a alta frecuencia porque su caso de uso es "el usuario pulsa un botón en HA una vez". No hay datos empíricos de throughput en el repo.

# 7. Renderizado

El modelo de estado es extremadamente simple y vive solo en la entidad Python (`BanlanxLight`), no en el dispositivo:

- **Color global/efecto**: un diccionario `effects` mapea ~15 nombres de efecto (Rainbow, Rainbow meteor, Red Comet, Drapeau Francais, etc.) a una lista corta de comandos hex que configuran velocidad, longitud y tipo de efecto del firmware del controlador. El color real en estos efectos lo decide el firmware interno del chip BanlanX, no la app.
- **Brillo**: un solo byte global (`A0 66 01 <brillo>`), aplicado a toda la tira.
- **RGB directo**: existe el opcode `0x69` en las notas pero no está implementado; sería un único color global, no por zona.
- **Direccionamiento individual de LEDs**: no existe ningún soporte, ni en código ni en las notas de reversing, para direccionar píxeles individuales o "zonas". Todo es color/efecto uniforme aplicado por el firmware del controlador a la tira completa.
- **Segmentos**: no hay concepto de segmentos en este repo.

En resumen, el modelo es: power on/off + brightness (0-255) + selección de uno de N efectos predefinidos con parámetros de velocidad/longitud. No hay control de pixel ni de framebuffer expuesto por esta integración.

# 8. Descubrimiento

El discovery usa el mecanismo nativo de Bluetooth de Home Assistant (`homeassistant.components.bluetooth`):

- En `manifest.json`, el bloque `"bluetooth": [{"service_uuid": "0000ffe0-0000-1000-8000-00805f9b34fb"}]` le dice al core de HA que anuncie/filtre dispositivos por ese UUID de servicio.
- En `light.py`, `bluetooth.async_register_callback(...)` se suscribe a cambios con el mismo filtro `service_uuid` y `connectable: True`, en modo de escaneo `ACTIVE`.
- No hay matching por nombre de dispositivo BLE ni por manufacturer data — el único criterio es el UUID de servicio anunciado `0000ffe0`.
- La configuración del dispositivo concreto a controlar es manual: el usuario debe poner la MAC address en `configuration.yaml` (`platform: banlanx`, `mac: 31:00:00:00:09:F5`); no hay flujo de configuración (`config_flow`) interactivo, es la plataforma "legacy" de HA basada en YAML.

# 9. Diseño

Honestamente, no hay patrones de diseño formales que destacar. Es un script plano:
- Una sola clase (`BanlanxLight`) que mezcla entidad HA, lógica de conexión BLE y construcción de comandos en los mismos métodos.
- El "protocolo" es un diccionario literal de strings hex (`effects`), sin builder, sin enum, sin función de encoding reusable más allá de `format(brightness, "02X")` para el byte de brillo.
- No hay capa de abstracción entre "comando lógico" (ej. "enciende", "brillo X") y "bytes BLE" — todo está hardcodeado inline.
- No hay manejo de reconexión, reintentos, ni cola de comandos; cada `write_msg` revisa `is_connected` y conecta si falta, sin lógica de retry ante fallos.
- No hay tests, ni separación de capas, ni gestión de estado fuera de atributos sueltos en la instancia.

Es código de prototipo/hobby, útil como evidencia de protocolo, no como referencia de arquitectura.

# 10. Lo bueno

- El archivo `REVERSE-NOTES` es un buen ejemplo de **documentar el proceso de reversing tal cual**, sin pulir: vale la pena adoptar la práctica de capturar btsnoop logs y anotar comandos probados manualmente vía `bluetoothctl`/gatt antes de escribir ningún código de integración — exactamente el método que OpenAmbientCore necesitará para validar hardware candidato real como este.
- El protocolo en sí (paquetes cortos `A0 <opcode> <len> <payload>`) es trivialmente simple de parsear/generar; si el comando RGB (`0x69`) funciona como documentado, es una API de "set color" de baja fricción para integrarse rápido en una prueba de concepto.
- El uso del discovery nativo de HA por `service_uuid` (en vez de nombre de dispositivo) es una buena práctica general — aunque en este caso el UUID `ffe0` es tan genérico (compartido por decenas de chips BLE baratos) que en producción probablemente generaría falsos positivos con otros dispositivos no-BanlanX.

# 11. Lo malo

- Mezclar la lógica de conexión BLE, el protocolo, y la entidad HA en una sola clase sin separación es lo primero que yo cambiaría — incluso para un proyecto pequeño, separar "encoder de protocolo" de "transporte BLE" de "entidad HA" facilita testear el protocolo sin hardware real.
- No hay manejo de errores: si `write_gatt_char` falla (timeout, dispositivo ocupado por la app oficial, fuera de rango), no hay retry, no hay backoff, no hay feedback claro al usuario más que excepciones sin capturar.
- El RGB/color directo nunca se implementó pese a estar ya identificado en las notas de reversing — es una limitación de alcance, no del protocolo. Esto confirma que probablemente SÍ es posible pero quedó sin terminar.
- No explora ni documenta el segundo servicio vendor-specific (`5833ff01...`) detectado en las propias capturas — podría ser un protocolo más rico (quizás con streaming, push de buffer, o telemetría) que se dejó sin investigar.
- Sin checksum/CRC visible en los comandos: si esto es correcto (no solo omisión de las notas), es un protocolo frágil ante ruido en el enlace BLE; no hay forma de detectar comandos corruptos.

# 12. Qué podemos reutilizar

Lo único genuinamente reutilizable es la **evidencia de protocolo**, no el código:

- UUID de servicio `0000ffe0` / característica `0000ffe1` como punto de partida para escanear y probar contra hardware BanlanX/SP108E real que adquiramos.
- La estructura de comando `A0 <opcode> <len> <payload>` como hipótesis de partida a validar con captura BLE propia (HCI snoop en Android, o `nRF Connect`/`bluetoothctl` directo) antes de codificar nada.
- Los opcodes documentados (`0x62` power, `0x63` effect, `0x66` brightness, `0x67` speed, `0x68` length, `0x69` RGB+brillo) como mapa inicial a verificar/expandir contra nuestro propio hardware específico (puede variar entre lotes/firmwares aunque se anuncien como "BanlanX").
- La advertencia operativa del README sobre conflicto de conexión BLE concurrente con la app del fabricante es un riesgo real a documentar en nuestro propio diseño (UX: avisar al usuario que debe cerrar la app oficial / desactivar Bluetooth del móvil).
- La pista del segundo servicio `5833ff01` no explorado merece investigación propia con captura BLE — podría ser la vía real hacia streaming si existe.

# 13. Compatibilidad con nuestro proyecto

Como hardware candidato real, la evidencia de este repo apunta a una limitación seria para Ambilight en tiempo real: el protocolo documentado aquí es de tipo **"set and forget"**, no streaming. Los comandos observados (`A0 62`, `A0 66`, `A0 63`, `A0 67`, `A0 68`, `A0 69`) configuran estado persistente en el controlador (power, brillo, efecto, velocidad, color único) — no hay ningún comando tipo "aquí va el frame N, púshalo ya" ni mecanismo de buffer por zona/píxel.

Aun si el comando `A0 69 04 RR GG BB brillo` funcionara de forma fiable y rápida, seguiría siendo "fijar UN color global", no un framebuffer por zona — insuficiente para Ambilight real (que necesita N colores por zona, actualizados a ~20-30 fps idealmente). Sobre BLE GATT write estándar, escribir un solo comando de 4-8 bytes podría sostener unos pocos Hz a decenas de Hz dependiendo del stack BLE y el controlador, pero ESTE protocolo no expone ninguna forma de actualizar más de un color a la vez — por lo que el cuello de botella no es solo de latencia BLE, sino de **capacidad del protocolo mismo**: no hay verbo para "set zona X a color Y" ni "set buffer completo".

Conclusión práctica: este protocolo, tal como está documentado en `ha-banlanx`, es viable solo para encender/apagar, fijar brillo, y lanzar efectos predefinidos del firmware — NO para llevar el color calculado por el procesamiento de pantalla en tiempo real a la tira. Si BanlanX/SP108E es viable para OpenAmbientCore, probablemente sea a través del servicio vendor-specific no explorado (`5833ff01-...`) o mediante un protocolo WiFi alternativo (no presente en este repo) — ambos requieren investigación adicional con hardware real y captura BLE propia antes de descartar o confirmar la viabilidad de streaming.

# 14. Resultado

★★☆☆☆ (2/5) — Útil únicamente como evidencia cruda de protocolo BLE (gracias a `REVERSE-NOTES`); como integración de software no aporta nada arquitectónicamente, y el protocolo documentado parece limitado a "set and forget" sin verbo de streaming, lo que lo deja como candidato dudoso para Ambilight en tiempo real sin investigación adicional sobre el servicio vendor-specific no explorado.
