# Xpress Scanner

Xpress Scanner is an Android barcode wedge-style app that uses a phone camera to scan QR codes and common 1D/2D barcodes, then sends the scanned value to a paired PC or Mac over Bluetooth Classic Serial Port Profile (SPP). It can also send manually typed numbers or codes over the same Bluetooth connection.

## Features

- Camera-based QR and barcode scanning with CameraX and Google ML Kit Barcode Scanning.
- Bluetooth paired-device picker and RFCOMM/SPP connection flow.
- Automatic scan sending with short duplicate suppression.
- Manual value entry and send button for typed barcode/number workflows.

## PC/Mac setup notes

1. Pair the Android phone with the computer in Android Bluetooth settings first.
2. Run or configure a Bluetooth SPP/RFCOMM receiver on the computer.
3. Open Xpress Scanner, choose the paired computer, tap **Connect**, then scan or manually send values.

Each value is sent as UTF-8 text followed by a newline, which makes it easy for the computer-side receiver to treat scans like line-delimited scanner input.
