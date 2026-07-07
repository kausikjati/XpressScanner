# Xpress Scanner

Xpress Scanner is an Android barcode wedge-style app that uses a phone camera to scan QR codes and common 1D/2D barcodes, then sends the scanned value to a paired PC or Mac over Bluetooth Classic Serial Port Profile (SPP). It can also send manually typed numbers or codes over the same Bluetooth connection.

## Features

- Camera-based QR and barcode scanning with CameraX and Google ML Kit Barcode Scanning.
- Bluetooth paired-device popup picker and RFCOMM/SPP connection flow with secure/insecure connection fallback.
- Automatic scan sending with short duplicate suppression.
- Improved manual value entry with a larger text field, keyboard send action, and send button for typed barcode/number workflows.

## PC/Mac setup notes

1. Pair the Android phone with the computer in Android Bluetooth settings first.
2. Run or configure a Bluetooth SPP/RFCOMM receiver on the computer. Pairing alone is not enough; the PC/Mac must be listening for an incoming serial/RFCOMM connection.
3. Open Xpress Scanner, choose the paired computer, tap **Connect**, then scan or manually send values.

Each value is sent as UTF-8 text followed by a newline, which makes it easy for the computer-side receiver to treat scans like line-delimited scanner input.


## Run in Android Studio

1. Install Android Studio with the Android SDK and Android SDK Platform 35.
2. Open this repository folder in Android Studio. The committed Gradle wrapper (`./gradlew`) lets Android Studio sync the same Gradle version used by the project.
3. If Android Studio does not create `local.properties` automatically, copy `local.properties.example` to `local.properties` and set `sdk.dir` to your Android SDK path.
4. Let Gradle sync finish, connect an Android phone, and run the `app` configuration.
5. Grant camera and Bluetooth permissions on first launch.

> Note: The computer side must expose a Bluetooth Serial Port Profile / RFCOMM receiver. The Android app sends scanned or manually typed values as newline-delimited UTF-8 text.

## Note for source-only PRs

This repository intentionally does not commit `gradle/wrapper/gradle-wrapper.jar` because some review systems reject binary files in pull requests. If your Android Studio installation requires the wrapper JAR, run `gradle wrapper --gradle-version 8.9 --distribution-type bin` once locally, or let Android Studio use its bundled Gradle to sync the project.

## Troubleshooting launch crashes

`MainActivity` uses AndroidX `ComponentActivity`, which is a `LifecycleOwner` required by CameraX `bindToLifecycle`, while still avoiding AppCompat theme dependencies.

On Android 12 and newer, Bluetooth APIs can throw a security exception if they are called before the Nearby devices permission is granted. The app requests Bluetooth permission only when you tap **Refresh devices** or **Connect**, then waits for that permission before checking paired devices or connecting; if Bluetooth is off, turn it on from Android settings and tap **Refresh devices**.
