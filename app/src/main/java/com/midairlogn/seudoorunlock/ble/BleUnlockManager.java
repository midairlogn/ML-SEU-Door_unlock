package com.midairlogn.seudoorunlock.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.NonNull;

import com.midairlogn.seudoorunlock.AppExecutors;
import com.midairlogn.seudoorunlock.api.ApiClient;
import com.midairlogn.seudoorunlock.api.CredentialApi;
import com.midairlogn.seudoorunlock.model.BleResponse;
import com.midairlogn.seudoorunlock.model.NfcActivationStep;
import com.midairlogn.seudoorunlock.nfc.NfcCommandBuilder;
import com.midairlogn.seudoorunlock.storage.CredentialCache;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class BleUnlockManager {

    private static final String TAG = "ZL_BleManager";

    private static final UUID SERVICE_UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    private static final UUID READ_UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int OPERATION_TIMEOUT_MS = 10000;
    private static final int SCAN_TIMEOUT_MS = 3000;
    private static final int CONNECT_RETRY_COUNT = 3;
    private static final int RETRY_DELAY_MS = 800;
    private static final int INTER_PACKET_DELAY_MS = 10;
    private static final int PRE_OPEN_DELAY_MS = 50;
    private static final int MAX_ACTIVATION_ROUNDS = 8;

    public interface BleCallback {
        void onSuccess(String message);
        void onError(String message);
        void onExpired();
    }

    private final Context context;
    private final CredentialCache cache;
    private final CredentialApi credentialApi;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final Handler timeoutHandler;

    private BluetoothAdapter bluetoothAdapter;
    private volatile BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic readCharacteristic;
    private BleCallback pendingCallback;
    private BluetoothDevice targetDevice;
    private int connectAttempt;
    private int activeDeviceId;
    private boolean unlockFlowStarted;
    private boolean operationFinished;
    private boolean scanFallbackOnConnectFailure;

    private boolean waitingForNotification = false;
    private byte[] lastNotificationData;
    private boolean waitingForWrite = false;
    private int lastWriteStatus = BluetoothGatt.GATT_FAILURE;
    private boolean waitingForDescriptor = false;

    public BleUnlockManager(Context context, CredentialCache cache) {
        this.context = context;
        this.cache = cache;
        this.credentialApi = new CredentialApi(cache);
        this.executor = AppExecutors.getInstance();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.timeoutHandler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    public boolean isBleSupported() {
        return bluetoothAdapter != null;
    }

    public boolean isBleEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public void unlock(BleCallback callback) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            callback.onError("Bluetooth not available or not enabled");
            return;
        }

        this.pendingCallback = callback;
        timeoutHandler.removeCallbacksAndMessages(null);
        clearActivationState();
        unlockFlowStarted = false;
        operationFinished = false;
        scanFallbackOnConnectFailure = false;

        int deviceId = cache.getDeviceId();
        if (deviceId == 0) {
            callback.onError("No door lock credentials cached");
            return;
        }

        if (cache.requiresDigitalCredentialActivation()) {
            activateDigitalCredential();
            return;
        }

        String credentialHex = cache.getCredentialHex();
        if (credentialHex.isEmpty()) {
            callback.onError("No door lock credentials cached");
            return;
        }

        // Strategy: cached MAC direct → scan fallback
        String cachedMac = cache.getBleMac();
        if (!cachedMac.isEmpty()) {
            Log.d(TAG, "Attempting direct connect to cached MAC: " + cachedMac);
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(cachedMac);
                if (device != null) {
                    connectToDevice(device, deviceId, true);
                    return;
                }
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Cached BLE MAC is invalid, falling back to scan", e);
            }
        }

        // Fallback: scan for device
        Log.d(TAG, "Cached MAC failed or empty, starting scan");
        startScanAndConnect();
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device, int deviceId) {
        connectToDevice(device, deviceId, false);
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device, int deviceId, boolean allowScanFallback) {
        targetDevice = device;
        activeDeviceId = deviceId;
        connectAttempt = 0;
        unlockFlowStarted = false;
        operationFinished = false;
        scanFallbackOnConnectFailure = allowScanFallback;
        connectNextAttempt();
    }

    @SuppressLint("MissingPermission")
    private void connectNextAttempt() {
        cleanupGattOnly();
        connectAttempt++;
        String address = safeDeviceAddress(targetDevice);
        Log.d(TAG, "Connecting to " + (address.isEmpty() ? "unknown" : address) + " attempt " + connectAttempt);
        scheduleTimeout(CONNECT_TIMEOUT_MS, "Connection timed out");
        try {
            bluetoothGatt = targetDevice.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (bluetoothGatt == null) {
                throw new IllegalStateException("connectGatt returned null");
            }
        } catch (RuntimeException e) {
            timeoutHandler.removeCallbacksAndMessages(null);
            retryOrFail("BLE connection start failed: " + safeMessage(e));
        }
    }

    private void retryOrFail(String message) {
        if (operationFinished) return;
        timeoutHandler.removeCallbacksAndMessages(null);
        if (targetDevice != null && connectAttempt < CONNECT_RETRY_COUNT) {
            Log.w(TAG, message + ", retrying BLE connection");
            timeoutHandler.postDelayed(this::connectNextAttempt, RETRY_DELAY_MS);
        } else if (scanFallbackOnConnectFailure) {
            Log.w(TAG, message + ", falling back to BLE scan");
            scanFallbackOnConnectFailure = false;
            targetDevice = null;
            connectAttempt = 0;
            startScanAndConnect();
        } else {
            fail(message);
        }
    }

    @SuppressLint("MissingPermission")
    private void startScanAndConnect() {
        int cachedDeviceId = cache.getDeviceId();
        String targetName = "XN-" + cachedDeviceId;
        String targetAddress = cache.getBleMac().toUpperCase().replace(":", "").replace("-", "");
        Log.d(TAG, "Scanning for: " + targetName + " addr=" + targetAddress);

        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            fail("Bluetooth scanner not available");
            return;
        }

        final BluetoothDevice[] fallbackDevice = new BluetoothDevice[1];
        final int[] fallbackDeviceId = new int[]{cachedDeviceId};
        final int[] fallbackRssi = new int[]{Integer.MIN_VALUE};
        final AtomicBoolean matched = new AtomicBoolean(false);

        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                ScanRecord scanRecord = result.getScanRecord();
                String deviceName = scanRecord != null && scanRecord.getDeviceName() != null
                    ? scanRecord.getDeviceName()
                    : safeDeviceName(device);
                String deviceAddress = safeDeviceAddress(device);
                String normalizedAddr = deviceAddress.toUpperCase().replace(":", "").replace("-", "");
                int advertisedDeviceId = parseDeviceId(deviceName);

                boolean nameMatch = matchesTargetName(deviceName, targetName);
                boolean addrMatch = !targetAddress.isEmpty() && normalizedAddr.equals(targetAddress);
                boolean deviceIdMatch = advertisedDeviceId != 0 && advertisedDeviceId == cachedDeviceId;
                boolean serviceMatch = hasDoorService(scanRecord);

                if (serviceMatch && result.getRssi() > fallbackRssi[0]) {
                    fallbackDevice[0] = device;
                    fallbackDeviceId[0] = advertisedDeviceId != 0 ? advertisedDeviceId : cachedDeviceId;
                    fallbackRssi[0] = result.getRssi();
                }

                if ((nameMatch || addrMatch || deviceIdMatch) && matched.compareAndSet(false, true)) {
                    stopScanQuietly(scanner, this);
                    Log.d(TAG, "Found device: " + (deviceName != null ? deviceName : deviceAddress)
                        + " match=" + (nameMatch ? "name" : addrMatch ? "address" : "deviceId"));
                    int resolvedDeviceId = advertisedDeviceId != 0 ? advertisedDeviceId : cachedDeviceId;
                    mainHandler.post(() -> connectToDevice(device, resolvedDeviceId));
                }
            }
        };

        try {
            scanner.startScan(scanCallback);
        } catch (RuntimeException e) {
            fail("Failed to start BLE scan: " + safeMessage(e));
            return;
        }

        timeoutHandler.postDelayed(() -> {
            stopScanQuietly(scanner, scanCallback);
            if (bluetoothGatt == null && pendingCallback != null && matched.compareAndSet(false, true)) {
                if (fallbackDevice[0] != null) {
                    Log.d(TAG, "Using BLE service UUID fallback: " + safeDeviceAddress(fallbackDevice[0]));
                    connectToDevice(fallbackDevice[0], fallbackDeviceId[0]);
                } else {
                    fail("Device not found nearby");
                }
            }
        }, SCAN_TIMEOUT_MS);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (isStaleGatt(gatt)) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected, discovering services");
                timeoutHandler.removeCallbacksAndMessages(null);
                try {
                    if (!gatt.discoverServices()) {
                        cleanupGattOnly();
                        retryOrFail("Failed to start service discovery");
                    } else {
                        scheduleTimeout(CONNECT_TIMEOUT_MS, "Service discovery timed out");
                    }
                } catch (RuntimeException e) {
                    cleanupGattOnly();
                    retryOrFail("Service discovery failed: " + safeMessage(e));
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected");
                cleanupGattOnly();
                if (operationFinished) {
                    return;
                }
                if (!unlockFlowStarted) {
                    retryOrFail("Disconnected");
                } else {
                    fail("Disconnected");
                }
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (isStaleGatt(gatt)) return;
            timeoutHandler.removeCallbacksAndMessages(null);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: " + status);
                cleanupGattOnly();
                retryOrFail("Service discovery failed");
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "Door lock service not found");
                cleanupGattOnly();
                retryOrFail("Door lock service not found");
                return;
            }

            writeCharacteristic = service.getCharacteristic(WRITE_UUID);
            readCharacteristic = service.getCharacteristic(READ_UUID);

            if (writeCharacteristic == null || readCharacteristic == null) {
                cleanupGattOnly();
                retryOrFail("Characteristics not found");
                return;
            }

            // Enable notifications or indications on read characteristic.
            BluetoothGattDescriptor descriptor;
            try {
                if (!gatt.setCharacteristicNotification(readCharacteristic, true)) {
                    cleanupGattOnly();
                    retryOrFail("Failed to enable notifications");
                    return;
                }
                descriptor = readCharacteristic.getDescriptor(CCCD_UUID);
            } catch (RuntimeException e) {
                cleanupGattOnly();
                retryOrFail("Failed to enable notifications: " + safeMessage(e));
                return;
            }
            if (descriptor == null) {
                cleanupGattOnly();
                retryOrFail("Notification descriptor not found");
                return;
            }

            int properties = readCharacteristic.getProperties();
            byte[] cccValue;
            if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                cccValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            } else if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                cccValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            } else {
                cleanupGattOnly();
                retryOrFail("Response characteristic does not support notifications");
                return;
            }

            waitingForDescriptor = true;
            boolean writeStarted;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    writeStarted = gatt.writeDescriptor(descriptor,
                        cccValue) == BluetoothStatusCodes.SUCCESS;
                } else {
                    descriptor.setValue(cccValue);
                    writeStarted = gatt.writeDescriptor(descriptor);
                }
            } catch (RuntimeException e) {
                waitingForDescriptor = false;
                cleanupGattOnly();
                retryOrFail("Failed to enable notifications: " + safeMessage(e));
                return;
            }
            if (!writeStarted) {
                waitingForDescriptor = false;
                cleanupGattOnly();
                retryOrFail("Failed to enable notifications");
                return;
            }

            scheduleTimeout(OPERATION_TIMEOUT_MS, "Notification setup timed out");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (isStaleGatt(gatt)) return;
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;
            waitingForDescriptor = false;
            timeoutHandler.removeCallbacksAndMessages(null);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                cleanupGattOnly();
                retryOrFail("Notification setup failed: " + status);
                return;
            }
            Log.d(TAG, "Notifications enabled, starting unlock flow");
            mainHandler.post(() -> startUnlockFlow());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (isStaleGatt(gatt)) return;
            if (WRITE_UUID.equals(characteristic.getUuid())) {
                synchronized (BleUnlockManager.this) {
                    lastWriteStatus = status;
                    waitingForWrite = false;
                    BleUnlockManager.this.notifyAll();
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (isStaleGatt(gatt)) return;
            handleCharacteristicChanged(characteristic.getUuid(), characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            if (isStaleGatt(gatt)) return;
            handleCharacteristicChanged(characteristic.getUuid(), value);
        }

        private void handleCharacteristicChanged(UUID uuid, byte[] value) {
            if (uuid.equals(READ_UUID)) {
                synchronized (BleUnlockManager.this) {
                    lastNotificationData = value != null ? value.clone() : null;
                    waitingForNotification = false;
                    BleUnlockManager.this.notifyAll();
                }
                Log.d(TAG, "Notification received, len=" + (lastNotificationData != null ? lastNotificationData.length : 0));
            }
        }
    };

    private void startUnlockFlow() {
        executor.execute(() -> {
            try {
                unlockFlowStarted = true;

                if (isActivationFlow && pendingActivationStep != null) {
                    runBleActivationLoop();
                    return;
                }

                int deviceId = activeDeviceId != 0 ? activeDeviceId : cache.getDeviceId();
                String credentialHex = cache.getCredentialHex();
                int projectId = getEffectiveProjectId();

                // Step 1: Send 0x74 credential header
                byte[] headerCmd = BleCommandBuilder.buildCredentialHeaderForDevice(deviceId, projectId, credentialHex);
                byte[] headerResp = sendAndWaitForNotification(headerCmd);
                if (headerResp == null) {
                    fail("No response to credential header");
                    return;
                }

                BleResponse headerResponse = BleCommandBuilder.parseResponse(deviceId, headerResp);
                if (!isValidResponse(headerResponse, BleCommandBuilder.CMD_CREDENTIAL_HEADER, "Credential header")) {
                    return;
                }
                if (headerResponse.getResultCode() != 0) {
                    fail("Credential header rejected: " + headerResponse.getResultCode());
                    return;
                }

                int ran = headerResponse.getRandom();
                Log.d(TAG, "Got random: " + ran);

                // Step 2: Send 0x75 credential packets
                byte[][] packets = BleCommandBuilder.buildCredentialPackets(deviceId, ran, projectId, credentialHex);
                for (int i = 0; i < packets.length; i++) {
                    byte[] packetResp = sendAndWaitForNotification(packets[i]);
                    if (packetResp == null) {
                        fail("No response to credential packet " + (i + 1));
                        return;
                    }
                    BleResponse packetResponse = BleCommandBuilder.parseResponse(deviceId, packetResp);
                    if (!isValidResponse(packetResponse, BleCommandBuilder.CMD_CREDENTIAL_PACKET,
                        "Credential packet " + (i + 1))) {
                        return;
                    }
                    // 0x75 responses acknowledge the packet frame; plainData[0] is not a result code
                    // and may echo the packet index (packet 2 can report 1).
                    Log.d(TAG, "Credential packet " + (i + 1) + " ack=" + packetResponse.getResultCode());
                    Thread.sleep(INTER_PACKET_DELAY_MS);
                }

                // Step 3: Wait before open door
                Thread.sleep(PRE_OPEN_DELAY_MS);

                // Step 4: Send 0x78 open door
                byte[] openCmd = BleCommandBuilder.buildOpenDoor(deviceId);
                byte[] openResp = sendAndWaitForNotification(openCmd);
                if (openResp == null) {
                    fail("No response to open door command");
                    return;
                }

                BleResponse openResponse = BleCommandBuilder.parseResponse(deviceId, openResp);
                if (!isValidResponse(openResponse, BleCommandBuilder.CMD_OPEN_DOOR, "Open door")) {
                    return;
                }
                if (openResponse.isSuccess()) {
                    success("Door opened successfully");
                } else if (openResponse.getResultCode() == 27) {
                    // Credential expired, try refetch
                    Log.d(TAG, "Credential expired, attempting refetch");
                    handleCredentialRefetch(deviceId);
                } else {
                    fail("Open door failed: " + openResponse.getErrorMessage());
                }

            } catch (Exception e) {
                Log.e(TAG, "Unlock flow error", e);
                fail("Unlock error: " + e.getMessage());
            }
        });
    }

    private void handleCredentialRefetch(int deviceId) {
        try {
            byte[] refetchCmd = BleCommandBuilder.buildCredentialRefetch(deviceId, deviceId);
            byte[] refetchResp = sendAndWaitForNotification(refetchCmd);

            if (refetchResp == null) {
                fail("No response to credential refetch");
                return;
            }

            BleResponse refetchResponse = BleCommandBuilder.parseResponse(deviceId, refetchResp);
            if (!isValidResponse(refetchResponse, BleCommandBuilder.CMD_CREDENTIAL_REFETCH, "Credential refetch")) {
                return;
            }
            if (!refetchResponse.isSuccess()) {
                fail("Credential refetch rejected: " + refetchResponse.getResultCode());
                return;
            }

            if (refetchResponse.plainData.length < 4) {
                fail("Credential refetch response too short");
                return;
            }

            int packetCount = refetchResponse.plainData[1] & 0xFF;
            int credentialLength = ((refetchResponse.plainData[2] & 0xFF))
                                 | ((refetchResponse.plainData[3] & 0xFF) << 8);
            int expectedCrc = refetchResponse.plainData.length > 4 ? (refetchResponse.plainData[4] & 0xFF) : -1;

            Log.d(TAG, "Refetch: packets=" + packetCount + " credLen=" + credentialLength + " expectedCrc=" + expectedCrc);

            if (packetCount <= 0 || credentialLength <= 0) {
                fail("Credential refetch returned invalid packet metadata");
                return;
            }

            byte[][] chunks = new byte[packetCount][];
            for (int i = 0; i < packetCount; i++) {
                byte[] readCmd = BleCommandBuilder.buildReadPacket(deviceId, i);
                byte[] readResp = sendAndWaitForNotification(readCmd);
                if (readResp == null) {
                    fail("No response to read packet " + i);
                    return;
                }
                BleResponse readResponse = BleCommandBuilder.parseResponse(deviceId, readResp);
                if (!isValidResponse(readResponse, BleCommandBuilder.CMD_READ_PACKET, "Read packet " + i)) {
                    return;
                }
                if (readResponse.plainData.length <= 1) {
                    fail("Read packet " + i + " response too short");
                    return;
                }
                int packetIndex = readResponse.plainData[0] & 0xFF;
                if (packetIndex >= packetCount) {
                    fail("Read packet index out of range: " + packetIndex);
                    return;
                }
                chunks[packetIndex] = Arrays.copyOfRange(readResponse.plainData, 1, readResponse.plainData.length);
            }

            byte[] credentialBytes = new byte[0];
            for (int i = 0; i < packetCount; i++) {
                if (chunks[i] == null) {
                    fail("Missing credential packet " + i);
                    return;
                }
                byte[] newCred = new byte[credentialBytes.length + chunks[i].length];
                System.arraycopy(credentialBytes, 0, newCred, 0, credentialBytes.length);
                System.arraycopy(chunks[i], 0, newCred, credentialBytes.length, chunks[i].length);
                credentialBytes = newCred;
            }

            if (credentialLength > 0 && credentialBytes.length >= credentialLength) {
                byte[] trimmed = new byte[credentialLength];
                System.arraycopy(credentialBytes, 0, trimmed, 0, credentialLength);
                credentialBytes = trimmed;
            }

            if (expectedCrc >= 0) {
                int actualCrc = com.midairlogn.seudoorunlock.crypto.CRC8.compute(credentialBytes);
                if (actualCrc != expectedCrc) {
                    Log.w(TAG, "Credential CRC mismatch: expected=" + expectedCrc + " actual=" + actualCrc);
                    fail("Credential CRC verification failed");
                    return;
                }
                Log.d(TAG, "Credential CRC verified OK");
            }

            String hex = bytesToHex(credentialBytes).toUpperCase();
            if (!hex.matches("^[0-9A-F]{64}$")) {
                fail("Door lock returned invalid refreshed credential");
                return;
            }
            cache.saveDoorLock(deviceId, cache.getBleMac(), hex, cache.getCredentialId());

            // Re-sync with server in background
            credentialApi.syncCredential(cache.getCredentialId(), new CredentialApi.SyncCallback() {
                @Override public void onSuccess(com.midairlogn.seudoorunlock.model.DoorLockInfo info) {
                    Log.d(TAG, "Server credential re-synced after BLE refresh");
                }
                @Override public void onError(String message) {
                    Log.w(TAG, "Server credential re-sync failed: " + message);
                }
            });

            byte[] openCmd = BleCommandBuilder.buildOpenDoor(deviceId);
            byte[] openResp = sendAndWaitForNotification(openCmd);
            if (openResp != null) {
                BleResponse openResponse = BleCommandBuilder.parseResponse(deviceId, openResp);
                if (!isValidResponse(openResponse, BleCommandBuilder.CMD_OPEN_DOOR, "Open door after refresh")) {
                    return;
                }
                if (openResponse.isSuccess()) {
                    success("Door opened (after credential refresh)");
                } else {
                    fail("Open door failed after refresh: " + openResponse.getErrorMessage());
                }
            } else {
                fail("No response after credential refresh");
            }

        } catch (Exception e) {
            fail("Credential refetch error: " + e.getMessage());
        }
    }

    private boolean isValidResponse(BleResponse response, int expectedCommand, String stage) {
        if (response.commandType != expectedCommand) {
            fail(stage + " response command mismatch: " + response.commandType);
            return false;
        }
        if (!response.crcValid) {
            fail(stage + " response CRC check failed");
            return false;
        }
        return true;
    }

    private synchronized byte[] sendAndWaitForNotification(byte[] data) throws InterruptedException {
        waitingForWrite = true;
        lastWriteStatus = BluetoothGatt.GATT_FAILURE;
        waitingForNotification = true;
        lastNotificationData = null;

        if (!writeCharacteristic(data)) {
            waitingForWrite = false;
            waitingForNotification = false;
            throw new IllegalStateException("Failed to start BLE write");
        }

        long deadline = System.currentTimeMillis() + OPERATION_TIMEOUT_MS;
        while (waitingForWrite && System.currentTimeMillis() < deadline) {
            wait(Math.max(1, deadline - System.currentTimeMillis()));
        }
        if (waitingForWrite) {
            waitingForWrite = false;
            waitingForNotification = false;
            throw new IllegalStateException("BLE write timed out");
        }
        if (lastWriteStatus != BluetoothGatt.GATT_SUCCESS) {
            waitingForNotification = false;
            throw new IllegalStateException("BLE write failed: " + lastWriteStatus);
        }

        while (waitingForNotification && System.currentTimeMillis() < deadline) {
            wait(Math.max(1, deadline - System.currentTimeMillis()));
        }

        return lastNotificationData;
    }

    @SuppressLint("MissingPermission")
    private boolean writeCharacteristic(byte[] data) {
        if (bluetoothGatt == null || writeCharacteristic == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return bluetoothGatt.writeCharacteristic(writeCharacteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    == BluetoothStatusCodes.SUCCESS;
            } else {
                writeCharacteristic.setValue(data);
                writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                return bluetoothGatt.writeCharacteristic(writeCharacteristic);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to write BLE characteristic", e);
            return false;
        }
    }

    private void scheduleTimeout(int delayMs, String message) {
        timeoutHandler.postDelayed(() -> {
            if (pendingCallback != null && !operationFinished) {
                if (!unlockFlowStarted && targetDevice != null) {
                    cleanupGattOnly();
                    retryOrFail(message);
                } else {
                    fail(message);
                }
            }
        }, delayMs);
    }

    private boolean isStaleGatt(BluetoothGatt gatt) {
        return gatt == null || gatt != bluetoothGatt;
    }

    private void success(String message) {
        if (operationFinished) return;
        operationFinished = true;
        clearActivationState();
        cleanupGatt();
        mainHandler.post(() -> {
            if (pendingCallback != null) pendingCallback.onSuccess(message);
        });
    }

    private void fail(String message) {
        if (operationFinished) return;
        operationFinished = true;
        clearActivationState();
        cleanupGatt();
        mainHandler.post(() -> {
            if (pendingCallback != null) pendingCallback.onError(message);
        });
    }

    @SuppressLint("MissingPermission")
    private void cleanupGatt() {
        timeoutHandler.removeCallbacksAndMessages(null);
        cleanupGattOnly();
    }

    @SuppressLint("MissingPermission")
    private void cleanupGattOnly() {
        BluetoothGatt gatt = bluetoothGatt;
        bluetoothGatt = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to disconnect BLE GATT", e);
            }
            try {
                gatt.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to close BLE GATT", e);
            }
        }
        writeCharacteristic = null;
        readCharacteristic = null;
        synchronized (this) {
            waitingForWrite = false;
            waitingForNotification = false;
            waitingForDescriptor = false;
            notifyAll();
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScanQuietly(BluetoothLeScanner scanner, ScanCallback callback) {
        try {
            scanner.stopScan(callback);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to stop BLE scan", e);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message != null && !message.isEmpty() ? message : throwable.getClass().getSimpleName();
    }

    private static boolean hasDoorService(ScanRecord scanRecord) {
        if (scanRecord == null) return false;
        List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
        if (serviceUuids == null) return false;
        for (ParcelUuid parcelUuid : serviceUuids) {
            if (SERVICE_UUID.equals(parcelUuid.getUuid())) return true;
        }
        return false;
    }

    @SuppressLint("MissingPermission")
    private static String safeDeviceAddress(BluetoothDevice device) {
        if (device == null) return "";
        try {
            String address = device.getAddress();
            return address != null ? address : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    @SuppressLint("MissingPermission")
    private static String safeDeviceName(BluetoothDevice device) {
        if (device == null) return null;
        try {
            return device.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int parseDeviceId(String deviceName) {
        if (deviceName == null || !deviceName.startsWith("XN-")) return 0;
        int start = 3;
        int end = start;
        while (end < deviceName.length() && Character.isDigit(deviceName.charAt(end))) {
            end++;
        }
        if (end == start) return 0;
        try {
            return Integer.parseInt(deviceName.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean matchesTargetName(String deviceName, String targetName) {
        if (deviceName == null) return false;
        if (deviceName.equals(targetName)) return true;
        if (!deviceName.startsWith(targetName) || deviceName.length() == targetName.length()) {
            return false;
        }
        char delimiter = deviceName.charAt(targetName.length());
        return delimiter == '-' || delimiter == '_' || delimiter == ' ';
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private int getEffectiveProjectId() {
        int projectId = cache.getProjectId();
        return projectId > 0 ? projectId : ApiClient.PROJECT_ID;
    }

    private int getEffectiveAppId() {
        int appId = cache.getAppId();
        return appId > 0 ? appId : ApiClient.APP_ID;
    }

    private boolean shouldResolveProjectIds() {
        int projectId = cache.getProjectId();
        int appId = cache.getAppId();
        return projectId <= 0 || appId <= 0
            || (projectId == ApiClient.PROJECT_ID && appId == ApiClient.APP_ID);
    }

    @SuppressLint("MissingPermission")
    private void activateDigitalCredential() {
        int deviceId = cache.getDeviceId();
        int projectId = getEffectiveProjectId();
        int appId = getEffectiveAppId();

        CredentialApi activationApi = new CredentialApi(cache);

        // Step 1: Resolve project IDs if missing or still using legacy defaults
        if (shouldResolveProjectIds()) {
            activationApi.fetchProjectByDeviceId(deviceId,
                new CredentialApi.ActivationCallback() {
                    @Override
                    public void onSuccess(com.midairlogn.seudoorunlock.model.NfcActivationStep step) {
                        int resolvedProjectId = getEffectiveProjectId();
                        int resolvedAppId = getEffectiveAppId();
                        proceedWithCredentialLookup(deviceId, resolvedProjectId, resolvedAppId, activationApi);
                    }
                    @Override
                    public void onError(String message) {
                        Log.w(TAG, "fetchProjectByDeviceId failed: " + message + ", using defaults");
                        proceedWithCredentialLookup(deviceId, projectId, appId, activationApi);
                    }
                });
        } else {
            proceedWithCredentialLookup(deviceId, projectId, appId, activationApi);
        }
    }

    private void proceedWithCredentialLookup(int deviceId, int projectId, int appId,
                                              CredentialApi activationApi) {
        // Step 2: Find or create digital credential
        activationApi.findOrCreateDigitalCredential(deviceId, projectId, appId,
            new CredentialApi.ActivationCallback() {
                @Override
                public void onSuccess(com.midairlogn.seudoorunlock.model.NfcActivationStep step) {
                    String credentialId = step.credentialId;
                    if (credentialId == null || credentialId.isEmpty()) {
                        fail("Server did not return credential ID");
                        return;
                    }
                    proceedWithBleActivation(deviceId, credentialId, projectId, appId, activationApi);
                }
                @Override
                public void onError(String message) {
                    fail("Credential lookup failed: " + message);
                }
            });
    }

    @SuppressLint("MissingPermission")
    private void proceedWithBleActivation(int deviceId, String credentialId,
                                           int projectId, int appId,
                                           CredentialApi activationApi) {
        // Step 3: Start BLE activation
        activationApi.startBleActivation(deviceId, credentialId, projectId, appId,
            new CredentialApi.ActivationCallback() {
                @Override
                public void onSuccess(com.midairlogn.seudoorunlock.model.NfcActivationStep step) {
                    // Store activation state for use after connection
                    pendingActivationStep = step;
                    pendingActivationApi = activationApi;
                    pendingActivationProjectId = projectId;
                    pendingActivationAppId = appId;
                    isActivationFlow = true;

                    String cachedMac = cache.getBleMac();
                    if (!cachedMac.isEmpty()) {
                        try {
                            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(cachedMac);
                            if (device != null) {
                                connectToDevice(device, deviceId, true);
                                return;
                            }
                        } catch (IllegalArgumentException e) {
                            Log.w(TAG, "Cached BLE MAC is invalid during activation, falling back to scan", e);
                        }
                    }
                    startScanAndConnect();
                }
                @Override
                public void onError(String message) {
                    fail("BLE activation start failed: " + message);
                }
            });
    }

    private void runBleActivationLoop() {
        executor.execute(() -> {
            try {
                NfcActivationStep currentStep = pendingActivationStep;
                CredentialApi activationApi = pendingActivationApi;
                int projectId = pendingActivationProjectId;
                int appId = pendingActivationAppId;
                int deviceId = activeDeviceId != 0 ? activeDeviceId : cache.getDeviceId();

                for (int round = 0; round < MAX_ACTIVATION_ROUNDS; round++) {
                    if (currentStep.isComplete()) {
                        String credentialHex = currentStep.credentialHex;
                        if (credentialHex == null || credentialHex.isEmpty()) {
                            throw new Exception("Activation did not return local credential");
                        }
                        cache.saveDoorLock(deviceId, cache.getBleMac(),
                            credentialHex, parseCredentialId(currentStep.credentialId, cache.getCredentialId()),
                            projectId, appId);
                        success("Digital key activated");
                        return;
                    }

                    java.util.List<String> responses = new java.util.ArrayList<>();
                    for (String packet : currentStep.packets) {
                        byte[] request = NfcCommandBuilder.hexToBytes(packet);
                        byte[] resp = sendAndWaitForNotification(request);
                        if (resp == null || resp.length == 0) {
                            throw new Exception("Door lock returned no activation response");
                        }
                        responses.add(bytesToHex(resp));
                    }

                    currentStep = activationApi.submitActivationResponsesSync(currentStep, responses,
                        "ble", projectId, appId);
                }

                throw new Exception("BLE activation round limit exceeded");
            } catch (Exception e) {
                Log.e(TAG, "BLE activation error", e);
                fail("BLE activation error: " + e.getMessage());
            }
        });
    }

    private void clearActivationState() {
        pendingActivationStep = null;
        pendingActivationApi = null;
        pendingActivationProjectId = 0;
        pendingActivationAppId = 0;
        isActivationFlow = false;
    }

    private static int parseCredentialId(String credentialId, int fallback) {
        if (credentialId == null || credentialId.isEmpty()) return fallback;
        try { return Integer.parseInt(credentialId); } catch (NumberFormatException e) { return fallback; }
    }

    // Activation state fields
    private com.midairlogn.seudoorunlock.model.NfcActivationStep pendingActivationStep;
    private CredentialApi pendingActivationApi;
    private int pendingActivationProjectId;
    private int pendingActivationAppId;
    private boolean isActivationFlow = false;

    public void onDestroy() {
        cleanupGatt();
        pendingCallback = null;
    }
}
