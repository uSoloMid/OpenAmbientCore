# 1. Resumen

UniLED (`monty68/uniled`) es una integracion custom de Home Assistant para controlar muchos controladores LED baratos, sobre todo BLE, desde una interfaz comun. Resuelve el problema de la fragmentacion: cada familia SP/BanlanX/LED Chord/LED Hue/Zengge habla un protocolo distinto, pero HA necesita exponer todo como entidades `light`, `select`, `number`, `sensor` y similares.

Fue creado para controladores BLE tipo SP107E, SP110E, SP601E, SP602E, SP608E, SP611E, SP613E, SP614E, SP617E, SP620E, SP621E, SP623E, SP624E y familia SP63xE/SP64xE, ademas de soporte basico Zengge/Hao Deng Mesh. Esta activo y es bastante mas serio que los prototipos de reversing de un solo dispositivo.

Vale mucho la pena estudiarlo. No por su UI de Home Assistant, sino por su modelado de familias, variantes, caracteristicas por dispositivo y separacion entre transporte BLE y protocolo. Calificacion: **9/10** como referencia para controladores RGBIC baratos; **6/10** como arquitectura directa para Android TV, porque depende del modelo de integraciones de HA.

# 2. Arquitectura

```
Home Assistant
  |
  | config_flow + bluetooth discovery
  v
UniLED integration
  |
  | UniledDevice / UniledModel / channels / features
  v
Transport BLE
  |
  | GATT write/read/notify
  v
Controller SP/BanlanX/Zengge
  |
  v
PWM/SPI RGB/RGBW/RGBCCT/RGBIC strip
```

Eligieron esta arquitectura porque el problema real no es renderizar video, sino normalizar muchas variantes de hardware. El centro es el modelo de capacidades: un dispositivo puede tener color, blanco, CCT, efectos, velocidad, longitud, chip order, segmentos o estado leido desde notificaciones.

# 3. Estructura del proyecto

- `custom_components/uniled/__init__.py`: setup de la integracion, registro de callbacks Bluetooth y resolucion del modelo.
- `config_flow.py`: alta de dispositivos, discovery BLE, seleccion manual de modelo cuando el auto-detect falla.
- `entity.py`, `light.py`, `select.py`, `number.py`, `sensor.py`: adaptadores HA. Son importantes solo como ejemplo de exposicion de capacidades.
- `lib/device.py`, `lib/model.py`, `lib/channel.py`, `lib/features.py`: nucleo conceptual. Separan dispositivo fisico, modelo soportado, canales y features.
- `lib/ble/device.py`: transporte BLE comun, conexion, reintentos, RSSI, timeouts, escritura y notificaciones.
- `lib/ble/models.py`: lista de modelos BLE soportados.
- `lib/ble/banlanx*.py`, `led_chord.py`, `led_hue.py`: protocolos/familias de comandos.
- `lib/zng/*`: familia Zengge/Hao Deng BLE Mesh.
- `lib/net/*`: esqueleto para transporte red; actualmente no aporta modelos relevantes.

# 4. Hardware soportado

BLE:

- LED Chord: SP107E.
- LED Hue: SP110E.
- BanlanX: SP601E, SP602E, SP608E, SP611E, SP613E, SP614E, SP617E, SP620E, SP621E, SP623E, SP624E, SP630E, SP631E, SP632E, SP633E, SP634E, SP635E, SP636E, SP637E, SP638E, SP639E, SP63AE, SP641E, SP642E, SP643E, SP644E, SP645E, SP646E, SP647E, SP648E, SP649E, SP64AE.
- Hao Deng/Zengge BLE Mesh: soporte basico.

Transportes: BLE si; WiFi/UDP/USB/Serial/MQTT no como foco actual. Algunos modelos controlan salidas PWM, SPI, RGB, RGBW, CCT y RGBCCT, pero eso ocurre dentro del controlador.

# 5. Protocolos

El patron no es un protocolo universal. UniLED modela familias:

