# SEU Door Lock

Native Android app (Java) for Southeast University (SEU) Jiulonghu Campus door lock control. Replaces the original 住理生活 (Zhuli Life) hybrid WebView app with a clean native implementation.

## Features

- **Phone + Password Login** — 6-digit numeric password, session persisted locally via Android Keystore + AES-256-GCM
- **NFC Door Unlock** — Tap phone on door lock NFC tag, `NfcA.transceive()` sends 40-byte encrypted command in one shot
- **BLE Door Unlock** — Connect via Bluetooth Low Energy, cached MAC direct connect with scan fallback
- **Credential Sync** — Automatic door lock info and credential synchronization from server
- **Offline-capable** — Credentials cached locally, NFC unlock works without network after initial sync

## Requirements

- Android 7.0+ (SDK 24)
- NFC-enabled device (required)
- Bluetooth LE (optional, for BLE unlock)

## Build

Open in Android Studio or build from command line:

```bash
./gradlew assembleDebug
```

The `applicationId` is `com.whxinna.userplatform` — this must match the AAR embedded in existing NFC tags in the field.

## Architecture

```
crypto/       CRC8, RC4, KeyDerivation (shared NFC/BLE primitives)
nfc/          NfcCommandBuilder (40-byte frame), NfcUnlockManager (reader mode + transceive)
ble/          BleCommandBuilder (20-byte frame), BleUnlockManager (GATT connect + unlock flow)
api/          AuthApi (login, captcha), CredentialApi (door lock sync), ApiClient (signing)
storage/      SecurePrefs (Keystore + AES-GCM), CredentialCache (typed accessors)
ui/           LoginActivity, MainActivity, CaptchaDialogFragment
model/        LoginResponse, DoorLockInfo, DoorResponse, BleResponse
```

## Protocol

See [docs/PROTOCOL_REFERENCE.md](docs/PROTOCOL_REFERENCE.md) for full protocol documentation including:

- Login endpoint and request signing
- Cryptographic primitives (deriveKey, RC4, CRC8)
- NFC 40-byte command frame format
- BLE 20-byte command frame and GATT UUIDs
- Response parsing and result codes

## Project Constants

| Constant | Value | Note |
|---|---|---|
| `applicationId` | `com.whxinna.userplatform` | Must match NFC tag AAR |
| Project ID | `21048` | SEU Jiulonghu Campus |
| App ID | `20104` | Server app identifier |
| Auth Server | `https://pm.whxinna.com` | Login, captcha |
| BLE Service UUID | `0xFF12` | Door lock GATT service |
| BLE Write UUID | `0xFF01` | Write characteristic |
| BLE Read UUID | `0xFF02` | Read/notify characteristic |
