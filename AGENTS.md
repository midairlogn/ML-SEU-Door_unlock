# AGENTS.md — Development Guidelines

## Project Overview

Android app (Java + XML) for SEU door lock control. Replaces the original 住理生活 (Zhuli Life) hybrid WebView app with a native implementation supporting phone+password login, NFC door unlock, and BLE door unlock.

- **applicationId**: `com.whxinna.userplatform` (MUST match original for NFC AAR routing from screen-off)
- **Java namespace**: `com.midairlogn.doorunlock` (source code package, no hardware dependency)
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34
- **Build**: Gradle, AndroidX, Java 11+

## Architecture

```
app/
├── build.gradle
│   applicationId "com.whxinna.userplatform"   # NFC AAR match
│   namespace "com.midairlogn.doorunlock"       # Java source package
│
├── src/main/java/com/midairlogn/doorunlock/
│   ├── App.java                          # Application class
│   ├── ui/
│   │   ├── LoginActivity.java            # Phone + password login
│   │   ├── MainActivity.java             # Main door unlock screen
│   │   └── CaptchaDialogFragment.java    # SVG captcha input
│   ├── nfc/
│   │   ├── NfcUnlockManager.java         # NFC reader mode, transceive
│   │   └── NfcCommandBuilder.java        # 40-byte frame construction
│   ├── ble/
│   │   ├── BleUnlockManager.java         # BLE GATT connect + unlock flow
│   │   └── BleCommandBuilder.java        # 20-byte frame construction
│   ├── crypto/
│   │   ├── KeyDerivation.java            # deriveKey(deviceId)
│   │   ├── RC4.java                      # RC4 encrypt/decrypt
│   │   └── CRC8.java                     # CRC8 with lookup table
│   ├── api/
│   │   ├── AuthApi.java                  # Login, captcha, register, reset
│   │   ├── CredentialApi.java            # Door lock info, credential sync
│   │   └── ApiClient.java               # OkHttp client, signing, base64
│   ├── storage/
│   │   ├── SecurePrefs.java              # Android Keystore + AES-GCM
│   │   └── CredentialCache.java          # Cached credentials, MAC, token
│   └── model/
│       ├── LoginResponse.java
│       ├── DoorLockInfo.java
│       └── DoorResponse.java
├── src/main/res/
│   ├── layout/
│   │   ├── activity_login.xml
│   │   ├── activity_main.xml
│   │   └── dialog_captcha.xml
│   ├── xml/
│   │   └── nfc_tech_filter.xml
│   └── values/
│       ├── strings.xml
│       └── themes.xml
```

## Coding Conventions

- **Language**: Java (no Kotlin)
- **UI**: XML layouts, Material Design 3, no Jetpack Compose
- **Networking**: OkHttp3 for HTTP, no Retrofit
- **Storage**: SharedPreferences with Android Keystore-backed AES-GCM encryption
- **NFC**: `NfcAdapter.enableReaderMode()` with `FLAG_READER_NFC_A`, NOT `enableForegroundDispatch`
- **BLE**: AndroidX `androidx.core:core-ktx` BLE APIs or raw `BluetoothGatt`
- **Async**: `ExecutorService` + `Handler` for callbacks, no RxJava/Coroutines
- **Logging**: `android.util.Log`, tag prefix `ZL_`

## Critical Constraints

1. **applicationId MUST be `com.whxinna.userplatform`** — the NFC tags in the field contain an AAR for this package. Changing it breaks NFC wake-up from screen-off. The Java source namespace `com.midairlogn.doorunlock` is independent and can be anything.
2. **`NfcA.transceive()` sends the entire 40-byte frame in one shot** — do NOT use page writes (`0xA2`).
3. **RC4 and CRC8 use exact constants from PROTOCOL_REFERENCE.md §5** — any deviation breaks lock communication.
4. **BLE GATT UUIDs are fixed**: Service `0xFF12`, Write `0xFF01`, Read/Notify `0xFF02`.
5. **Project ID is hardcoded**: `21048` (SEU Jiulonghu Campus). App ID: `20104`.

## Security Notes

- Passwords are sent as plaintext in GET params (server limitation, not our choice)
- Store credentials encrypted with Android Keystore + AES-GCM
- Never log sensitive data (credentials, tokens, keys)
- Session secret rotates each login — always use the latest one for signing

## File Map

| File | Reference Section |
|---|---|
| `KeyDerivation.java` | PROTOCOL_REFERENCE.md §5.1 |
| `RC4.java` | PROTOCOL_REFERENCE.md §5.2 |
| `CRC8.java` | PROTOCOL_REFERENCE.md §5.3 |
| `NfcCommandBuilder.java` | PROTOCOL_REFERENCE.md §6.3 |
| `NfcUnlockManager.java` | PROTOCOL_REFERENCE.md §6.4 |
| `BleCommandBuilder.java` | PROTOCOL_REFERENCE.md §7.2 |
| `BleUnlockManager.java` | PROTOCOL_REFERENCE.md §7.4 |
| `AuthApi.java` | PROTOCOL_REFERENCE.md §3.1, §3.2 |
| `CredentialApi.java` | PROTOCOL_REFERENCE.md §4.1, §4.2 |
| `ApiClient.java` | PROTOCOL_REFERENCE.md §5 (signing) |
| `SecurePrefs.java` | PROTOCOL_REFERENCE.md §10, Appendix A |
