# cardo-record

Demo Android: abre la línea de voz Bluetooth SCO con un intercom Cardo (modo "llamada") y graba el mic del casco con `VOICE_COMMUNICATION` a WAV 16 kHz mono.

- Android 12+: `setCommunicationDevice()` con el dispositivo `TYPE_BLUETOOTH_SCO`.
- Android < 12: `startBluetoothSco()` + espera a `SCO_AUDIO_STATE_CONNECTED` (timeout 6 s → mic por defecto).
- Archivo: `/sdcard/Android/data/com.mycompany.cardorecorder/files/cardo_YYYYMMDD_HHMMSS.wav`

## Build

GitHub Actions (`.github/workflows/android.yml`) compila en cada push a `main`:
- APKs en el artifact **cardo-recorder-apk** del run.
- Tag `v*` (ej. `git tag v0.1.0 && git push --tags`) → GitHub Release con el APK.

Local: `gradle assembleDebug` (Gradle 8.10+, JDK 17, Android SDK).

Sacar la grabación: `adb pull /sdcard/Android/data/com.mycompany.cardorecorder/files/`
