# BEIQI-ST Digital Counter Android App

Native Android application designed to connect to the **BEIQI-ST** Bluetooth counter (`7A:EC:97:9F:C2:B8`) and register a click count every time the physical counter button is clicked.

## Features
- **Auto-connects** to BEIQI-ST (MAC: `7A:EC:97:9F:C2:B8`).
- **Auto-discovers** GATT Notify/Indicate characteristics without hardcoding unknown vendor UUIDs.
- **Debounced Click Counting**: Registers each button press reliably.
- **Haptic Vibration**: Gives physical feedback on the phone for each click.
- **Packet Inspector**: Displays the raw hex data stream received from the ring in real time.
- **Reset Button**: Quickly zero the tally.

## How to Get the APK

### Option 1: Automated Free Cloud Build (GitHub Actions)
1. Push this folder to a GitHub repository (public or private).
2. Go to the **Actions** tab on your GitHub repository.
3. The `Build Android APK` workflow will run automatically.
4. When finished (approx. 2 mins), download **`BEIQI-ST-Counter-App`** under Artifacts, unzip it, and transfer `app-debug.apk` directly to your Android phone to install!

### Option 2: Android Studio
1. Open this folder in Android Studio.
2. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
3. Locate the generated APK under `app/build/outputs/apk/debug/app-debug.apk` and transfer to your phone.
