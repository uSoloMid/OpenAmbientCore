# 1. Resumen

`TheSylex/ELK-BLEDOM-bluetooth-led-strip-controller` es un controlador Rust para tiras LED genericas que anuncian como ELK-BLEDOM. Resuelve control basico BLE: power, color, brillo, efectos, velocidad, hora y programaciones.

Hardware objetivo: receptores BLE chinos genericos ELK-BLEDOM, usualmente para tiras RGB no direccionables o controladores simples. Esta poco activo y parece prototipo, pero el protocolo es muy claro. Calificacion: **6/10** como evidencia BLE; **3/10** como arquitectura.

# 2. Arquitectura

```
CLI Rust
  |
  | btleplug
  v
BLE scan/connect
  |
  | characteristic fff3
  v
ELK-BLEDOM controller
  |
  v
RGB LED strip
```

Eligieron una arquitectura minima: encontrar una MAC fija, conectar, filtrar caracteristica y mandar paquetes de 9 bytes. Es reversing practico, no plataforma.

# 3. Estructura del proyecto

- `src/device.rs`: todo lo importante. Scan, conexion, filtro de characteristic, comandos y constantes de efectos.
- `src/main.rs`: ejemplo/CLI de uso.
- `README.md`: instrucciones minimas.

No hay UI real, discovery robusto, procesamiento de imagen ni abstraccion multi-modelo.

# 4. Hardware soportado

ELK-BLEDOM generico. El codigo usa una MAC hardcodeada en el ejemplo, por lo que no es multi-dispositivo sin cambios.

Transportes:

- BLE: si.
- WiFi/UDP/TCP/HTTP/MQTT/USB/Serial: no.

# 5. Protocolos

Caracteristica usada:

- UUID de 128 bits que corresponde a characteristic corta `0xfff3` en formato BLE base.

Formato de comando:

```
7e 00 <cmd> <arg1> <arg2> <arg3> <arg4> 00 ef
```

Ejemplos conceptuales:

- Power: comando `0x04` con payload ON/OFF.
- Brightness: comando `0x01`, valor 0-100.
- Speed: comando `0x02`, valor 0-100.
- Effect: comando `0x03`, codigo de efecto.
- Color: comando `0x05`, modo RGB + R/G/B.
- Time/schedule: comandos `0x83` y `0x82`.

No se observa checksum; el framing se basa en inicio `0x7e` y fin `0xef`.

# 6. Latencia

No es tiempo real. Son comandos discretos BLE. Puede cambiar color global rapidamente, pero no hay framebuffer LED individual ni streaming a FPS constantes. Sin buffering, compresion, interpolacion o smoothing.

# 7. Renderizado

Color global RGB y efectos predefinidos. No hay segmentos, matriz ni LED individual. La conversion es directa: color -> paquete BLE.

# 8. Descubrimiento

El ejemplo usa scan BLE pero luego busca una MAC especifica. No hay deteccion por nombre, manufacturer data o selector de dispositivos. Para producto real habria que escanear por nombre `ELK-BLEDOM` y characteristic/service.

# 9. Diseno

Patrones:

- Wrapper simple `BleLedDevice`.
- Constantes de efectos.
- Sin factory, sin repository, sin DI, sin estado robusto.

Es deliberadamente simple.

# 10. Lo bueno

- Protocolo facil de entender.
- Muy barato y comun.
- Paquetes pequenos, perfectos para un driver Android sencillo.
- Buen candidato para control global o pocas zonas.

# 11. Lo malo

- MAC hardcodeada.
- Sin reconexion robusta.
- Sin soporte multi-dispositivo.
- Sin lectura/estado real.
- No RGBIC ni LED individual.

# 12. Que podemos reutilizar

- Driver BLE minimo para color global.
- Framing `0x7e ... 0xef`.
- Soporte como "basic RGB BLE controller".
- Fallback barato cuando no hay RGBIC.

# 13. Compatibilidad con nuestro proyecto

Para Ambilight, ELK-BLEDOM sirve solo como salida degradada:

```
MediaProjection -> promedio de pantalla o zonas reducidas -> color global BLE
```

No sirve para Ambilight perimetral real con zonas independientes, salvo que usemos varios controladores fisicos, lo cual seria torpe.

# 14. Resultado

★★★☆☆ Bueno para aprender BLE simple.

★★☆☆☆ Limitado para Ambilight.

★★★★☆ Candidato barato para modo color global.

