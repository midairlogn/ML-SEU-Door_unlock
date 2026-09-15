# 住理生活 (Zhuli Life) - APK Research Findings

## App Overview

| Field | Value |
|---|---|
| App Name | 住理生活 (Zhuli Life) |
| Package | `com.whxinna.userplatform` |
| Version | 3.11.51 (code 617) |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 |
| Packer | **SecShell** (encrypted DEX in `assets/apps/zhuli.zip`) |
| Real Application Class | `com.whxinna.zhuli.MainApplication` |
| Server Domain | `uc-zhuli.whxinna.com` |

> **Important**: The app is protected by SecShell packer. All business logic (login, NFC door lock, BLE communication) is encrypted in `assets/apps/zhuli.zip` and decrypted at runtime by `libSecShell.so`. The smali directory only contains the SecShell unpacking stub and third-party library code. The findings below are derived from the AndroidManifest, resource strings, native JavaScript bridge APIs, and test HTML files bundled in the APK.

---

## 1. Login Mechanism

### 1.1 Architecture: WebView + DSBridge Hybrid

The app uses a **hybrid architecture** where the main UI is rendered via WebView loading remote/local HTML, and native capabilities are exposed through a **DSBridge** JavaScript bridge. The JS API is namespaced under the `zl` global object.

### 1.2 Login Methods

The app supports **multiple authentication methods**:

#### a) WeChat OAuth Login
- **Namespace**: `__native_oauth__`
- **API**: `zl.oauth.sendAuth({ type: "WeChat", info: "" }, callback)`
- **WeChat AppID**: `wx9622aee7b9ae7536` (from `strings.xml:1081`)
- **WeChat AppSecret**: `425b4b4ad72c0a317f28bc6b311adfd3` (from `strings.xml:1082`)
- **WeChat Package**: `com.tencent.mm`
- Activities: `WXEntryActivity`, `WXPayEntryActivity` in `com.whxinna.userplatform.wxapi`
- Mini Program support: `zl.wechat.launchMiniProgram("gh_91739dab54c2", ...)` — WeChat Official Account ID `gh_91739dab54c2`

#### b) Phone Number One-Click Login (China Mobile)
- **Namespace**: `__native_cmic__`
- **API**: `zl.cmic.loginAuth(...)` / `zl.cmic.getPhoneInfo(...)`
- Uses China Mobile's `GenLoginAuthActivity` (`com.cmic.gen.sdk.view.GenLoginAuthActivity`)
- This is the "one-tap phone number verification" service (号码认证) provided by China Mobile

#### c) Alipay OAuth Login
- **Namespace**: `__native_oauth__`
- **API**: `zl.oauth.sendAuth({ type: "Alipay", info: "" }, callback)`

#### d) Face Recognition Login/Verification
- **Namespace**: `__native_face__`
- **API**: `zl.face.init(config, callback)`
- Uses Baidu Brain face SDK (`libbdface_sdk.so`)
- Activities: `FaceActivity`, `FaceLivenessExpActivity`, `FaceDetectExpActivity`, `FaceHomeAgreementActivity`, `CollectionSuccessActivity`
- Liveness detection supports: Eye blink, Mouth open, Head nod (up/down), Head turn (left/right)
- Face data collection for identity verification (实名认证场景)

#### e) Biometric (Fingerprint/Device Credential) Authentication
- **Namespace**: `__native_biometric__`
- **API**: `zl.biometric.start(...)`
- Activity: `BiometricActivity` (`com.whxinna.biometric.BiometricActivity`)
- Uses AndroidX Biometric API

### 1.3 Login Flow (Inferred)

Based on the architecture and available APIs:

1. **Splash Screen** (`SplashActivity`) → Privacy agreement check
2. **WebView-based login page** loaded from server (`zhuli.whxinna.com`)
3. User selects login method:
   - **Phone number + SMS verification code** (via China Mobile CMIC service)
   - **WeChat OAuth** (redirect to WeChat app, get auth code, exchange for token)
   - **Alipay OAuth** (similar flow)
4. After authentication, a **session token** is stored locally via `zl.storage.set()` (encrypted with key `R9Tdbuh5KCXNa09fL4hGaVY5wh6WRMSW`, prefix `ETD#`)
5. **Face recognition** may be required for sensitive operations (door lock management, payments)
6. **Biometric** can be used for quick re-authentication