- BanlanX 601/60x/6xx y BanlanX 2/3: comandos BLE propietarios, con variantes por generacion.
- LED Chord / LED Hue: comandos SP mas antiguos.
- Zengge: BLE Mesh, con gestion de nodos y paquetes propios.

La leccion importante es que no basta con "soportar BLE". Hay que soportar `BleProtocolDriver` por familia, porque UUIDs, payloads, notificaciones, escenas y capacidades cambian por modelo.

# 6. Latencia

No es Ambilight en tiempo real. Es control domotico: cambios de estado, color, brillo, efecto y parametros. El flujo admite reintentos y espera de notificaciones, lo cual mejora confiabilidad pero no esta pensado para 30-60 FPS. BLE barato y una sola conexion concurrente son limites practicos.

No se ve compresion de frames, interpolacion de video ni smoothing de imagen. El smoothing queda, si existe, dentro del controlador o de HA.

# 7. Renderizado

Representa luces como capacidades y canales, no como framebuffer completo. Soporta color global, modos, efectos, longitud/velocidad y en algunos modelos RGBIC parametros de efecto. No intenta enviar LED individual a alta frecuencia.

La conversion ocurre en los drivers de protocolo: entidad HA -> feature/canal -> comando propietario.

# 8. Descubrimiento

Discovery por Bluetooth de Home Assistant:

- scan BLE activo/pasivo;
- `BluetoothServiceInfoBleak`;
- nombre local;
- service UUID;
- manufacturer data para algunas familias;
- seleccion manual si el modelo no se puede inferir.

La idea clave: el auto-detect debe ser probabilistico y editable. En estos controladores baratos, el nombre BLE muchas veces es mas confiable que una firma GATT perfecta.

# 9. Diseno

Patrones visibles:

- Strategy: cada familia/modelo implementa su protocolo.
- Adapter: entidades HA adaptan capacidades genericas a comandos especificos.
- Factory/registry: listas de modelos soportados.
- State model: estado por canal y por feature.
- Async/coroutines: integracion con HA y BLE.
- Separation of concerns razonable: transporte BLE separado del payload de familia.

# 10. Lo bueno

- El catalogo de modelos SP/BanlanX es oro para nuestro mapa de compatibilidad.
- Buen ejemplo de "capability model" por dispositivo.
- Separacion conceptual entre modelo, transporte, canal y feature.
- Acepta que el auto-detect falla y permite override manual.
- Maneja variantes de una misma familia sin fingir que hay un protocolo unico.

# 11. Lo malo

- Esta atado al framework de Home Assistant.
- No esta disenado para streaming visual en tiempo real.
- BLE como unico transporte fuerte limita FPS y alcance.
- La superficie de integracion HA agrega mucho ruido para una app Android nativa.

# 12. Que podemos reutilizar

No codigo. Si ideas:

- `ControllerModel` con lista de features.
- `ProtocolDriver` por familia.
- `Transport` separado de `Protocol`.
- `DeviceFingerprint` con nombre BLE, manufacturer data, service UUID y fallback manual.
- Matriz de compatibilidad por modelo.

# 13. Compatibilidad con nuestro proyecto

Para:

```
Android TV -> MediaProjection -> procesamiento -> BLE/WiFi -> RGBIC barato -> tira
```

UniLED sirve en la parte final: BLE/WiFi -> controlador barato. No ayuda con MediaProjection ni procesamiento de imagen. Su mayor aporte es definir como abstraer controladores baratos sin ensuciar el pipeline de Ambilight.

En OpenAmbientCore conviene imitar conceptualmente:

```
AmbientFrame -> ZoneMapper -> DeviceColorFrame -> ProtocolDriver -> Transport
```

El driver UniLED-like vive solo al final, recibiendo un color global, zonas o comandos de efecto.

# 14. Resultado

★★★★★ Ideal para estudiar BLE barato y modelado de compatibilidad.

★★★★☆ Excelente referencia para familias BanlanX/SP.

★★★☆☆ No es referencia de Ambilight en tiempo real.

