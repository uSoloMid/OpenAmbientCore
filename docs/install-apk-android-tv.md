# Instalar APK en Android TV

APK incluido en el repo:

```text
artifacts/OpenAmbientCore-debug-v0.1.0.apk
```

## Opcion 1: instalar por ADB USB

En la TV:

1. Activa Opciones de desarrollador.
2. Activa Depuracion USB.
3. Conecta la TV o Android TV box a la laptop por USB.

En Windows, desde la carpeta del repo:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
& $adb install -r artifacts\OpenAmbientCore-debug-v0.1.0.apk
```

## Opcion 2: instalar por ADB WiFi

En la TV:

1. Activa Opciones de desarrollador.
2. Activa Depuracion por red, si tu Android TV la trae.
3. Anota la IP de la TV.

En Windows:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect IP_DE_TU_TV:5555
& $adb devices
& $adb install -r artifacts\OpenAmbientCore-debug-v0.1.0.apk
```

Ejemplo:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect 192.168.1.80:5555
& $adb install -r artifacts\OpenAmbientCore-debug-v0.1.0.apk
```

## Compilar APK desde el repo

Si tienes Android Studio / SDK instalado:

```powershell
.\gradlew.bat assembleDebug --no-problems-report
```

APK generado:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Probar WLED cuando llegue el ESP32

1. Flashea WLED al ESP32.
2. Conecta el ESP32 al WiFi.
3. Entra a WLED desde navegador y copia su IP.
4. En OpenAmbientCore pon:

```text
IP: IP_DEL_ESP32
LEDs: 150
FPS: 15
Brillo: 35-45
```

5. Toca "Probar colores WLED".
6. Si la tira cambia rojo/verde/azul, ya esta comunicando.
7. Toca "Iniciar Ambilight".

