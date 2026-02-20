![WhatsApp Image 2026-02-20 at 08 57 07](https://github.com/user-attachments/assets/ca83f34d-10b7-48ec-bd81-542d17f96109)
# BombasQR (appAirsoft)

Aplicacion Android para dinamicas de airsoft con codigos QR.  
Permite configurar partidas desde un panel admin y jugar en varios modos con temporizador y escaneo por camara.

## Caracteristicas

- Escaneo QR en tiempo real (CameraX + ML Kit).
- 4 modos de juego:
  - `BOMB`
  - `HUNT_N_CODES`
  - `SEQUENCE_CODES`
  - `KING_OF_THE_QR`
- Panel admin con PIN para configuracion.
- Temporizador de partida.
- Sonidos y feedback de estado.
- Boton de aborto con pulsacion prolongada (10s).

## Tecnologias

- Kotlin
- Jetpack Compose
- AndroidX
- CameraX
- Google ML Kit Barcode Scanning
- Gradle (Kotlin DSL)

## Requisitos

- Android Studio (recomendado: version reciente)
- JDK 17
- SDK Android:
  - `compileSdk 34`
  - `targetSdk 34`
  - `minSdk 26`

## Estructura principal

- `app/src/main/java/com/appairsoft/MainActivity.kt`  
  Contiene la UI Compose, logica de estados y flujo del juego.
- `app/src/main/AndroidManifest.xml`  
  Permiso de camara y configuracion de la app.
- `app/build.gradle.kts`  
  Dependencias y configuracion de compilacion.

## Instalacion y ejecucion

1. Clona el repositorio.
2. Abre el proyecto en Android Studio.
3. Deja que Gradle sincronice dependencias.
4. Ejecuta en dispositivo/emulador con camara.

Tambien puedes usar consola:

```bash
./gradlew installDebug
