# Secret Gallery (Android native)

Secret Gallery is a native Android app project. It encrypts selected image/video files into the app's private internal storage using AES-256-GCM. It then asks Android to approve deletion of the original shared MediaStore items.

## Critical limitations and safety

- This is a starter project, not a professionally audited security product.
- Minimum Android version: Android 11 (API 30).
- The app uses an 8–12 digit PIN with PBKDF2-HMAC-SHA256 (600,000 iterations) to derive the vault encryption key. A PIN is lower entropy than a long passphrase; this design is not equivalent to a hardware-backed credential vault.
- App-private storage is protected by Android's app sandbox. Keep your phone's screen lock enabled.
- No cloud sync or server is used. The encrypted files are stored only in the app's internal private directory.
- Uninstalling the app deletes its private vault data. There is no export/backup feature in this starter. Do not import your only copy of important photos until you have a tested backup/export workflow.
- The app verifies the encrypted file by decrypting/authenticating it before attempting source deletion.
- Android deletion requires a system confirmation. If declined or unsupported, the encrypted copy remains but the original remains in the gallery too.
- Files selected from a document provider other than MediaStore are encrypted but are not automatically deleted.
- The starter stores encrypted originals and encrypted metadata, but does not provide thumbnails or in-app image/video preview yet.
- Android manufacturers' gallery apps may cache thumbnails temporarily; a successful MediaStore deletion removes the shared media item but cached thumbnails may take time to refresh.
- Never interpret a success toast as proof if Android's confirmation was declined. Verify in your gallery and in the app.

## Build with Android Studio (recommended)

1. Install Android Studio on a computer.
2. Extract this project.
3. Open the `SecretGallery` folder in Android Studio.
4. Allow Gradle sync and install Android SDK Platform 35 if prompted.
5. Connect your Android phone with USB debugging enabled.
6. Choose Run, select your phone, and install.

To build a debug APK:

```bash
gradle assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## Build from Termux (advanced)

Building Android apps entirely in Termux is not officially supported by the Android Gradle Plugin. It requires a compatible JDK, Gradle, Android SDK command-line tools, platform 35 and build-tools. The easiest reliable workflow is Android Studio on a computer. If you want to try Termux, first install a compatible JDK and Gradle and configure `ANDROID_HOME` to a working Android SDK installation:

```bash
pkg update && pkg upgrade -y
pkg install openjdk-17 gradle git unzip -y
```

You must separately install/configure Android SDK platform 35 and build-tools 35.x. Then from the project root:

```bash
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
gradle assembleDebug
```

If Gradle or the Android Gradle Plugin fails due to Android/Termux host incompatibility, build with Android Studio on a computer instead. Do not download random prebuilt APKs.

## Install the generated APK

Copy `app/build/outputs/apk/debug/app-debug.apk` to your phone and open it. Android may ask you to allow installation from that file manager/browser. Only install the APK you built yourself.

## Source deletion flow

1. The user selects media using Android's document picker.
2. The app copies the bytes directly into an AES-GCM encrypted file in internal app storage.
3. The app reads and authenticates the entire encrypted copy.
4. The app stores the metadata index encrypted.
5. For MediaStore content URIs, Android's `MediaStore.createDeleteRequest` system confirmation is shown.
6. If the user approves, Android deletes the shared original. If declined, the original remains.

## Project structure

```text
SecretGallery/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── README.md
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/secretgallery/
        │   ├── MainActivity.kt
        │   └── VaultCrypto.kt
        └── res/values/strings.xml
```
