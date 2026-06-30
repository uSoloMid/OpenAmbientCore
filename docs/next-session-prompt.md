# Prompt para continuar OpenAmbientCore

Actua como Software Architect Senior y Android Engineer.

Contexto:

- Repo: `https://github.com/uSoloMid/OpenAmbientCore`
- Objetivo: app Ambilight para Android TV / Google TV.
- Hardware objetivo inicial: Android TV -> MediaProjection -> WiFi UDP -> ESP32 con WLED -> tira WS2812B 5V.
- APK debug actual en repo: `artifacts/OpenAmbientCore-debug-v0.1.0.apk`
- App actual:
  - pide permiso de captura con MediaProjection;
  - corre un foreground service;
  - captura imagen reducida a 96x54;
  - promedia colores del borde;
  - suaviza colores;
  - manda frames DRGB por UDP a WLED puerto 21324;
  - incluye prueba rojo/verde/azul;
  - configura IP, LED count, FPS y brillo.

Lo que sigue:

1. Probar instalacion en Android TV.
2. Confirmar que abre, pide permiso de captura y no crashea.
3. Probar sin ESP32 primero: revisar UI y permisos.
4. Cuando haya ESP32 con WLED:
   - configurar WLED;
   - poner cantidad de LEDs;
   - obtener IP;
   - probar boton "Probar colores WLED";
   - luego probar "Iniciar Ambilight".
5. Mejorar app:
   - agregar preview visual de zonas dentro de la app;
   - guardar estado del servicio;
   - boton de "modo simulacion" sin WLED;
   - descubrir WLED por mDNS si es posible;
   - agregar selector de layout: start corner, direction, top/right/bottom/left counts;
   - mejorar manejo de errores UDP;
   - agregar README de cableado.

Restricciones:

- No evadir DRM de Netflix/Crunchy/Disney/etc.
- Si una app protegida se ve negra por MediaProjection, se documenta como limite del sistema.
- Mantener el primer prototipo simple: Java/Android SDK, sin Compose por ahora.

