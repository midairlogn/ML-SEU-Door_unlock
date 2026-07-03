# 住理生活 (Zhuli Life) — Door Lock System Full Protocol Reference

> Comprehensive reverse-engineering findings combining static analysis of the original APK (`zhuli.apk` v3.11.5, package `com.whxinna.userplatform`) .

---

## Table of Contents

1. [App Overview](#1-app-overview)
2. [Server Architecture](#2-server-architecture)
3. [Login Mechanism](#3-login-mechanism)
4. [Credential Synchronization](#4-credential-synchronization)
5. [Cryptographic Primitives](#5-cryptographic-primitives)
6. [NFC Door Unlock Protocol](#6-nfc-door-unlock-protocol)
7. [BLE Door Unlock Protocol](#7-ble-door-unlock-protocol)
8. [JavaScript Bridge API (`zl.*`)](#8-javascript-bridge-api-zl)
9. [NFC Tag Format & Android Intent Handling](#9-nfc-tag-format--android-intent-handling)
10. [Security Observations](#10-security-observations)

---

## 1. App Overview

| Field | Value |
|---|---|
| App Name | 住理生活 (Zhuli Life) |
| Package | `com.whxinna.userplatform` |
| Version | 3.11.5 (code 617) |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 |
| Packer | **SecShell** (encrypted DEX in `assets/apps/zhuli.zip`) |
| Real Application Class | `com.whxinna.zhuli.MainApplication` |
| Architecture | Hybrid WebView + DSBridge native bridge |
| Native Bridge | DSBridge (`dsbridge@3.1.3`) exposing `zl.*` namespace |

> **Note**: The APK is protected by SecShell packer. All business logic is encrypted in `assets/apps/zhuli.zip` and decrypted at runtime by `libSecShell.so`. The findings below combine static analysis of resources/manifest/JS bridge with protocol documentation from the SEU-Door re-implementation.

---

## 2. Server Architecture

| Server | URL | Purpose |
|---|---|---|
| Auth Server | `https://pm.whxinna.com` | Login, register, refresh token, OAuth |
| Business Server | `server_info.server_addr` (e.g., `https://zhuli104.whxinna.com`) | Door lock info, credentials, device operations |
| Alipay Callback | `http://nps.whxinna.com:9639/alipay_notify/recharge_notify` | Payment notification |
| Privacy Policy | `https://zhuli.whxinna.com/privacy.html` | Privacy agreement |
| Service Agreement | `https://zhuli.whxinna.com/agreement.html` | Terms of service |

---

## 3. Login Mechanism

### 3.1 Account & Password Login

**Endpoint**:
```
GET https://pm.whxinna.com/webapi/users/login
```

**Parameters**:

| Parameter | Description |
|---|---|
| `phone` | Phone number |
| `pwd` | 6-digit numeric password (plaintext) |
| `code` | Captcha code (required after multiple failed attempts) |
| `pid` | Project ID: `21048` (SEU Jiulonghu Campus) |
| `appid` | App ID: `20104` |
| `timestamp` | Unix timestamp (seconds) |
| `noncestr` | Random 32-character alphanumeric string (auth) or 16-char (business) |
| `sign` | MD5 signature (uppercase, see Appendix A) |

**Response** (base64-decoded `data` field):
```json
{
  "user_info": {
    "id": "UUID",
    "phone": "phone_number",
    "identity_code": "business API identity_code parameter",
    "isbind": 1,
    "balance": "0.0000",
    "group": { "name": "学生", ... }
  },
  "platform_token": "platform_token",
  "server_info": {
    "server_addr": "https://zhuli104.whxinna.com",
    "session_secret": "business API signing key (changes each login)",
    "appsecret": "app secret",
    "server_appid": 21048,
    "server_id": 20104,
    "projectname": "东南大学九龙湖校区"
  }
}
```

**Post-login checks**:
1. If `user_info.is_pwd == 0` → must set password (`/account/set_password`)
2. If `user_info.isbind == false` → must bind project (`/account/bind_project`)
3. If `"授权信息未找到"` or `"openid错误"` → must go through "bind phone" flow (§3.5)

### 3.2 Login Captcha

**Endpoint**:
```
GET https://pm.whxinna.com/webapi/users/get_login_code
```

| Parameter | Description |
|---|---|
| `phone` | Phone number |

**Response** (base64-decoded):

```json
{
  "codeImg": "<svg ...>...</svg>"
}
```

`codeImg` is an **SVG string** containing a 4-character alphanumeric CAPTCHA (case-sensitive). Render as image for user input.

**Trigger**: When login response `message` contains `"本次登录需要进行验证"`, `"CAPTCHA_REQUIRED"`, or `"验证码输入错误"`.

### 3.3 OAuth Login — WeChat

**Endpoint**:
```
GET https://pm.whxinna.com/webapi/oauth/login
```

**AppID/Secret** (hardcoded in APK `strings.xml`):

| Platform | AppID | AppSecret |
|---|---|---|
| WeChat | `wx9622aee7b9ae7536` | `425b4b4ad72c0a317f28bc6b311adfd3` |

**Web OAuth Flow** (no SDK required):
1. Construct authorization URL:
   ```
   https://open.weixin.qq.com/connect/oauth2/authorize
     ?appid=wx9622aee7b9ae7536
     &redirect_uri=<callback_url>
     &response_type=code
     &scope=snsapi_userinfo
     &state=STATE
     #wechat_redirect
   ```
2. User authorizes → callback receives `code`
3. Exchange `code` for `access_token`:
   ```
   GET https://api.weixin.qq.com/sns/oauth2/access_token
     ?appid=wx9622aee7b9ae7536
     &secret=425b4b4ad72c0a317f28bc6b311adfd3
     &code=<code>
     &grant_type=authorization_code
   ```
4. Send `access_token` + `openid` to server `/webapi/oauth/login`

**Native SDK Flow** (in original app):

```javascript
zl.oauth.sendAuth({ provider: "WeChat", info: null }, callback)
// Returns: { access_token, openid, refresh_token }
// oauth_type = "wechat_app" (access_token is passed as auth_code)
```

### 3.4 OAuth Login — Alipay

**AppID**: `2021001155694496` (Alipay PID: `2088901922185401`)

**Web OAuth Flow**:
1. User visits:
   ```
   https://openauth.alipay.com/oauth2/publicAppAuthorize.htm
     ?app_id=2021001155694496
     &scope=auth_user
     &redirect_uri=<whitelisted_callback_url>
   ```
2. Callback returns `auth_code` (user `auth_code`, NOT `app_auth_code`)
3. Send `auth_code` directly to `/webapi/oauth/login` — RSA token exchange happens **server-side**

**Native SDK Flow** (in original app):
1. Fetch SDK parameters:
   ```
   GET https://pm.whxinna.com/webapi/oauth/alipay/auth_info
   ```
2. Launch Alipay app via AIDL:
   ```kotlin
   val intent = Intent("com.eg.android.AlipayGphone.IAlixPay")
   intent.setPackage("com.eg.android.AlipayGphone")
   bindService(intent, connection, Context.BIND_AUTO_CREATE)
   val alixPay = IAlixPay.Stub.asInterface(binder)
   alixPay.registerCallback(callback)
   val result = alixPay.Pay(authInfo)  // authInfo from server
   // result = "resultStatus={9000};result={auth_code=xxx&user_id=xxx}"
   ```
3. Send `auth_code` to server

### 3.5 OAuth Login — CMIC (China Mobile One-Click)

**Endpoint**:
```
POST https://pm.whxinna.com/webapi/oauth/account_login
```

**Flow**:

1. CMIC SDK authenticates via SIM card:
   ```javascript
   zl.cmic.loginAuth({ info: "cmic_phone" }, callback)
   // callback: { code: 0, token: "cmic_token" }
   ```
2. Send token to server (see §3.6 for request format with `oauth_type: "cmic_phone"`)

### 3.6 OAuth Request Format

Both WeChat, Alipay, and CMIC OAuth use the **same request format** — GET with base64-encoded nested parameters:

```
GET /webapi/oauth/login
  ?base64_systemInfo=<base64_url_encode(JSON({"appVersion":"1.0.0","systemType":"android","systemVersion":"14","deviceModel":"Pixel 7","deviceToken":""}))>
  &base64_authInfo=<base64_url_encode(JSON({"auth_code":"xxx","oauth_type":"alipay_app","sign_type":"RSA"}))>
  &app_version=1.0.0
  &timestamp=xxx
  &noncestr=xxx
  &sign=xxx
```

**Base64 encoding**: Standard base64, then `+` → `-`, `/` → `_` (URL-safe)

**oauth_type values**:

| Provider | oauth_type | Endpoint |
|---|---|---|
| WeChat | `wechat_app` | `/webapi/oauth/login` |
| Alipay | `alipay_app` | `/webapi/oauth/login` |
| Huawei | `huawei_account` | `/webapi/oauth/account_login` |
| CMIC | `cmic_phone` | `/webapi/oauth/account_login` |

**Response**: Same as account login (§3.1).

### 3.7 Register

```
GET https://pm.whxinna.com/webapi/users/sendregSMS?phone=xxx    # Send SMS code
POST https://pm.whxinna.com/webapi/users/register                # Register
```

| Parameter | Description |
|---|---|
| `phone` | Phone number |
| `code` | SMS verification code |
| `pwd` | 6-digit numeric password |

### 3.8 Password Reset

```
GET https://pm.whxinna.com/webapi/users/sendresetpwdSMS?phone=xxx   # Send SMS code
POST https://pm.whxinna.com/webapi/oauth/pwd_reset                   # Reset
```

| Parameter | Description |
|---|---|
| `phone` | Phone number |
| `code` | SMS verification code |
| `newpwd` | New 6-digit password |

### 3.9 Bind Phone (Post-OAuth)

If first OAuth login returns `"授权信息未找到"` or `"openid错误"`:

**Step 1**: Request verification code

```
POST https://pm.whxinna.com/webapi/oauth/get_auth_code
```
| Parameter | Description |
|---|---|
| `phone` | Phone number |
| `oauth_type` | `wechat_app` / `alipay_app` |
| `openid` | WeChat only |
| `auth_code` | Alipay only |

**Step 2**: Bind
```
POST https://pm.whxinna.com/webapi/oauth/auth_by_code
```

---

## 4. Credential Synchronization

After login, the client synchronizes door lock credentials:

### 4.1 Get Door Lock Info

```
GET {server_addr}/webapi/v1/student/accommodation/details
```

| Parameter | Description |
|---|---|
| `user_id` | User UUID (from login response `user_info.id`) |
| `identitycode` | `user_info.identity_code` |

**Response** (base64-decoded):
```json
{
  "accommodation": {
    "building_name": "楼栋名",
    "floor_name": "楼层",
    "room_name": "房间号"
  },
  "door_lock": {
    "device_id": 1234567,
    "ble_name": "XN-1234567",
    "ble_mac": "AA:BB:CC:DD:EE:FF",
    "battery_level": 100.0,
    "credential": "64-char hex (chain_key)",
    "credential_id": 1234
  }
}
```

### 4.2 Get Credential List (Fallback)

If `accommodation/details` doesn't return `credential`:

```
GET {server_addr}/webapi/v1/staff/door_lock/credentials
```

| Parameter | Description |
|---|---|
| `device_id` | Door lock device ID |
| `user_id` | User UUID |
| `identitycode` | `identity_code` |

### 4.3 NFC Activation (Server-side, rarely used)

```
GET {server_addr}/webapi/v1/door_lock/command/create
```

| Parameter | Value |
|---|---|
| `device_id` | Door lock device ID |
| `command` | `1` |
| `type` | `nfc` |
| `credential_id` | Credential ID |
| `user_id` | User UUID |
| `identitycode` | `identity_code` |

**Response**:
```json
{
  "credential_id": 1234,
  "command": 1,
  "payload": "B10105XXXXXXXXXXXX"
}
```

> **Note**: The current client does NOT use this endpoint. NFC offline unlock constructs the 40-byte command locally using synced `credential` and sends it directly via `NfcA.transceive()`.

---

## 5. Cryptographic Primitives

All three primitives are shared between NFC and BLE. All 32-bit operations are **unsigned, modulo 2³²**.

### 5.1 Key Derivation: `deriveKey(deviceId)` → 16 bytes

**Constants**:
- `DELTA0 = 0x9E3779B9` (2654435769)
- `DELTA_STEP = 0x12345678` (305419896)
- `KEY_CONST` (16 bytes) = `[172,171,188,218, 174,191,20,38, 53,66,84,101, 114,135,146,1]`

**Algorithm**:
```python
# 1) deviceId split little-endian 4 bytes [b0,b1,b2,b3], then reassemble big-endian
value = (b0<<24 | b1<<16 | b2<<8 | b3) & 0xFFFFFFFF

# 2) KEY_CONST read as 4 little-endian uint32 words
words[0..3] = KEY_CONST每4字节一组、小端解析

# 3) 4 rounds of confusion
for i in 0..3:
    delta  = (DELTA0 + DELTA_STEP * i) & 0xFFFFFFFF
    left   = ((value AND delta) + i) & 0xFFFFFFFF
    middle = ((value OR delta) - 2*i) & 0xFFFFFFFF
    right  = ((0xFFFFFFFF XOR value) XOR delta) & 0xFFFFFFFF
    t      = (left + middle - right) & 0xFFFFFFFF
    words[i] = words[i] XOR t

# 4) Output: 4 words each written big-endian, concatenated → 16 bytes
return BE32(words[0]) ++ BE32(words[1]) ++ BE32(words[2]) ++ BE32(words[3])
```

### 5.2 RC4

Standard RC4 (no drop-n). Key = `deriveKey(deviceId)` (16 bytes). KSA uses `key[i % 16]`, PRGA XORs byte-by-byte. Encryption and decryption are the same function.

### 5.3 CRC8

Lookup table method, initial value `0x00`, no final XOR:
```python
crc = 0
for b in data:
    crc = CRC8_TABLE[crc XOR b]
return crc & 0xFF
```

**CRC8 Table** (256 entries, MSB-first, fingerprint `TABLE[1]=0x5E`):
```
0, 94, 188, 226, 97, 63, 221, 131, 194, 156, 126, 32, 163, 253, 31, 65,
157, 195, 33, 127, 252, 162, 64, 30, 95, 1, 227, 189, 62, 96, 130, 220,
35, 125, 159, 193, 66, 28, 254, 160, 225, 191, 93, 3, 128, 222, 60, 98,
190, 224, 2, 92, 223, 129, 99, 61, 124, 34, 192, 158, 29, 67, 161, 255,
70, 24, 250, 164, 39, 121, 155, 197, 132, 218, 56, 102, 229, 187, 89, 7,
219, 133, 103, 57, 186, 228, 6, 88, 25, 71, 165, 251, 120, 38, 196, 154,
101, 59, 217, 135, 4, 90, 184, 230, 167, 249, 27, 69, 198, 152, 122, 36,
248, 166, 68, 26, 153, 199, 37, 123, 58, 100, 134, 216, 91, 5, 231, 185,
140, 210, 48, 110, 237, 179, 81, 15, 78, 16, 242, 172, 47, 113, 147, 205,
17, 79, 173, 243, 112, 46, 204, 146, 211, 141, 111, 49, 178, 236, 14, 80,
175, 241, 19, 77, 206, 144, 114, 44, 109, 51, 209, 143, 12, 82, 176, 238,
50, 108, 142, 208, 83, 13, 239, 177, 240, 174, 76, 18, 145, 207, 45, 115,
202, 148, 118, 40, 171, 245, 23, 73, 8, 86, 180, 234, 105, 55, 213, 139,
87, 9, 235, 181, 54, 104, 138, 212, 149, 203, 41, 119, 244, 170, 72, 22,
233, 183, 85, 11, 136, 214, 52, 106, 43, 117, 151, 201, 74, 20, 246, 168,
116, 42, 200, 150, 21, 75, 169, 247, 182, 232, 10, 84, 215, 137, 107, 53
```

---

## 6. NFC Door Unlock Protocol

### 6.1 NFC Chip Info

- **Chip**: FM11NT021-type (online NFC frontend, 240-byte user area)
- **CC**: `E1101E00`
- **SAK**: `0x00` / **ATQA**: `0x0044`
- **Memory**: Shared with lock MCU via I2C

### 6.2 NFC Tag Reading

Standard `0x30` READ command reads NDEF from user memory (page 4 onwards). Parse TLV structure to extract NDEF message, then extract URL's `?d=` parameter to get `device_id`.

### 6.3 NFC Command Frame (40 bytes)

```
[0]     = 0xB1 (frame header)
[1]     = 0x0D (subcommand)
[2]     = 0x24 (36 = encrypted data length)
[3-38]  = RC4(projectId[4_LE] ++ credential[32])
[39]    = CRC8(0x0D, 0x24, projectId_LE ++ credential)
```

**Building the command**:
```kotlin
fun buildNfcCommand(deviceId: Int, credentialHex: String, projectId: Int): ByteArray {
    val credential = credentialHex.hexToBytes()                   // 32 bytes
    val pidBytes = littleEndianInt(projectId)                      // 4 bytes LE
    val plainData = pidBytes + credential                          // 36 bytes
    val encrypted = rc4Encrypt(plainData, deriveKey(deviceId))     // 36 bytes
    val command = ByteArray(40)
    command[0] = 0xB1
    command[1] = 0x0D
    command[2] = encrypted.size.toByte()                           // 0x24 = 36
    System.arraycopy(encrypted, 0, command, 3, encrypted.size)
    command[39] = crc8(byteArrayOf(0x0D, 0x24) + plainData)
    return command
}
```

### 6.4 NFC Communication Method

- **Use `enableReaderMode(FLAG_READER_NFC_A)`** — NOT `enableForegroundDispatch`
- Send entire 40-byte frame via `NfcA.transceive()` in **one shot**
- Lock returns **20-byte response** directly in the transceive return value
- **Do NOT** use `0xA2` WRITE (page write) — the chip doesn't respond to it
- Timeout: 1500ms, retries: 3

### 6.5 NFC Response (20 bytes)

```
[0]     = 0xB1 (frame header)
[1]     = commandId
[2]     = payload length
[3..]   = RC4 encrypted payload
[last]  = CRC8
```

**Parsing**:
```kotlin
fun parseResponse(deviceId: Int, frame: ByteArray): DoorResponse {
    val commandId = frame[1] & 0xFF
    val payloadSize = frame[2] & 0xFF
    val encryptedPayload = frame[3..(3+payloadSize)]
    val plainPayload = rc4Encrypt(encryptedPayload, deriveKey(deviceId))
    val resultCode = plainPayload[3] & 0xFF    // result code at offset 3
    val crcValid = (frame[3+payloadSize] == crc8(byteArrayOf(frame[1], frame[2]) + plainPayload))
    return DoorResponse(commandId, payloadSize, resultCode, crcValid, ...)
}
```

### 6.6 Result Codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | CRC check error |
| 2 | ISN random error |
| 3 | Busy |
| 7 | No key set |
| 12 | User deleted |
| 13 | Random verification failed |
| 14 | Project ID mismatch |
| 20 | User info not found |
| 21 | Key type mismatch |
| 22 | Admin random mismatch |
| 23 | Door already open (also success) |
| 24 | Expired |
| 25 | Offline count exhausted |
| 26 | Credential update failed |
| 27 | Need to update key (triggers refresh) |
| 255 | Unknown command |

### 6.7 NFC Credential Characteristics

- **Static**: Response is only 20 bytes, cannot carry a 32-byte chainKey → credentials don't roll
- **Deterministic**: Same `credential` always produces the same 40-byte command (no nonce)
- **Expiration**: Server-side offline credential expiry → client detects code `24`/`27`, triggers re-sync via `syncCredential`

---

## 7. BLE Door Unlock Protocol

### 7.1 BLE Device Info

| Item | Value |
|---|---|
| Device name pattern | `XN-{device_id}` |
| Service UUID | `0000ff12-0000-1000-8000-00805f9b34fb` |
| Write UUID | `0000ff01-0000-1000-8000-00805f9b34fb` |
| Read/Notify UUID | `0000ff02-0000-1000-8000-00805f9b34fb` |
| CCCD UUID | `00002902-0000-1000-8000-00805f9b34fb` |

### 7.2 BLE Command Frame (20 bytes)

```
[0]     = 0x14 (length marker = 20)
[1]     = 0x00 (reserved)
[2]     = command type (0x74/0x75/0x76/0x77/0x78)
[3-18]  = RC4 encrypted 16-byte data
[19]    = CRC8 (calculated on plaintext)
```

**Building**:
```kotlin
fun buildBleCommand(deviceId: Int, commandType: Int, data: ByteArray): ByteArray {
    val plainData = ByteArray(16)   // zero-padded to 16 bytes
    System.arraycopy(data, 0, plainData, 0, data.size)
    val encrypted = rc4Encrypt(plainData, deriveKey(deviceId))
    return ByteArray(20).apply {
        this[0] = 0x14
        this[1] = 0x00
        this[2] = commandType.toByte()
        System.arraycopy(encrypted, 0, this, 3, 16)
        this[19] = crc8(plainData)
    }
}
```

### 7.3 BLE Command Types

| Command | Hex | Direction | Purpose |
|---|---|---|---|
| Credential Header | `0x74` | App → Lock | Send header, get random number |
| Credential Packet | `0x75` | App → Lock | Send credential in 3 packets |
| Request Refetch | `0x76` | App → Lock | Request new credential from lock |
| Read Packet | `0x77` | App → Lock | Read credential packet from lock |
| Open Door | `0x78` | App → Lock | Execute unlock |

### 7.4 BLE Open Door Flow

```
1. Connect BLE (prefer cached ble_mac for direct connection)
2. Send 0x74 credential header → parse ran (random number)
3. Send 0x75 × 3 (ran + projectId + credential split into 3 packets)
4. Wait 50ms
5. Send 0x78 open door → parse result code
```

**Step-by-step**:

1. **0x74 Credential Header**:
   - Plaintext: `[0]=0x28(=40), [1]=0x00, [2]=0x03, [3]=CRC8(projectId_LE ++ credential), [4..15]=0`
   - Response: `plainData[0]` = result code (must be 0); `ran = plainData[4..7]` (little-endian)

2. **0x75 Credential Packets ×3**:
   - Payload = `ran_LE(4) ++ projectId_LE(4) ++ credential(32)` = 40 bytes
   - Split into 3 chunks of 15 bytes each
   - Each packet plaintext: `[0]=packet_index(0/1/2), [1..15]=chunk_data`
   - Inter-packet delay: 10ms

3. **0x78 Open Door**:
   - Plaintext: 16 bytes all zeros (credential already registered)
   - Response: `plainData[0]` = result code

### 7.5 BLE Credential Refresh (Result Code 27)

```
1. Send 0x76 with credential_id (4 bytes LE, rest zeros)
2. Parse response: [1]=packet_count, [2..3]=credential_total_length, [4]=CRC8
3. Send 0x77 × packet_count to read each packet
4. Merge packets, verify CRC8, validate 64-char hex format
5. Use new credential to retry 0x78 open door
```

### 7.6 BLE Response Parsing

```kotlin
fun parseBleResponse(deviceId: Int, frame: ByteArray): BleResponse {
    require(frame.size == 20)
    require(frame[1] == 0x00)  // reserved byte
    val commandType = frame[2] & 0xFF
    val encryptedData = frame[3..18]
    val plainData = rc4Encrypt(encryptedData, deriveKey(deviceId))
    val receivedCrc = frame[19]
    val calculatedCrc = crc8(plainData)
    return BleResponse(commandType, plainData, receivedCrc == calculatedCrc)
}
```

---

## 8. JavaScript Bridge API (`zl.*`)

The app uses DSBridge to expose native capabilities to WebView pages. The JS API is in `assets/native/native.min.js`.

### 8.1 Full Namespace List

| Namespace | Native API | Methods |
|---|---|---|
| `zl.nfc` | `__native_nfc__` | `status()`, `init()`, `write({data, timeout})`, `release()`, `cancelWrite()`, `disconnect()` |
| `zl.bluetooth` | `__native_bluetooth__` | `init({services_uuid, read_uuid, write_uuid})`, `startScan()`, `stopScan()`, `connect(deviceName)`, `write(data)`, `disconnect()`, `close()` |
| `zl.oauth` | `__native_oauth__` | `sendAuth({type, info}, callback)` |
| `zl.cmic` | `__native_cmic__` | `loginAuth({info}, callback)`, `getPhoneInfo()` |
| `zl.face` | `__native_face__` | `init(config, callback)` |
| `zl.biometric` | `__native_biometric__` | `start(config, callback)` |
| `zl.scan` | `__native_scan__` | `init(callback)` |
| `zl.payment` | `__native_payment__` | `pay(type, orderInfo, callback)` |
| `zl.wechat` | `__native_wechat__` | `launchMiniProgram(id, params, callback)`, `sendMsg()`, `OpenBusinessView()` |
| `zl.system` | `__native_system__` | `getDeviceInfo()`, `getStatusBarHeight()`, `getNavigationBarHeight()`, `callPhone()`, `launchApplication()`, `getSignature()`, `launchWeb()`, `getLaunchArgs()`, `checkPermissions()`, `requestPermissions()` |
| `zl.storage` | `__native_user_cache__` | `set(key, value, encrypt)`, `get(key)` |
| `zl.http` | `__native_http__` | `request(config)`, `clearQueue()` |
| `zl.location` | `__native_location__` | `getLocation()`, `getAddress()` |
| `zl.navigation` | `__native_navigation__` | `navigateTo()`, `redirectTo()`, `reLaunch()`, `navigateBack()`, `getCurrentPages()`, `closeSplash()`, `sendToWebView()`, `evaluateJsToWebView()`, `closeWebView()` |
| `zl.dialog` | `__native_dialog__` | `loading()`, `alert()`, `confirm()`, `prompt()`, `close()`, `toast()` |
| `zl.privacy` | `__native_privacy__` | `isAgreePrivacy()`, `setAgreePrivacy()` |
| `zl.upgrade` | `__native_upgrade__` | `showDialog()` |
| `zl.ad` | `__native_ad__` | `setBeiZis()`, `setCJSdk()`, `setSplashAd()`, `loadNative()`, `closeNative()`, `loadInterstitial()`, etc. |
| `zl.logger` | `__native_logger__` | (no exposed actions) |

### 8.2 BLE Events

| Event | Description |
|---|---|
| `onDevicesDiscovered` | BLE device found during scan |
| `onConnected` | Connection established |
| `onConnectTimeouted` | Connection timed out |
| `onServicesDiscovered` | GATT services discovered |
| `onDisconnected` | Connection lost |
| `onCharacteristicChanged` | Data notification received |
| `onCharacteristicRead` | Read response |
| `onCharacteristicWrite` | Write confirmation |
| `onScanError` | Scan error |

### 8.3 NFC Events

| Event | Description |
|---|---|
| `onTagDiscovered` | NFC tag detected |
| `onTagReaderFail` | Tag reading failed |

### 8.4 System Events

| Event | Description |
|---|---|
| `onBackPress` | Back button pressed |
| `onAppShow` | App brought to foreground |
| `onAppNotice` | Push notification received |
| `onAppNfcTag` | NFC tag detected while app in foreground |

### 8.5 BLE Usage Example (from `testPage.html`)

```javascript
// Initialize BLE with door lock GATT UUIDs
const initResult = zl.bluetooth.init({
    services_uuid: "0000ff12-0000-1000-8000-00805f9b34fb",
    read_uuid:      "0000ff02-0000-1000-8000-00805f9b34fb",
    write_uuid:     "0000ff01-0000-1000-8000-00805f9b34fb"
});

// Register event handlers
zl.bluetooth.onScanError((code, msg) => { /* ... */ });
zl.bluetooth.onConnected((deviceName, deviceAddress) => { /* ... */ });
zl.bluetooth.onServicesDiscovered(() => { /* ... */ });
zl.bluetooth.onDisconnected((deviceName, deviceAddress) => { /* ... */ });
zl.bluetooth.onCharacteristicChanged((uuid, data) => { /* process data */ });
zl.bluetooth.onCharacteristicRead((uuid, data) => { /* ... */ });
zl.bluetooth.onCharacteristicWrite((uuid, data) => { /* ... */ });
zl.bluetooth.onDevicesDiscovered((deviceName, deviceAddress) => {
    if (`XN-1136247` == deviceName) {
        zl.bluetooth.stopScan();
        zl.bluetooth.connect(deviceName);
    }
});

// Start scanning
const startScanResult = zl.bluetooth.startScan();
```

### 8.6 NFC Usage Example (from `nfc_test.html`)

```javascript
// Check status
const statusResult = dsBridge.call("__native_nfc__.status", {});
const statusData = JSON.parse(statusResult);
// statusData.data = { supported, enabled, initialized, present, idleTime, timeout }

// Initialize
const initResult = dsBridge.call("__native_nfc__.init", {});

// Write data (default: "https://uc-zhuli.whxinna.com?d=259289")
dsBridge.call("__native_nfc__.write", {
    data: "https://uc-zhuli.whxinna.com?d=259289",
    timeout: 15000
}, function(result) {
    const res = JSON.parse(result);
    // res.data.status: "waiting" → "detected" → "connected" → "writing" → "success"
});

// Register tag discovery event
dsBridge.register("__native_nfc__.onTagDiscovered", function(tagInfo) {
    const info = JSON.parse(tagInfo);
    // info = { id, techs, atqa, sak, records[] }
});

// Release resources
dsBridge.call("__native_nfc__.release", {});
```

---

## 9. NFC Tag Format & Android Intent Handling

### 9.1 Tag Data Format

NFC tags contain an NDEF URL record:
```
https://uc-zhuli.whxinna.com?d={device_id}
```

- `d` parameter = door lock device ID (integer)
- Also supports `device_id` as alternative parameter name

### 9.2 AndroidManifest NFC Configuration

```xml

<uses-feature android:name="android.hardware.nfc" android:required="true" /><uses-permission
android:name="android.permission.NFC" />

<activity android:name="com.midairlogn.nfc.NfcPendingActivity" android:launchMode="singleInstance"
android:theme="@style/ZhuLiLifeTheme.Transparent">
<!-- NDEF Discovery (URL-based) -->
<intent-filter>
    <action android:name="android.nfc.action.NDEF_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:host="uc-zhuli.whxinna.com" android:scheme="https" />
</intent-filter>
<!-- TECH Discovery -->
<intent-filter>
    <action android:name="android.nfc.action.TECH_DISCOVERED" />
</intent-filter>
<meta-data android:name="android.nfc.action.TECH_DISCOVERED"
    android:resource="@xml/nfc_tech_filter" />
<!-- TAG Discovery (fallback) -->
<intent-filter>
    <action android:name="android.nfc.action.TAG_DISCOVERED" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
</activity>
```

### 9.3 Supported NFC Tech Types

From `nfc_tech_filter.xml`:
- `android.nfc.tech.IsoDep`
- `android.nfc.tech.NfcA`
- `android.nfc.tech.NfcB`
- `android.nfc.tech.NfcF`
- `android.nfc.tech.NfcV`
- `android.nfc.tech.Ndef`
- `android.nfc.tech.NdefFormatable`
- `android.nfc.tech.MifareClassic`
- `android.nfc.tech.MifareUltralight`

### 9.4 NFC Wake-up (App Not in Foreground)

The original NFC tag embeds an **AAR** (Android Application Record) for `com.whxinna.userplatform`. The SEU-Door re-implementation sets its `applicationId` to `com.whxinna.userplatform` so the AAR routes directly to it. When the app is not running, Android launches it and delivers the NFC tag intent to `MainActivity`.

---

## 10. Security Observations

1. **Plaintext WeChat AppSecret** in `strings.xml`: `425b4b4ad72c0a317f28bc6b311adfd3` — should be server-side only
2. **Plaintext password login**: 6-digit numeric password sent as `pwd` parameter (GET request, logged in server access logs)
3. **Auth signing key hardcoded**: `6d5dbb85b949447a95ff8fda9a9b759b` — shared across all clients
4. **NFC credentials are static**: Don't roll, only expire server-side; any eavesdropper on NFC traffic can replay
5. **BLE credentials roll but via insecure channel**: The 0x76/0x77 refresh happens over BLE without additional encryption beyond RC4(device-derived key)
6. **Local storage**: Original app uses custom encryption (`R9Tdbuh5KCXNa09fL4hGaVY5wh6WRMSW` key, `ETD#` prefix); SEU-Door uses Android Keystore + AES-GCM
7. **SecShell packer**: Hides business logic but doesn't protect against runtime analysis (Frida/Xposed)

---

## Appendix A: Request Signing Algorithm

Every API request includes a `sign` query parameter computed as follows:

### Auth Server Requests

```
sign = MD5(
  sorted(key1=val1&key2=val2&...&keyN=valN) + "&key=" + AUTH_SECRET
).toUpperCase()
```

Where:
- Parameters are sorted alphabetically by key
- `AUTH_SECRET` = `6d5dbb85b949447a95ff8fda9a9b759b` (hardcoded, shared across all clients)
- Nonce length: 32 characters

### Business Server Requests

```
sign = MD5(
  sorted(key1=val1&key2=val2&...&keyN=valN) + "&key=" + sessionSecret
).toUpperCase()
```

Where:
- `sessionSecret` is obtained from the login response (`server_info.session_secret`)
- Nonce length: 16 characters

### Example

Given params `appid=20104&noncestr=abc123&phone=13800138000&pid=21048&pwd=123456&timestamp=1700000000`:

```
signSource = "appid=20104&noncestr=abc123&phone=13800138000&pid=21048&pwd=123456&timestamp=1700000000&key=6d5dbb85b949447a95ff8fda9a9b759b"
sign = MD5(signSource).toUpperCase()
```

---

## Appendix B: Local Storage Fields

After synchronization, the client stores:

| Field | Description |
|---|---|
| `serverUrl` | Business server URL |
| `authServerUrl` | Auth server URL (`https://pm.whxinna.com`) |
| `phone` | Phone number |
| `password` | Password (for re-sync) |
| `userId` | User UUID |
| `identityCode` | Identity code |
| `deviceId` | Door lock device ID (integer) |
| `credentialId` | Credential ID |
| `bleMac` | BLE MAC address |
| `credentialHex` | 64-char uppercase hex credential |
| `sessionSecret` | Business API signing key |
| `updatedAt` | Last sync timestamp |