### 1.4 Server Endpoints

| Endpoint | Purpose |
|---|---|
| `https://uc-zhuli.whxinna.com` | Main API / NFC deep-link handler |
| `https://zhuli.whxinna.com/agreement.html` | Service agreement |
| `https://zhuli.whxinna.com/privacy.html` | Privacy policy |
| `http://nps.whxinna.com:9639/alipay_notify/recharge_notify` | Alipay payment callback |

### 1.5 Security Notes

- The WeChat AppSecret is **hardcoded in plaintext** in `strings.xml` — this is a security vulnerability
- Shared preferences encryption key: `R9Tdbuh5KCXNa09fL4hGaVY5wh6WRMSW` (prefix: `ETD#`)
- AES seed: `!@#$` (from `strings.xml:68`)
- Private key: `#e$r` (from `strings.xml:745`)

---

## 2. Door Lock Opening Mechanism

### 2.1 NFC Method (Primary)

#### 2.1.1 NFC Configuration

- **NFC is mandatory**: `<uses-feature android:name="android.hardware.nfc" android:required="true"/>`
- **Permission**: `android.permission.NFC`
- **NFC Handler Activity**: `com.whxinna.nfc.NfcPendingActivity` (singleInstance, transparent theme)

#### 2.1.2 NFC Intent Filters

The `NfcPendingActivity` handles three NFC discovery modes:

| Intent Action | Data/Filter |
|---|---|
| `NDEF_DISCOVERED` | `https://uc-zhuli.whxinna.com` (host match) |
| `TECH_DISCOVERED` | Filtered by `nfc_tech_filter.xml` |
| `TAG_DISCOVERED` | Default category |

#### 2.1.3 Supported NFC Technologies

From `res/xml/nfc_tech_filter.xml`:
- `android.nfc.tech.IsoDep`
- `android.nfc.tech.NfcA`
- `android.nfc.tech.NfcB`
- `android.nfc.tech.NfcF`
- `android.nfc.tech.NfcV`
- `android.nfc.tech.Ndef`
- `android.nfc.tech.NdefFormatable`
- `android.nfc.tech.MifareClassic`
- `android.nfc.tech.MifareUltralight`

This comprehensive tech support means the app can interact with virtually any NFC tag type used in door lock systems.

#### 2.1.4 NFC Tag Data Format

From `assets/nfc_test.html`, the default NFC tag data is:

```
https://uc-zhuli.whxinna.com?d=259289
```

- **URL scheme**: `https`
- **Host**: `uc-zhuli.whxinna.com`
- **Parameter `d`**: Device/door identifier (e.g., `259289`)

When an NFC tag containing this URL is scanned by the phone (even outside the app), Android triggers `NfcPendingActivity` via the NDEF_DISCOVERED intent, which then deep-links into the app to handle the door unlock operation.

#### 2.1.5 NFC API (JavaScript Bridge)

**Namespace**: `__native_nfc__`

| Method | Description |
|---|---|
| `status()` | Get NFC status (supported, enabled, initialized, tag present) |
| `init()` | Initialize NFC and start listening for tags |
| `write({ data, timeout })` | Write data to NFC tag (async, 15s timeout) |
| `release()` | Release all NFC resources |
| `cancelWrite()` | Cancel ongoing write operation |

**Events**:
| Event | Description |
|---|---|
| `onTagDiscovered` | Fired when an NFC tag is detected |
| `onTagReaderFail` | Fired when tag reading fails |

**Write Status Callbacks**: `waiting` → `detected` → `connected` → `writing` → `success`

#### 2.1.6 NFC Door Lock Flow (Inferred)

1. **Tag Programming**: During door lock setup, the app writes an NDEF URL record (`https://uc-zhuli.whxinna.com?d={deviceId}`) to an NFC sticker/tag placed on the door lock
2. **Door Opening**: User taps phone on the NFC tag
3. **Intent Handling**: Android fires `NDEF_DISCOVERED` → `NfcPendingActivity` is launched
4. **Deep-link Processing**: Activity parses the `d` parameter (device ID) from the URL
5. **Authentication Check**: App verifies user is logged in and has permission for this device
6. **Unlock Command**: App sends unlock command to the server (or processes locally)
7. **Door Opens**: Lock receives unlock signal

