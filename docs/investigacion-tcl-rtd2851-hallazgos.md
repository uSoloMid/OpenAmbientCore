# Hallazgos: ambilight interno en TCL (Realtek RTD2851M)

**Fecha:** 21 de octubre de 2026
**TV analizado:** TCL Smart TV Pro — `BeyondTV4` / `G03_4K_GB`, Google TV (Android 11), build `R51MT05` (`V8-R51MT05`), SoC **Realtek RTD2851M** (`ro.hardware = rtd288o`), 32-bit, `release-keys`.
**Objetivo:** obtener los colores del contenido en pantalla (sobre todo YouTube e IPTV) para un ambilight con tira LED WS2812B vía ESP32 + WLED, **sin capturar/transmitir la imagen completa** y sin evadir DRM.

> **TL;DR / Veredicto:** La TV sí calcula estadísticas de color por zona internamente, pero **todas las vías para leerlas desde una app normal están cerradas** (SELinux + bootloader bloqueado sin desbloqueo posible). La captura por códec H.264 *sí* ve el contenido (incluido YouTube), pero **choca con el reproductor por falta de núcleos de códec** en este chip y queda inestable. **Recomendación: ambilight por cámara externa** apuntando a la pantalla (ver [tools/camera-ambilight/](../tools/camera-ambilight/README.md)).

---

## 1. Resumen de todo lo probado

| Vía | ¿App normal? | Resultado | Estado |
|---|---|---|---|
| Estadísticas VPQ (`getPixelInfo`, APL 12×24, histograma) vía binder `sitatvservice` | ✅ llega (SELinux permite `/dev/sbinder`) | Devuelve **ceros** con YouTube y con HDMI | ❌ |
| `MaxRGB`/histograma por zona del VPQ (ioctl directo) | ❌ | `/dev/vpqexdev` solo system/root (SELinux) | 🔒 root |
| Realtek PQ (`com.realtek.tv.PQ`, HIDL `IRtkTvService`) | ❌ | Solo `platform_app`/`system_app` | 🔒 firma TCL |
| Captura interna TCL (`CaptureScreen.CapturePicture`) | ✅ llega | `ret=true` pero **0 bytes** (plano de video) | ❌ |
| MediaProjection + ImageReader (la app actual) | ✅ | Capta UI, pero **YouTube en negro** (plano HW) | ⚠️ |
| MediaProjection + **códec H.264** ("Codec compatibility") | ✅ | **Sí capta YouTube** (colores reales por borde) pero pelea por el códec → se traba / gris | ⚠️ inestable en este chip |
| Nodos `/sys` `/proc` `debugfs` con stats de color | ✅ shell | No existen legibles; solo `rhal_tp` (poke de registros, riesgoso) | ❌ |
| Root (software) | — | **Bootloader bloqueado, sin desbloqueo** (ver §5) | 🔒 imposible por software |
| **Cámara externa** | n/a | Lee la luz emitida; funciona con todo | ✅ **elegido** |

---

## 2. Arquitectura del problema

El video de las apps (YouTube, streaming) se dibuja en un **plano de hardware (overlay) separado** del framebuffer de Android. Por eso:

- `MediaProjection`/`ImageReader` y las capturas normales obtienen **negro** sobre ese plano.
- El procesador de imagen (VPQ de Realtek) **sí** calcula estadísticas de color sobre la imagen, pero expone esos datos solo por `ioctl` a `/dev/vpqexdev`, reservado a procesos de sistema.

No es DRM lo que bloquea YouTube en la captura — es la arquitectura de planos. El DRM (Widevine L1) solo aplica a Netflix/Disney/Prime.

## 3. Estadísticas VPQ (lo más cercano a "solo colores")

Ingeniería inversa de `/vendor/lib/librtk_kadp_vpq.so` con Ghidra 11.0.3. Los lectores de estadística son `ioctl()` sobre **`/dev/vpqexdev`** (sin "enable" por software; son ioctls pelados):

| Función HAL | ioctl | Devuelve |
|---|---|---|
| `KADP_VPQ_GetPixelInfo_TV030` | `0x4004541d` | struct `ScreenPixelInfo` (64 B): sumas/min/max R,G,B de un rect |
| `KADP_VPQ_HDR_Histogram_MaxRGB` | `0x8001542c` | **RGB máximo por zona** (lo ideal para ambilight) |
| `KADP_VPQ_GetHistoData_APL` | `0x80047177` | APL (nivel de brillo) |
| `KADP_VPQ_GetHistogram` | `0x80047152` | histograma |
| `KADP_VPQ_Get_HUE_Histogram` | `0x80045110` | histograma de tono |
| `KADP_VPQ_Get_SAT_Histogram` | `0x80045111` | histograma de saturación |
| `KADP_VPQ_LED_LDGetAPL` | `0x80026c08` | APL de local dimming (este panel = 0 zonas) |

Init del fd: `KADP_VPQEX_OPEN` hace `ioctl(fd, 0x7102)` / `0x7302` sobre `/dev/vpqexdev`.

**Importante:** vía el daemon (`sitatvservice`), `getPixelInfo` devolvió **cero** con YouTube. Es probable que estas estadísticas se llenen solo para la ruta de **sintonizador/HDMI**, no para el plano de las apps. Es decir, **ni con root está garantizado** que sirvan para YouTube.

