<div align="center">
<h1>SEU Door Lock</h1>
Native Android client for Southeast University Jiulonghu Campus door lock control, replacing the original Zhuli Life hybrid app.<br><br>

**English** | [**中文简体**](README.md)
</div>

## Usage

1. **Login**: Alipay quick login is recommended. Phone number + 6-digit password login is also supported. Credentials are encrypted locally with Android Keystore + AES-256-GCM.
2. **NFC Unlock**: Tap your phone on the door lock NFC tag to unlock — no need to open the app (screen must be on).
3. **BLE Unlock**: Tap the unlock button on the home screen to auto-connect and unlock nearby door locks.
4. **Settings**: Switch language (English/Chinese/system) and theme (light/dark/system).

> First use requires network to sync door lock info and credentials. After that, NFC unlock works offline.

## Requirements

- Android 7.0 (SDK 24) or higher
- NFC (required)
- Bluetooth LE (optional, for BLE unlock)

## Installation

Download the latest APK from the [Releases](https://github.com/midairlogn/ML-SEU-Door_unlock/releases) page.

Or build from source:

```bash
git clone https://github.com/midairlogn/ML-SEU-Door_unlock.git
./gradlew assembleDebug
```

## Tech Stack

- **Language**: Java
- **Networking**: OkHttp 3
- **UI**: AndroidX, Material Design 3
- **Crypto**: Android Keystore + AES-256-GCM, RC4, CRC8

## License

[GNU General Public License v3.0](LICENSE)
