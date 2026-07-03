# AGENTS.md — Development Guidelines

## Project Overview

Android app (Java + XML) for SEU door lock control. Replaces the original 住理生活 (Zhuli Life) hybrid WebView app with a native implementation supporting phone+password login, NFC door unlock, and BLE door unlock.

- **applicationId**: `com.whxinna.userplatform` (MUST match original for NFC AAR routing from screen-off)
- **Java package**: `com.whxinna.userplatform` (same as applicationId — source lives here)
- **Min SDK**: 24 (Android 7.0) / **Target SDK**: 34
- **Build**: Gradle, AndroidX, Java 11+, AGP 9.2.1

## Build & Run

```bash
./gradlew assembleDebug          # build debug APK
./gradlew assembleRelease        # release (ProGuard enabled, see app/proguard-rules.pro)
```

No tests, lint tasks, or CI pipelines exist. The `benchmark/` directory is empty.

## Architecture

Single-module Gradle project. All source under `app/src/main/java/com/whxinna/userplatform/`:

| Package | Key Files | Role |
|---|---|---|
| `crypto/` | `CRC8.java`, `RC4.java`, `KeyDerivation.java` | Shared NFC/BLE crypto primitives |
| `nfc/` | `NfcCommandBuilder.java`, `NfcUnlockManager.java`, `NfcPendingActivity.java` | 40-byte NFC frame + reader mode transceive |
| `ble/` | `BleCommandBuilder.java`, `BleUnlockManager.java` | 20-byte BLE frame + GATT connect flow |
| `api/` | `AuthApi.java`, `CredentialApi.java`, `ApiClient.java` | Login, captcha, door lock sync, request signing |
| `storage/` | `SecurePrefs.java`, `CredentialCache.java` | Android Keystore + AES-GCM encrypted prefs |
| `ui/` | `LoginActivity.java`, `MainActivity.java`, `CaptchaDialogFragment.java` | Phone+password login, main unlock screen |
| `model/` | `LoginResponse.java`, `DoorLockInfo.java`, `DoorResponse.java`, `BleResponse.java` | Data classes |

## Coding Conventions

- **Java only** — no Kotlin, no Compose
- **XML layouts** with Material Design 3
- **OkHttp3** for HTTP, no Retrofit
- **Async**: `ExecutorService` + `Handler`, no RxJava/Coroutines
- **NFC**: `NfcAdapter.enableReaderMode()` with `FLAG_READER_NFC_A`, NOT `enableForegroundDispatch`
- **Logging**: `android.util.Log`, tag prefix `ZL_`

## Critical Constraints

1. **`applicationId` MUST be `com.whxinna.userplatform`** — field NFC tags contain an AAR for this package. Changing it breaks NFC wake-up from screen-off.
2. **`NfcA.transceive()` sends the entire 40-byte frame in one shot** — do NOT use page writes (`0xA2`).
3. **RC4 and CRC8 use exact constants from `docs/PROTOCOL_REFERENCE.md` §5** — any deviation breaks lock communication.
4. **BLE GATT UUIDs are fixed**: Service `0xFF12`, Write `0xFF01`, Read/Notify `0xFF02`.
5. **Project ID**: `21048` (SEU Jiulonghu Campus). App ID: `20104`.

## Security Notes

- Passwords sent as plaintext in GET params (server limitation)
- Credentials stored with Android Keystore + AES-GCM — never log sensitive data
- Session secret rotates each login — always use latest for signing

## Protocol Reference

`docs/PROTOCOL_REFERENCE.md` is the source of truth for all crypto constants, frame formats, API endpoints, and signing logic. `PLAN.md` contains the original design (may be partially outdated). `docs/RESEARCH_FINDINGS.md` has reverse-engineering notes. `backup/` holds older copies.
