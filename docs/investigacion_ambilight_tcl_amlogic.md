# Investigación técnica: Ambilight DIY en TCL mediante estadísticas del procesador

**Fecha:** 21 de octubre de 2026  
**Estado:** investigación exploratoria; no hay prueba funcional confirmada en una TCL específica.

## 1. Objetivo

Crear un sistema de iluminación ambiental sincronizada con el contenido de una televisión TCL usando:

- Una aplicación o servicio interno en el televisor, si la plataforma lo permite.
- Estadísticas de imagen del procesador (preferentemente colores por regiones), **sin capturar ni transmitir fotogramas completos**.
- Envío únicamente de valores de color por Wi-Fi a un **ESP32**.
- Una tira LED direccionable controlada por **WLED** u otro firmware.
- Compatibilidad deseada con aplicaciones internas como Netflix, YouTube y Disney+, incluso cuando se reproduce contenido protegido.

**Limitación crítica:** no se ha demostrado que los controladores de la TCL concreta permitan consultar estadísticas de color durante la reproducción protegida por DRM. La existencia de registros o estructuras de datos en código público de Amlogic no garantiza acceso en todos los SoC ni firmwares.

## 2. Cómo funciona Ambilight integrado en Philips

Los modelos Philips que incluyen Ambilight incorporan de fábrica LEDs en el chasis y una integración con su procesamiento de imagen. Analizan información de la imagen y controlan las luces desde componentes internos. Esto no implica que una app externa tenga acceso equivalente a los píxeles ni que Philips «eluda» el DRM: es una arquitectura de fabricante integrada y autorizada.

## 3. Por qué no basta una app Android de captura

En Android TV/Google TV, aplicaciones basadas en MediaProjection o capturas convencionales suelen obtener superficies negras para video protegido por mecanismos DRM (por ejemplo Widevine) o por flags de ventana segura. HDCP protege determinadas conexiones digitales HDMI y no es exactamente lo mismo que el DRM de una aplicación nativa.

La propuesta consiste en investigar estadísticas calculadas por hardware o controladores del fabricante, **no** intentar capturar la pantalla. Que estas estadísticas existan tampoco significa que se puedan leer desde una app de terceros durante Netflix.

## 4. Proyectos existentes revisados

