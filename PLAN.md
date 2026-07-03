# PLAN.md — SEU Door Lock Android App

## Phase 1: Project Setup & Crypto Module

### 1.1 Android Project Initialization
- Create Gradle project:
  - `applicationId = "com.whxinna.userplatform"` (NFC AAR match — must stay)
  - `namespace = "com.midairlogn.doorunlock"` (Java source package)
- minSdk 24, targetSdk 34, Java 11
- Dependencies: OkHttp3, AndroidX AppCompat, Material3, AndroidX Biometric
- Permissions in manifest: NFC, BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION
- NFC feature required: `<uses-feature android:name="android.hardware.nfc" android:required="true"/>`
- BLE feature optional: `<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>`

### 1.2 Crypto Module
- `CRC8.java` — 256-entry lookup table from docs/PROTOCOL_REFERENCE.md §5.3, `crc8(byte[])` returns `int`
- `RC4.java` — Standard RC4, key = `deriveKey(deviceId)`, KSA uses `key[i % 16]`, PRGA XORs byte-by-byte. Single `encrypt(byte[] data, byte[] key)` method works for both encrypt and decrypt
- `KeyDerivation.java` — `deriveKey(int deviceId)` returns 16 bytes. Split deviceId into 4 LE bytes, reassemble big-endian. 4 rounds of confusion with DELTA0=0x9E3779B9, DELTA_STEP=0x12345678, KEY_CONST from §5.1. Output 4 big-endian uint32 words concatenated

## Phase 2: Local Storage & API Client

### 2.1 Secure Storage
- `SecurePrefs.java` — Encrypt SharedPreferences values using Android Keystore (`AndroidKeyStore`) + AES-256-GCM
- Generate keypair on first run, alias `zl_credential_store`
- Methods: `putString(key, value)`, `getString(key)`, `contains(key)`, `remove(key)`

### 2.2 Credential Cache
- `CredentialCache.java` — Wraps SecurePrefs with typed accessors:
  - `saveSession(phone, password, userId, identityCode, platformToken, sessionSecret, serverUrl)`
  - `saveDoorLock(deviceId, bleMac, credentialHex, credentialId)`
  - `getPhone()`, `getPassword()`, `getUserId()`, `getIdentityCode()`, `getSessionSecret()`, `getServerUrl()`, `getDeviceId()`, `getBleMac()`, `getCredentialHex()`, `getCredentialId()`
  - `hasSession()` → boolean
  - `clear()`

### 2.3 API Client
- `ApiClient.java` — OkHttp singleton:
  - Base URL: `https://pm.whxinna.com` for auth, `serverUrl` for business
  - Request signing: MD5 of sorted params + `sessionSecret` (for business API)
  - Response parsing: base64 decode `data` field → JSON
  - Auth header: `Authorization: Bearer {platform_token}` if available

### 2.4 Auth API
- `AuthApi.java`:
  - `login(phone, pwd, callback)` → `GET /webapi/users/login` with pid=21048, appid=20104, timestamp, noncestr, sign
  - `getLoginCaptcha(phone, callback)` → `GET /webapi/users/get_login_code` → returns SVG string
  - `refreshToken(callback)` → if token expired, re-login with stored phone+password
  - On success: save all fields to CredentialCache, then call `CredentialApi.sync()`

### 2.5 Credential Sync API
- `CredentialApi.java`:
  - `syncDoorLockInfo(callback)` → `GET {serverUrl}/webapi/v1/student/accommodation/details` with user_id, identitycode
  - Parse response: extract `device_id`, `ble_name`, `ble_mac`, `credential`, `credential_id`
  - Save to CredentialCache
  - Fallback: if no credential in response, call `GET {serverUrl}/webapi/v1/staff/door_lock/credentials`
  - `syncCredential(callback)` → triggered by NFC code 24/27 or BLE code 27

## Phase 3: Login UI

### 3.1 LoginActivity
- Layout: `activity_login.xml` — Phone number EditText (numeric, 11 digits), Password EditText (numeric, 6 digits, password toggle), Login Button, "Forgot Password?" link
- Validate: phone matches `^1[3-9]\d{9}$`, password is exactly 6 digits
- On login click: call `AuthApi.login()`
- Show progress indicator during request
- On success: start `MainActivity`
- On captcha required: show `CaptchaDialogFragment`

### 3.2 CaptchaDialogFragment
- Fetch SVG captcha from `AuthApi.getLoginCaptcha()`
- Render SVG in an ImageView (use `WebView` or Android SVG library)
- User enters 4-char code in EditText
- Submit captcha with login request
- Retry up to 3 times

### 3.3 App Startup Flow
- `App.java` `onCreate()`:
  1. Check `CredentialCache.hasSession()`
  2. If yes → skip login, go to `MainActivity`, optionally refresh credentials in background
  3. If no → go to `LoginActivity`
- `LoginActivity` checks if session exists → auto-redirect to `MainActivity`

## Phase 4: NFC Door Unlock

### 4.1 NFC Command Builder
- `NfcCommandBuilder.java`:
  - `buildCommand(int deviceId, String credentialHex, int projectId)` → `byte[40]`
    - Format: `[0xB1, 0x0D, 0x24, ...RC4(40 bytes of projectId_LE ++ credential), CRC8]`
    - See docs/PROTOCOL_REFERENCE.md §6.3
  - `parseResponse(int deviceId, byte[] frame)` → `DoorResponse`
    - Decrypt payload with RC4, check CRC8, extract result code
    - See docs/PROTOCOL_REFERENCE.md §6.5

