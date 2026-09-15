# AGENTS.md — Development Guidelines

## Project Overview

Android app (Java + XML) for SEU door lock control. Replaces the original 住理生活 (Zhuli Life) hybrid WebView app with a native implementation supporting phone+password login, NFC door unlock, and BLE door unlock.

- **applicationId**: `com.whxinna.userplatform` (MUST match original for NFC AAR routing from screen-off)
- **Java package**: `com.midairlogn.seodorunlock` (source lives here; applicationId differs for NFC AAR compatibility)
- **Min SDK**: 24 (Android 7.0) / **Target SDK**: 34
- **Build**: Gradle, AndroidX, Java 11+, AGP 9.2.1

## Build & Run

```bash
./gradlew assembleDebug          # build debug APK
./gradlew assembleRelease        # release (ProGuard enabled, see app/proguard-rules.pro)
```

No tests, lint tasks, or CI pipelines exist. The `benchmark/` directory is empty.

## Architecture

Single-module Gradle project. All source under `app/src/main/java/com/midairlogn/seodorunlock/`:

| Package | Key Files | Role |
|---|---|---|
| (root) | `App.java`, `SettingsActivity.java`, `AppExecutors.java` | Application class, settings, thread pool |
| `crypto/` | `CRC8.java`, `RC4.java`, `KeyDerivation.java` | Shared NFC/BLE crypto primitives |
| `nfc/` | `NfcCommandBuilder.java`, `NfcUnlockManager.java` | 40-byte NFC frame + reader mode transceive + activation |
| `ble/` | `BleCommandBuilder.java`, `BleUnlockManager.java` | 20-byte BLE frame + GATT connect flow + activation |
| `api/` | `AuthApi.java`, `CredentialApi.java`, `ApiClient.java` | Login (async + `loginSync` for silent re-login), captcha, door lock sync, activation, request signing |
| `alipay/` | `AlipayAuth.java` | Alipay AIDL payment authentication |
| `storage/` | `SecurePrefs.java`, `CredentialCache.java` | Android Keystore + AES-GCM encrypted prefs |
| `ui/` | `LoginActivity.java`, `MainActivity.java`, `CaptchaDialogFragment.java` | Phone+password login, main unlock screen |
| `model/` | `LoginResponse.java`, `DoorLockInfo.java`, `DoorResponse.java`, `BleResponse.java`, `NfcActivationStep.java` | Data classes |

## Coding Conventions

- **Java only** — no Kotlin, no Compose
- **XML layouts** with Material Design 3
- **OkHttp3** for HTTP, no Retrofit
- **Async**: `ExecutorService` + `Handler`, no RxJava/Coroutines
- **NFC**: `NfcAdapter.enableReaderMode()` with `FLAG_READER_NFC_A`, NOT `enableForegroundDispatch`
- **Logging**: `android.util.Log`, tag prefix `ZL_`
- **i18n**: English default (`values/`), Chinese (`values-zh/`)

## Critical Constraints

1. **`applicationId` MUST be `com.whxinna.userplatform`** — field NFC tags contain an AAR for this package. Changing it breaks NFC wake-up from screen-off.
2. **`NfcA.transceive()` sends the entire 40-byte frame in one shot** — do NOT use page writes (`0xA2`).
3. **RC4 and CRC8 use exact constants from `docs/PROTOCOL_REFERENCE.md` §5** — any deviation breaks lock communication.
4. **BLE GATT UUIDs are fixed**: Service `0xFF12`, Write `0xFF01`, Read/Notify `0xFF02`.
5. **Project ID and App ID are dynamic** — obtained from login response (`server_info.project_id` / `server_info.app_id`). Defaults: `21048` / `20104`.
6. **Business request nonce is 32 characters** (same as auth). Both use `generateNonce(32)`.
7. **Digital credential activation** — when `credentialHex` is blank but `deviceId` > 0, the app must run the NFC/BLE activation handshake (`command/create` → transceive → `command/parse` loop) before unlocking.
8. **Cached `session_secret` expires server-side** — any newer login (this app or another device) invalidates it; business requests then fail with `api_sign_error`. Recovery: silent re-login with stored phone/password (`AuthApi.loginSync`) and retry once (see `docs/PROTOCOL_REFERENCE.md` §11).
9. **Signed set must equal sent set** — never send a query/form param with an empty value; empty params are stripped before signing (`ApiClient.withoutEmptyParams`). A param present-but-empty in the request while absent from the sign source breaks the signature.

## Security Notes

- Passwords sent as plaintext in GET params (server limitation)
- Credentials stored with Android Keystore + AES-GCM — never log sensitive data
- Session secret rotates each login — always use latest for signing

## Protocol Reference

`docs/PROTOCOL_REFERENCE.md` is the source of truth for all crypto constants, frame formats, API endpoints, and signing logic. `docs/RESEARCH_FINDINGS.md` has reverse-engineering notes. `backup/` holds older copies.
