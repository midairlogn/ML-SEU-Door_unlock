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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.midairlogn.seudoorunlock.AppExecutors;
import com.midairlogn.seudoorunlock.api.CredentialApi;
import com.midairlogn.seudoorunlock.model.BleResponse;
import com.midairlogn.seudoorunlock.storage.CredentialCache;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public class BleUnlockManager {

    private static final String TAG = "ZL_BleManager";

    private static final UUID SERVICE_UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    private static final UUID READ_UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int OPERATION_TIMEOUT_MS = 5000;
    private static final int INTER_PACKET_DELAY_MS = 10;
    private static final int PRE_OPEN_DELAY_MS = 50;
    private static final int PROJECT_ID = 21048;

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
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic readCharacteristic;
    private BleCallback pendingCallback;

    private byte[] pendingData;
    private int pendingIndex;
    private int pendingTotal;
    private int pendingRan;
    private boolean waitingForNotification = false;
    private byte[] lastNotificationData;

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

        int deviceId = cache.getDeviceId();
        String credentialHex = cache.getCredentialHex();
        if (deviceId == 0 || credentialHex.isEmpty()) {
            callback.onError("No door lock credentials cached");
            return;
        }

        // Strategy: cached MAC direct → scan fallback
        String cachedMac = cache.getBleMac();
        if (!cachedMac.isEmpty()) {
            Log.d(TAG, "Attempting direct connect to cached MAC: " + cachedMac);
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(cachedMac);
            if (device != null) {
                connectToDevice(device);
                return;
            }
        }

        // Fallback: scan for device
        Log.d(TAG, "Cached MAC failed or empty, starting scan");
        startScanAndConnect();
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        scheduleTimeout(CONNECT_TIMEOUT_MS, "Connection timed out");
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressLint("MissingPermission")
    private void startScanAndConnect() {
        String targetName = "XN-" + cache.getDeviceId();
        String targetAddress = cache.getBleMac().toUpperCase().replace(":", "").replace("-", "");
        Log.d(TAG, "Scanning for: " + targetName + " addr=" + targetAddress);

        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            if (pendingCallback != null) pendingCallback.onError("Bluetooth scanner not available");
            return;
        }

        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String deviceName = device.getName();
                String normalizedAddr = device.getAddress().toUpperCase().replace(":", "").replace("-", "");

                boolean nameMatch = deviceName != null && deviceName.equals(targetName);
                boolean addrMatch = !targetAddress.isEmpty() && normalizedAddr.equals(targetAddress);

                if (nameMatch || addrMatch) {
                    scanner.stopScan(this);
                    Log.d(TAG, "Found device: " + (deviceName != null ? deviceName : device.getAddress())
                        + " match=" + (nameMatch ? "name" : "address"));
                    mainHandler.post(() -> connectToDevice(device));
                }
            }
        };

        scanner.startScan(scanCallback);

        timeoutHandler.postDelayed(() -> {
            scanner.stopScan(scanCallback);
            if (bluetoothGatt == null && pendingCallback != null) {
                pendingCallback.onError("Device not found nearby");
            }
        }, CONNECT_TIMEOUT_MS);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected, discovering services");
                timeoutHandler.removeCallbacksAndMessages(null);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected");
                cleanupGatt();
                if (pendingCallback != null && !waitingForNotification) {
                    mainHandler.post(() -> pendingCallback.onError("Disconnected"));
                }
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: " + status);
                mainHandler.post(() -> {
                    if (pendingCallback != null) pendingCallback.onError("Service discovery failed");
                });
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "Door lock service not found");
                mainHandler.post(() -> {
                    if (pendingCallback != null) pendingCallback.onError("Door lock service not found");
                });
                return;
            }

            writeCharacteristic = service.getCharacteristic(WRITE_UUID);
            readCharacteristic = service.getCharacteristic(READ_UUID);

            if (writeCharacteristic == null || readCharacteristic == null) {
                mainHandler.post(() -> {
                    if (pendingCallback != null) pendingCallback.onError("Characteristics not found");
                });
                return;
            }

            // Enable notifications on read characteristic
            gatt.setCharacteristicNotification(readCharacteristic, true);
            BluetoothGattDescriptor descriptor = readCharacteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
            }

            Log.d(TAG, "Services discovered, starting unlock flow");
            mainHandler.post(() -> startUnlockFlow());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            handleCharacteristicChanged(characteristic.getUuid(), characteristic.getValue());
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            handleCharacteristicChanged(characteristic.getUuid(), value);
        }

        private void handleCharacteristicChanged(UUID uuid, byte[] value) {
            if (uuid.equals(READ_UUID)) {
                lastNotificationData = value;
                waitingForNotification = false;
                Log.d(TAG, "Notification received, len=" + (lastNotificationData != null ? lastNotificationData.length : 0));
                synchronized (BleUnlockManager.this) {
                    BleUnlockManager.this.notifyAll();
                }
            }
        }
    };

    private void startUnlockFlow() {
        executor.execute(() -> {
            try {
                int deviceId = cache.getDeviceId();
                String credentialHex = cache.getCredentialHex();

                // Step 1: Send 0x74 credential header
                byte[] headerCmd = BleCommandBuilder.buildCredentialHeaderForDevice(deviceId, PROJECT_ID, credentialHex);
                byte[] headerResp = sendAndWaitForNotification(headerCmd);
                if (headerResp == null) {
                    fail("No response to credential header");
                    return;
                }

                BleResponse headerResponse = BleCommandBuilder.parseResponse(deviceId, headerResp);
                if (!headerResponse.isSuccess()) {
                    fail("Credential header rejected: " + headerResponse.getResultCode());
                    return;
                }

                int ran = headerResponse.getRandom();
                Log.d(TAG, "Got random: " + ran);

                // Step 2: Send 0x75 × 3 credential packets
                byte[][] packets = BleCommandBuilder.buildCredentialPackets(deviceId, ran, PROJECT_ID, credentialHex);
                for (int i = 0; i < 3; i++) {
                    writeCharacteristic(packets[i]);
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
            int credentialId = cache.getCredentialId();
            byte[] refetchCmd = BleCommandBuilder.buildCredentialRefetch(deviceId, credentialId);
            byte[] refetchResp = sendAndWaitForNotification(refetchCmd);

            if (refetchResp == null) {
                fail("No response to credential refetch");
                return;
            }

            BleResponse refetchResponse = BleCommandBuilder.parseResponse(deviceId, refetchResp);
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

            byte[] credentialBytes = new byte[0];
            for (int i = 0; i < packetCount; i++) {
                byte[] readCmd = BleCommandBuilder.buildReadPacket(deviceId, i);
                byte[] readResp = sendAndWaitForNotification(readCmd);
                if (readResp != null) {
                    BleResponse readResponse = BleCommandBuilder.parseResponse(deviceId, readResp);
                    if (readResponse.plainData.length <= 1) {
                        fail("Read packet " + i + " response too short");
                        return;
                    }
                    byte[] chunk = Arrays.copyOfRange(readResponse.plainData, 1, readResponse.plainData.length);
                    byte[] newCred = new byte[credentialBytes.length + chunk.length];
                    System.arraycopy(credentialBytes, 0, newCred, 0, credentialBytes.length);
                    System.arraycopy(chunk, 0, newCred, credentialBytes.length, chunk.length);
                    credentialBytes = newCred;
                }
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
            if (hex.length() >= 64) {
                hex = hex.substring(0, 64);
                cache.saveDoorLock(deviceId, cache.getBleMac(), hex, credentialId);

                // Re-sync with server in background
                credentialApi.syncCredential(credentialId, new CredentialApi.SyncCallback() {
                    @Override public void onSuccess(com.midairlogn.seudoorunlock.model.DoorLockInfo info) {
                        Log.d(TAG, "Server credential re-synced after BLE refresh");
                    }
                    @Override public void onError(String message) {
                        Log.w(TAG, "Server credential re-sync failed: " + message);
                    }
                });
            }

            byte[] openCmd = BleCommandBuilder.buildOpenDoor(deviceId);
            byte[] openResp = sendAndWaitForNotification(openCmd);
            if (openResp != null) {
                BleResponse openResponse = BleCommandBuilder.parseResponse(deviceId, openResp);
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

    private synchronized byte[] sendAndWaitForNotification(byte[] data) throws InterruptedException {
        waitingForNotification = true;
        lastNotificationData = null;
        writeCharacteristic(data);

        // Wait up to OPERATION_TIMEOUT_MS for notification
        long deadline = System.currentTimeMillis() + OPERATION_TIMEOUT_MS;
        while (waitingForNotification && System.currentTimeMillis() < deadline) {
            wait(OPERATION_TIMEOUT_MS);
        }

        return lastNotificationData;
    }

    @SuppressLint("MissingPermission")
    private void writeCharacteristic(byte[] data) {
        if (bluetoothGatt == null || writeCharacteristic == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt.writeCharacteristic(writeCharacteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            writeCharacteristic.setValue(data);
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            bluetoothGatt.writeCharacteristic(writeCharacteristic);
        }
    }

    private void scheduleTimeout(int delayMs, String message) {
        timeoutHandler.postDelayed(() -> {
            if (pendingCallback != null) {
                cleanupGatt();
                pendingCallback.onError(message);
            }
        }, delayMs);
    }

    private void success(String message) {
        cleanupGatt();
        mainHandler.post(() -> {
            if (pendingCallback != null) pendingCallback.onSuccess(message);
        });
    }

    private void fail(String message) {
        cleanupGatt();
        mainHandler.post(() -> {
            if (pendingCallback != null) pendingCallback.onError(message);
        });
    }

    @SuppressLint("MissingPermission")
    private void cleanupGatt() {
        timeoutHandler.removeCallbacksAndMessages(null);
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        writeCharacteristic = null;
        readCharacteristic = null;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    public void onDestroy() {
        cleanupGatt();
        pendingCallback = null;
    }
}