## 4. SELinux

`/dev/vpqexdev` tiene permisos DAC `0666`, pero su tipo SELinux `rtk_vpq_device` solo lo pueden abrir: `rtk_tv_service_default`, `tcl_sitatvservice`, `hal_neuralnetworks_default`, `rtkm_*`, `tvservice`. **No** `untrusted_app` ni `shell`. El bloqueo es del kernel (MAC), no de la app — saltarlo requiere root (`setenforce 0` o editar política).

## 5. Root: imposible por software en este equipo

Estado leído de la propia TV (`getprop`):

```
ro.boot.flash.locked       = 1          (bootloader BLOQUEADO)
ro.boot.verifiedbootstate  = green      (verified boot estricto)
ro.boot.vbmeta.device_state = locked
sys.oem_unlock_allowed     = 0          (desbloqueo NO permitido)
ro.boot.veritymode         = enforcing
```

- `oem_unlock_allowed = 0` → no hay forma de desbloquear desde Opciones de desarrollador.
- TCL usa **authboot** (fastboot que exige autorización de servidores TCL) → sin desbloqueo oficial.
- `verifiedbootstate = green` → rechaza cualquier `boot` modificado (Magisk) → root "sin desbloqueo" tampoco arranca.
- Únicas vías restantes: hardware (UART U-Boot, desoldar eMMC) = alto riesgo de brick, no dan sistema rooteado estable.

Para el uso objetivo (YouTube) **perder Widevine L1 no afectaría** (YouTube no usa L1), pero da igual: **no se puede rootear por software.**

## 6. Captura por códec H.264 (la que sí ve YouTube)

Método "Codec (compatibility)" (de *hyperion-android-reborn*): `MediaProjection → VirtualDisplay → MediaCodec encoder (Surface) → decoder → ImageReader`. El encoder de hardware **sí** recibe el plano de video compuesto por HWC. Probado en esta TV (`tools/codec-test`):

- **Funciona:** con YouTube reproduciéndose, el encoder produce frames reales (hasta ~160 KB) y con decoder de **hardware** salieron **colores reales por borde** (ej. arriba `(140,117,92)`).
- **Problema:** el gestor de códec del RTD2851M **le quita el decodificador a nuestra app y la mata** (`Decoder is absent, kill current process!`) cuando YouTube también lo usa → se traba / pantalla gris.
- Con decoder **por software** no pelea, pero en pruebas de un solo hilo salió oscuro/lento (requiere pipeline multihilo bien hecho; resultado incierto en este chip).
- Detalle clave: lanzar una Activity visible **pausa** YouTube; la captura debe correr desde servicio en segundo plano (o con retraso) para capturar reproduciendo.

Conclusión: viable pero frágil en este SoC por falta de núcleos de códec.

## 7. DRM

Netflix/Disney/Prime usan Widevine L1: el video se decodifica en **memoria protegida por hardware**, ilegible para el SO/apps. No hay forma (ni con root) de leer esos píxeles, y no se intenta evadir. YouTube/IPTV (sin DRM) sí son capturables.

## 8. Herramientas creadas (en `tools/`)

- **`tcl-probe/`** — sonda `app_process` + reflexión para llamar `getPixelInfo`/APL/histograma del SDK TCL.
- **`cap-test/`** — APK que prueba la captura interna `CaptureScreen` (devolvió 0 bytes).
- **`codec-test/`** — APK del método códec H.264 (capturó YouTube; inestable por recursos).
- **`camera-ambilight/`** — **el camino elegido:** ambilight por cámara → WLED. Ver su README.

## 9. Conclusión y recomendación

La TV sí tiene la información de color (estructuras `MaxRGB`/histograma del VPQ), pero está sellada: SELinux la reserva al sistema, y el bootloader no se puede desbloquear. La captura por códec la alcanza pero no convive de forma estable con el reproductor en este chip.

**La vía robusta, sin root, sin riesgo y que funciona con todo (YouTube, IPTV, lo que sea) es la cámara externa** apuntando a la pantalla — lee la luz emitida, como los ojos; es lo que hacen Govee y Philips Hue. Implementación en [tools/camera-ambilight/](../tools/camera-ambilight/README.md).

*Si algún día esta TV fuera rooteable, la receta de ioctl del §3 permitiría leer los colores directo (sujeto a que el VPQ los calcule para el plano de apps).*

### Referencias

- [hyperion-android-reborn — método "Codec compatibility"](https://github.com/evanwhitt/hyperion-android-reborn)
- [TCL firmware tracker (R51MT05 / G03_4K_GB)](https://faserf.github.io/TCL-Discussion-Telegram/firmwares/)
- [Rooting TCL RT51/RT2851/BeyondTV (XDA)](https://xdaforums.com/t/official-rooted-image-for-tcl-rt51-rt2851-beyondtv-android-9-plattform.4065717/)
- [bootloader-unlock-wall-of-shame — TCL authboot](https://github.com/zenfyrdev/bootloader-unlock-wall-of-shame/blob/main/brands/tcl/README.md)
- [GUIDE: Unbricking TCL TVs](https://xdaforums.com/t/guide-unbricking-tcl-tvs.4776759/)