#### 2.1.7 NFC System Event

The native bridge also exposes a system-level NFC event:
- **Namespace**: `__native_system__`
- **Event**: `onAppNfcTag` — allows WebView pages to receive NFC tag data when the app is in foreground

---

### 2.2 Bluetooth (BLE) Method (Secondary)

#### 2.2.1 BLE Configuration

- **Feature**: `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>` (optional)
- **Permissions**:
  - `BLUETOOTH` (max SDK 30)
  - `BLUETOOTH_ADMIN` (max SDK 30)
  - `BLUETOOTH_SCAN`
  - `BLUETOOTH_ADVERTISE`
  - `BLUETOOTH_CONNECT`
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` / `ACCESS_BACKGROUND_LOCATION` (required for BLE scanning on older Android)

#### 2.2.2 BLE API (JavaScript Bridge)

**Namespace**: `__native_bluetooth__`

| Method | Description |
|---|---|
| `init({ services_uuid, read_uuid, write_uuid })` | Initialize BLE with specific GATT service/characteristic UUIDs |
| `startScan()` | Start scanning for BLE devices |
| `stopScan()` | Stop scanning |
| `connect(deviceName)` | Connect to a BLE device by name |
| `write(data)` | Write data to the connected device |
| `disconnect()` | Disconnect from device |
| `close()` | Close BLE resources |

**Events**:
| Event | Description |
|---|---|
| `onDevicesDiscovered` | Fired when a BLE device is found during scan |
| `onConnected` | Connection established |
| `onConnectTimeouted` | Connection timed out |
| `onServicesDiscovered` | GATT services discovered |
| `onDisconnected` | Connection lost |
| `onCharacteristicChanged` | Received data notification |
| `onCharacteristicRead` | Read response |
| `onCharacteristicWrite` | Write confirmation |
| `onScanError` | Scan error |

#### 2.2.3 BLE Door Lock Communication Parameters

From `assets/testPage.html`, the BLE door lock uses these GATT characteristics:

| Parameter | UUID |
|---|---|
| Service UUID | `0000ff12-0000-1000-8000-00805f9b34fb` |
| Read Characteristic | `0000ff02-0000-1000-8000-00805f9b34fb` |
| Write Characteristic | `0000ff01-0000-1000-8000-00805f9b34fb` |

> These UUIDs are standard 16-bit UUIDs in the Bluetooth SIG reserved range (`0xFF00-0xFFFF`), commonly used by Chinese smart lock manufacturers (e.g., Tuya, Zhiji, etc.).

#### 2.2.4 BLE Device Naming Convention

From the test code:
```javascript
if (`XN-1136247` == deviceName) {
    zl.bluetooth.stopScan();
    zl.bluetooth.connect(deviceName);
}
```

- Device name pattern: `XN-{deviceId}` (e.g., `XN-1136247`)
- The `deviceid` corresponds to the internal device identifier (e.g., `1136247`)

#### 2.2.5 BLE Door Lock Flow (Inferred)

1. **Scan**: App scans for nearby BLE devices matching `XN-{deviceId}` pattern
2. **Filter**: Only connect to devices whose IDs match door locks assigned to the user
3. **Connect**: Establish BLE GATT connection
4. **Discover Services**: Discover GATT services on the lock
5. **Send Command**: Write unlock command to the write characteristic (`0xFF01`)
6. **Read Response**: Read acknowledgment from the read characteristic (`0xFF02`)
7. **Door Opens**: Lock executes the unlock command

---

### 2.3 MQTT Push Notifications

- **Service**: `com.whxinna.mqtt.MqttMessageService` (MQTT-based push messaging)
- **MQTT Library**: Eclipse Paho (`org.eclipse.paho.android.service.MqttService`)
- **Purpose**: Likely used for remote door lock commands, status updates, and real-time notifications
- Push notification support: Mi Push, Huawei HMS Push, OPPO Push, vivo Push, Meizu Push, Honor Push

---

## 3. Key Components Summary

| Component | Class | Purpose |
|---|---|---|
| Splash Screen | `com.whxinna.zhuli.SplashActivity` | App entry, privacy check |
| Browser | `com.whxinna.zhuli.BrowserActivity` | Main WebView container |
| Mini Program | `com.whxinna.zhuli.MiniProgramActivity` | Mini-program container |
| NFC Handler | `com.whxinna.nfc.NfcPendingActivity` | NFC deep-link handler |
| Face Recognition | `com.whxinna.face.FaceActivity` | Baidu face SDK |
| QR Scanner | `com.whxinna.scan.ScanActivity` | QR/barcode scanning |
| WebView | `com.whxinna.webview.WebViewActivity` | Generic WebView |
| Biometric | `com.whxinna.biometric.BiometricActivity` | Fingerprint auth |
| WeChat Entry | `com.whxinna.userplatform.wxapi.WXEntryActivity` | WeChat OAuth callback |
| MQTT Service | `com.whxinna.mqtt.MqttMessageService` | Push messaging |

---

## 4. Native Libraries (Relevant)

| Library | Purpose |
|---|---|
| `libSecShell.so` | SecShell packer (decrypts `zhuli.zip` at runtime) |
| `libbdface_sdk.so` | Baidu face recognition SDK |
| `libscannative.so` | QR/barcode scanning |
| `libsignature_check.so` | APK signature verification |
| `libentryexpro.so` | Entry SDK |

---

## 5. Conclusions

1. **Login** is primarily handled through a **WebView-based flow** using the DSBridge JS bridge, supporting **WeChat OAuth**, **Alipay OAuth**, **China Mobile one-click phone login**, and **face recognition** for identity verification. The actual login API calls and token management are encrypted in the SecShell-packed DEX.

2. **Door lock opening** uses **both NFC and Bluetooth**:
   - **NFC** (primary, required): Tags store a URL `https://uc-zhuli.whxinna.com?d={deviceId}` that triggers deep-link navigation into the app. The app writes NFC tags during setup and reads them to identify which door to unlock.
   - **Bluetooth BLE** (secondary, optional): Connects to BLE door locks via GATT service `0xFF12` with read/write characteristics `0xFF02`/`0xFF01`. Devices follow the naming pattern `XN-{deviceId}`.