### 4.2 NFC Unlock Manager
- `NfcUnlockManager.java`:
  - `enableReaderMode(Activity activity)` — call `NfcAdapter.enableReaderMode()` with `FLAG_READER_NFC_A | FLAG_READER_SKIP_NDEF_CHECK`, timeout 1500ms
  - `disableReaderMode(Activity activity)` — in `onPause()`
  - Handle `Tag` from `onTagDiscovered()`:
    1. Get `NfcA` tech from tag
    2. `nfcA.connect()`
    3. Build 40-byte command via `NfcCommandBuilder`
    4. `nfcA.transceive(command)` — one shot, 1500ms timeout
    5. Parse 20-byte response
    6. Result code 0 or 23 = success → door opened
    7. Result code 24/27 → trigger `CredentialApi.syncCredential()` then retry
    8. Other codes → show error
  - Retry logic: 3 attempts with 200ms delay between retries

### 4.3 NFC Tech Filter
- `res/xml/nfc_tech_filter.xml` — list all tech types from §9.3 (NfcA, NfcB, NfcF, NfcV, IsoDep, Ndef, NdefFormatable, MifareClassic, MifareUltralight)

### 4.4 NFC Intent Handling
- `AndroidManifest.xml`: Register `NfcPendingActivity` with `NDEF_DISCOVERED`, `TECH_DISCOVERED`, `TAG_DISCOVERED` intent filters
- `NfcPendingActivity` (transparent, singleInstance): parse `d` parameter from NDEF URL, pass deviceId to `MainActivity`

## Phase 5: BLE Door Unlock

### 5.1 BLE Command Builder
- `BleCommandBuilder.java`:
  - `buildCommand(int deviceId, int commandType, byte[] data)` → `byte[20]`
    - Format: `[0x14, 0x00, commandType, ...RC4(zero-padded 16 bytes), CRC8]`
    - See docs/PROTOCOL_REFERENCE.md §7.2
  - `buildCredentialHeader(int projectId, String credentialHex)` → `byte[20]` (command type 0x74)
    - Plaintext: `[0x28, 0x00, 0x03, CRC8(projectId_LE ++ credential), 0x00...]`
  - `buildCredentialPacket(int packetIndex, byte[] payload)` → `byte[20]` (command type 0x75)
    - Split 40-byte payload (ran_LE ++ projectId_LE ++ credential) into 3 chunks of 15 bytes
  - `buildOpenDoor()` → `byte[20]` (command type 0x78, all zeros)
  - `buildCredentialRefetch(int credentialId)` → `byte[20]` (command type 0x76)
  - `buildReadPacket(int index)` → `byte[20]` (command type 0x77)
  - `parseResponse(int deviceId, byte[] frame)` → `BleResponse`

### 5.2 BLE Unlock Manager
- `BleUnlockManager.java`:
  - **Connection strategy**: Cached MAC direct connect → scan fallback
    1. If `CredentialCache.getBleMac()` is non-empty: attempt `BluetoothDevice.connectGatt()` directly by MAC
    2. If connection fails or MAC is empty: start BLE scan for `XN-{deviceId}`
    3. On scan hit: stop scan, connect to found device
  - **Unlock flow** (once GATT connected + services discovered):
    1. Send 0x74 credential header → parse `ran` from response
    2. Send 0x75 × 3 (credential packets) with 10ms inter-packet delay
    3. Wait 50ms
    4. Send 0x78 open door → parse result code
    5. Result 0 = success, 27 = credential expired → refetch + retry
  - **Credential refetch** (on code 27):
    1. Send 0x76 with credential_id
    2. Parse packet count and total length from response
    3. Send 0x77 × packet_count to read each packet
    4. Merge packets, verify CRC8, update CredentialCache
    5. Retry 0x78
  - Timeout: 10s per connection attempt, 5s per GATT operation

### 5.3 BLE Permissions
- Runtime permission requests for `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION` on Android 12+

## Phase 6: Main UI

### 6.1 MainActivity
- Layout: `activity_main.xml`
  - Top: user info (name/phone), "Logout" button
  - Center: large "Open Door" button (or two buttons: NFC / BLE)
  - Bottom: door status, battery level, last unlock time
- NFC tap indicator (pulsing icon when reader mode active)
- BLE button: triggers `BleUnlockManager.unlock()`
- NFC: handled automatically via reader mode, show feedback on transceive result
- Show toast/snackbar for success/failure

### 6.2 Error Handling
- Network errors: retry with exponential backoff (1s, 2s, 4s), max 3 retries
- NFC failures: show "Tap again" with retry count
- BLE failures: fallback from cached MAC to scan, show "Searching for lock..."
- Auth expired: auto re-login with stored credentials, then retry operation
- All errors logged to `Log.w()` with tag `ZL_`

## Phase 7: Polish & Edge Cases

### 7.1 App Lifecycle
- `onResume()`: enable NFC reader mode if on main screen
- `onPause()`: disable NFC reader mode
- `onNewIntent()` (for NFC wake): parse device_id, trigger unlock if logged in

### 7.2 Background Credential Refresh
- On app start, if session exists, background-sync door lock info
- Store `updatedAt` timestamp, refresh if older than 24 hours

### 7.3 ProGuard Rules
- Keep OkHttp classes
- Keep crypto classes (no reflection, but be safe)
- Keep model classes (Gson serialization if used)

## Implementation Order

1. Project setup + manifest + dependencies
2. Crypto module (CRC8, RC4, KeyDerivation) + unit tests
3. SecurePrefs + CredentialCache
4. ApiClient + AuthApi + CredentialApi
5. LoginActivity + CaptchaDialogFragment
6. NfcCommandBuilder + NfcUnlockManager + NFC activity
7. BleCommandBuilder + BleUnlockManager
8. MainActivity with NFC + BLE integration
9. Testing with real door lock hardware
10. Polish: error handling, loading states, edge cases
