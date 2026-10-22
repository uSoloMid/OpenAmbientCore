# Camera Ambilight (prototipo)

Ambilight para **cualquier** contenido de la TV (YouTube, IPTV, lo que sea) usando una **webcam apuntando a la pantalla** → calcula los colores del borde → los envía a **WLED** por UDP (DRGB, puerto 21324, el mismo protocolo que la app Android).

Es la vía **sin root y sin riesgo**: la cámara solo lee la luz que ya sale de la pantalla (como los ojos), así que funciona con todo. Ver [la investigación](../../docs/investigacion-tcl-rtd2851-hallazgos.md) de por qué la captura interna de la TV no era viable.

## Requisitos

- **Node.js** (ya instalado, v20+).
- Una **webcam USB** (probado con Logitech 1080p) conectada a la laptop.
- La laptop en la **misma red** que el ESP32 con WLED (cuando lo tengas). Para probar sin LEDs, hay **modo simulación**.

## Cómo correrlo

```bash
cd tools/camera-ambilight
node server.js
# abre http://localhost:8099 en el navegador
```

Opcional, fijar la IP de WLED de una vez:

```bash
WLED_IP=192.168.1.80 node server.js          # Git Bash / Linux / Mac
# PowerShell:  $env:WLED_IP="192.168.1.80"; node server.js
```

## Pasos en la página

1. **Encender cámara** → acepta el permiso del navegador. Elige la Logitech en el selector si hay varias.
2. Apunta/coloca la webcam de modo que vea **toda la pantalla de la TV** (de frente, lo más posible).
3. **Calibrar pantalla** → *Marcar esquinas* y haz clic en las 4 esquinas del contenido, en orden: arriba-izq, arriba-der, abajo-der, abajo-izq. Esto corrige el ángulo y recorta el cuarto alrededor.
4. Ajusta los **LEDs por lado** (arriba/derecha/abajo/izquierda) según tu tira. El orden empieza en la esquina arriba-izq y va en sentido horario; usa *Invertir* y *Desfase* si la tira empieza en otro punto u otra dirección.
5. **Ajustes:** brillo, saturación, gamma, suavizado (anti-parpadeo) y FPS.
6. Pon la **IP de WLED** y **desactiva Simulación**. Usa **Probar R/G/B** para confirmar comunicación; luego el ambilight corre solo.

Todos los ajustes se guardan en el navegador (localStorage).

## Cómo funciona

- `index.html`: usa `getUserMedia` (webcam) + canvas. Muestrea pequeñas zonas justo dentro de cada borde del cuadro calibrado, aplica saturación/gamma/brillo y suavizado temporal, y las manda al servidor ~15-20 veces/seg.
- `server.js`: recibe los colores y los reenvía a WLED como paquete **DRGB** (`[2][2][R,G,B...]`) al puerto **21324** — idéntico a [`WledUdpClient.java`](../../app/src/main/java/com/openambientcore/WledUdpClient.java).

## Configuración de WLED (cuando llegue el ESP32)

1. Flashea WLED al ESP32, conéctalo al WiFi y anota su IP.
2. En WLED pon la **cantidad de LEDs** de tu tira (debe coincidir con el total que marca la página).
3. WLED acepta realtime UDP por defecto; si no, revisa *Config → Sync Interfaces → Realtime*.

## Limitaciones / siguiente paso

- La cámara debe ver la pantalla; la luz del cuarto afecta un poco el color (igual que Govee/Hue).
- Para algo **permanente** (sin la laptop), el mismo enfoque se mueve a una Raspberry Pi con webcam, o a una ESP32-CAM pegada a la TV. Este prototipo valida colores, calibración y mapeo antes de invertir en eso.