3. The core business logic (API endpoints, unlock protocol, encryption of BLE commands) is **hidden inside the SecShell-encrypted DEX** (`assets/apps/zhuli.zip`) and cannot be fully analyzed through static analysis alone. Dynamic analysis (e.g., Frida runtime hooking) would be needed to extract the actual unlock protocol and API calls.

---

## 6. Cross-Validation & Field Findings

The protocol documented here has been cross-checked against independent working implementations of the same backend. Confirmed facts:

1. **Signing**: identical algorithm — sorted `k=v` pairs (quotes/spaces stripped), `&key=<secret>` appended, uppercase MD5; `pid=21048` / `appid=20104` constants for the Jiulonghu campus deployment.
2. **Session secret expiry (confirmed)**: the login-issued `session_secret` is invalidated server-side by any newer login. Business requests signed with a stale secret are rejected with `err_msg: "api_sign_error"`. Reference clients treat cached-secret refresh as best-effort and fall back to a **full re-login with stored credentials** on auth failure. This project implements the same recovery (`CredentialApi.reloginSilently` + one retry; `AuthApi.loginSync`).
3. **Empty params**: reference implementations never send empty parameter values. This project enforces the same invariant (`ApiClient.withoutEmptyParams`) because a param sent as `key=` while absent from the sign source also produces `api_sign_error`.
4. **Server quirks**: business servers are deployed behind multiple replicas that can respond inconsistently ("同一接口时好时坏"); responses may contain dirty bytes in JSON string values, so lenient parsing/salvage extraction of strongly-formatted fields (64-hex credentials, integer IDs) is recommended.

### 6.1 Debugging notes

- `err_msg` values surface in logcat wrapped as `JSONException` (from `ApiClient.extractDataField`); the full rejection payload is logged under `ZL_ApiClient` (`Server rejected request: {...}`).
- A BLE notification race previously crashed `handleCharacteristicChanged` (NPE reading `lastNotificationData` after the executor nulled it); the log now reads the local notification buffer inside the synchronized block.