| Proyecto | Enfoque | Utilidad | Limitación para este objetivo |
|---|---|---|---|
| [Hyperion Android Reborn](https://github.com/evanwhitt/hyperion-android-reborn) | Captura Android y envío a sistemas Ambilight; menciona compatibilidad TCL | Referencia de integración y rendimiento | Captura protegida por DRM puede salir negra |
| [HyperFlatSender](https://github.com/Uroshino/HyperFlatSender) | MediaProjection y envío a Hyperion | Arquitectura y optimización | No resuelve acceso a contenido protegido |
| [HyperHDRGrabber](https://github.com/Lpanos/HyperHDRGrabber) | ADB/scrcpy con equipo externo | Alternativa para contenidos capturables | No demuestra acceso a contenido DRM |
| [DIY Ambilight + WLED](https://gist.github.com/billyshub/f017bbde2d6f88dd51bd152f0d7f1adb) | Guía de Hyperion y LEDs | Referencia para ESP32 y tira | No resuelve origen RGB protegido |
| [TCL-TV-reverse-engineering](https://github.com/prototux/TCL-TV-reverse-engineering) | Investigación de firmware y bibliotecas TCL antiguas | Referencia de ingeniería inversa | No demuestra interfaz RGB en modelos modernos |
| [TCL Android ADB / TV Manager](https://gist.github.com/det-peralta/eab8c1ffbc0263c9dcd9359ccbdedd45) | Exploración de servicios TCL | Pistas sobre servicios internos | Dependiente de modelo y firmware |

Los proyectos basados en captura convencional se incluyen como antecedentes, **no como solución propuesta**, dado que el requisito es evitar ese método.

## 5. Hallazgos en controladores Amlogic

### 5.1. VDIN: funciones de histogramas

En versiones públicas de controladores Amlogic se identificaron llamadas como:

```c
vdin_dump_histgram(devp);

#ifdef CONFIG_AML_LOCAL_DIMMING
vdin_dump_histgram_ldim(devp, hnum, vnum);
#endif
```

**Fuente:** [vdin_debug.c — Android Git at Google](https://android.googlesource.com/kernel/arm64/+/f5269100977385d1fd4a5ef68e49631892cf4fe4/drivers/amlogic/media/vin/tvin/vdin/vdin_debug.c)

**Precaución:** en una versión examinada de `vdin_dump_histgram_ldim`, un arreglo local se inicializa en ceros y la copia de estadísticas reales aparece comentada. Por ello, **esa rutina de depuración por sí sola no prueba que entregue datos reales**.

### 5.2. Registros de histogramas por regiones para Local Dimming

En `ldim_reg.h` aparecen definiciones:

```c
#define LDIM_STTS_HIST_REGION_IDX       0x1ad0
#define LDIM_STTS_HIST_SET_REGION       0x1ad1
#define LDIM_STTS_HIST_READ_REGION      0x1ad2
#define LDIM_STTS_HIST_START_RD_REGION  0x1ad3
```

**Fuente:** [ldim_reg.h — Nest Open Source](https://nest-open-source.googlesource.com/manifest_repos/kernel/+/1650886512330522da41d487bb2ce75fd5da5ec8/drivers/amlogic/media/vout/lcd/backlight/bl_ldim/ldim_reg.h)

Estos nombres indican soporte de estadísticas de histograma por regiones en esa implementación. Los histogramas pueden describir luminancia y **no equivalen necesariamente a colores RGB promedio**.

### 5.3. Estructura `max_rgb` por región

En la investigación previa se identificó una función de depuración denominada:

```c
static void ldim_dump_matrix_info_max_rgb(
    struct aml_ldim_driver_s *ldim_drv)
```

que consulta una matriz de estadísticas:

```c
ldim_drv->stts->max_rgb
```

con tres componentes por zona, esquemáticamente:

```c
p[n * 3]       // R
p[n * 3 + 1]   // G
p[n * 3 + 2]   // B
```

**Interpretación:** esta es la pista más cercana a la idea de extraer RGB espacial sin usar captura convencional. Sin embargo, `max_rgb` puede contener máximos de canal y **no un color representativo/promedio**. El formato, ruta de producción y compatibilidad dependen del SoC, de la versión del controlador y de la configuración de Local Dimming. Se mencionaron rutas diferenciadas para T7/T3.

**Referencia de exploración:** buscar `ldim_dump_matrix_info_max_rgb`, `stts->max_rgb`, `ldim_debug.c` en árboles públicos de controladores Amlogic. La conversación anterior no dejó una URL permanente verificada al archivo exacto de esa función.

### 5.4. VDIN: muestreo RGB por coordenadas

También se identificaron los símbolos:

```c
vdin_prob_set_xy(..., x, y, ...);
vdin_prob_get_rgb(..., &r, &g, &b);
```

**Interpretación:** algunas rutas VDIN incluyen un mecanismo de consulta de componentes RGB en una posición. Debe verificarse qué señal observa, en qué fase del pipeline funciona y si está disponible para las aplicaciones internas protegidas. VDIN podría no procesar todas las rutas de imagen del televisor.

**Referencia inicial:** [árbol Amlogic VDIN en Android Git](https://android.googlesource.com/kernel/arm64/+/f5269100977385d1fd4a5ef68e49631892cf4fe4/drivers/amlogic/media/vin/tvin/vdin/vdin_debug.c).

## 6. Diferencias importantes entre los datos

| Dato | ¿Indica posición? | ¿Indica color? | ¿Sirve directamente para Ambilight? |
|---|---|---|---|
| Histograma global RGB | No | Distribución de canales | No, falta ubicación |
| APL global | No | Principalmente brillo | No |
| Histograma por región | Sí | Puede ser solo luminancia | Parcialmente |
| Estadísticas de Local Dimming | Sí | A menudo luminancia | Parcialmente |
| `max_rgb` por región | Sí | Componentes RGB máximas | Potencialmente; requiere validación |
| RGB por coordenadas VDIN | Sí | Sí, en una muestra | Potencialmente; depende de la ruta de video |

## 7. Arquitectura deseada

```text
Apps internas / HDMI
        |
        v
Procesamiento de imagen del SoC TCL
        |
        v
Estadísticas de color por región (si existen y son accesibles)
        |
        v
Servicio interno de lectura y mapeo de bordes
        |
        v
Paquetes RGB por Wi-Fi (UDP/DDP u otro protocolo compatible)
        |
        v
ESP32 con WLED
        |
        v
Tira LED direccionable detrás de la TV
```

Se podría comenzar con unas 20–30 actualizaciones por segundo, pero la frecuencia viable dependerá de las interfaces de hardware, la latencia, la estabilidad y el procesamiento necesario. **No se ha probado todavía**.

## 8. El problema decisivo: DRM y rutas de imagen

Hay que responder por separado:

1. ¿El SoC concreto de la TCL calcula histogramas o estadísticas RGB espaciales?
2. ¿Esos datos describen la imagen final, incluidas las apps internas, o solamente entradas específicas del pipeline?
3. ¿El fabricante expone un nodo `sysfs`, `debugfs`, `ioctl`, HAL o servicio para consultarlos?
4. ¿Puede acceder un proceso ordinario, uno privilegiado o solo código del kernel?
5. ¿Se mantienen disponibles durante Netflix/Disney+/Prime Video con reproducción protegida?
6. ¿Los datos representan colores útiles o solamente brillo/máximos?
7. ¿La consulta es suficientemente rápida para sincronizar iluminación?

**No debe asumirse** que la disponibilidad de una estadística de hardware elimina las restricciones de seguridad, ni que obtener root resolverá el acceso. Tampoco se plantea eludir HDCP/Widevine.

## 9. Plan de investigación, sin modificar el televisor

### Fase A — Identificación

Necesitamos **modelo exacto de TCL**, versión de firmware, sistema operativo y SoC.

Con depuración ADB autorizada, comenzar con consultas de solo lectura:

```bash
adb shell getprop ro.board.platform
adb shell getprop ro.hardware
adb shell uname -a
adb shell ls /sys/class/
```

Estos comandos son exploratorios; la ausencia de nombres relevantes en `/sys/class` no descarta que haya interfaces en otras rutas.

### Fase B — Identificación de interfaces

- Buscar módulos/controladores relacionados con `ldim`, `vdin`, `amvecm`, `vpp`, `pq` y estadísticas de imagen.
- Identificar qué servicios del fabricante y bibliotecas existen realmente en ese firmware.
- Comparar el kernel/SoC con árboles de código fuente compatibles.
- Revisar permisos sin alterarlos.

### Fase C — Pruebas de funcionamiento

- Comprobar si alguna interfaz accesible ofrece estadísticas cambiantes al mostrar patrones de color no protegidos.
- Evaluar si los valores varían por región y se corresponden con el contenido.
- Determinar si la misma interfaz sigue entregando datos durante una aplicación protegida, **sin intentar evadir controles de DRM**.
- Medir frecuencia, estabilidad y calidad de representación cromática.

### Fase D — Prototipo ESP32

Solo si existe una fuente RGB válida:

- Traducir las regiones del procesador a segmentos del perímetro.
- Calibrar gamma, saturación, brillo y suavizado temporal.
- Enviar colores por Wi-Fi al ESP32.
- Usar WLED y una tira direccionable apropiada.
- Validar sincronización y consumo eléctrico; dimensionar correctamente la fuente de alimentación.

## 10. Riesgos y límites

- **Compatibilidad:** no todos los televisores TCL usan Amlogic; algunos usan otras plataformas.
- **Local Dimming:** disponer de esta función no implica una interfaz RGB expuesta.
- **Seguridad:** los nodos de depuración pueden no estar presentes en builds comerciales o requerir privilegios.
- **DRM:** las rutas de reproducción segura pueden impedir la lectura de información útil.
- **Calidad:** los máximos RGB por región podrían generar colores exagerados o incorrectos.
- **Firmware:** intentar root, escritura en registros o modificación de firmware puede inutilizar el equipo y no forma parte de la fase inicial.

## 11. Conclusión

**Hipótesis viable para investigar, no solución demostrada.** El hallazgo más interesante es la presencia de estructuras como `stts->max_rgb` y registros de histogramas por regiones en implementaciones públicas de Amlogic. Estos antecedentes justifican investigar si una TCL concreta expone estadísticas cromáticas del pipeline de imagen, pero no prueban compatibilidad con Netflix ni acceso desde una app normal.

**Siguiente dato indispensable:** modelo exacto del televisor TCL, versión de firmware y plataforma del SoC. A partir de eso se puede descartar o confirmar la relevancia de estos controladores y diseñar una prueba de solo lectura.

---

### Referencias principales

1. [Amlogic `vdin_debug.c` — Android Git](https://android.googlesource.com/kernel/arm64/+/f5269100977385d1fd4a5ef68e49631892cf4fe4/drivers/amlogic/media/vin/tvin/vdin/vdin_debug.c)
2. [Amlogic `ldim_reg.h` — Nest Open Source](https://nest-open-source.googlesource.com/manifest_repos/kernel/+/1650886512330522da41d487bb2ce75fd5da5ec8/drivers/amlogic/media/vout/lcd/backlight/bl_ldim/ldim_reg.h)
3. [Hyperion Android Reborn](https://github.com/evanwhitt/hyperion-android-reborn)
4. [HyperFlatSender](https://github.com/Uroshino/HyperFlatSender)
5. [HyperHDRGrabber](https://github.com/Lpanos/HyperHDRGrabber)
6. [DIY Ambilight + WLED](https://gist.github.com/billyshub/f017bbde2d6f88dd51bd152f0d7f1adb)
7. [TCL-TV-reverse-engineering](https://github.com/prototux/TCL-TV-reverse-engineering)
8. [TCL Android ADB / TV Manager](https://gist.github.com/det-peralta/eab8c1ffbc0263c9dcd9359ccbdedd45)

*Nota de trazabilidad: documento compilado a partir de los hallazgos descritos en esta conversación. Algunas funciones se reportaron sin enlace directo verificable a su implementación exacta; se identifican expresamente para su validación posterior.*
