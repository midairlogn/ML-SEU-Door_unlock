package com.midairlogn.seudoorunlock.nfc;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.IntentCompat;

import com.midairlogn.seudoorunlock.AppExecutors;
import com.midairlogn.seudoorunlock.api.CredentialApi;
import com.midairlogn.seudoorunlock.model.DoorResponse;
import com.midairlogn.seudoorunlock.storage.CredentialCache;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public class NfcUnlockManager {

    private static final String TAG = "ZL_NfcManager";
    private static final int TIMEOUT_MS = 1500;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 200;
    private static final int USER_DATA_START_PAGE = 4;
    private static final int DEFAULT_USER_BYTES = 144;
    private static final int PAGE_SIZE = 4;
    private static final int READ_BLOCK_PAGES = 4;

    public interface NfcCallback {
        void onSuccess(DoorResponse response);
        void onError(String message);
        void onExpired();
    }

    private NfcAdapter nfcAdapter;
    private final CredentialCache cache;
    private final CredentialApi credentialApi;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private NfcCallback pendingCallback;
    private boolean readerModeEnabled = false;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    public NfcUnlockManager(Activity activity, CredentialCache cache) {
        this.cache = cache;
        this.credentialApi = new CredentialApi(cache);
        this.executor = AppExecutors.getInstance();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
    }

    public boolean isNfcSupported() {
        return nfcAdapter != null;
    }

    public boolean isNfcEnabled() {
        return nfcAdapter != null && nfcAdapter.isEnabled();
    }

    public void enableReaderMode(Activity activity, NfcCallback callback) {
        if (nfcAdapter == null) {
            callback.onError("NFC not supported");
            return;
        }
        this.pendingCallback = callback;
        int flags = NfcAdapter.FLAG_READER_NFC_A
            | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;
        nfcAdapter.enableReaderMode(activity, tag -> {
            handleTagDiscovered(tag);
        }, flags, null);
        readerModeEnabled = true;
        Log.d(TAG, "Reader mode enabled");
    }

    public void disableReaderMode(Activity activity) {
        if (nfcAdapter != null && readerModeEnabled) {
            nfcAdapter.disableReaderMode(activity);
            readerModeEnabled = false;
            Log.d(TAG, "Reader mode disabled");
        }
    }

    public boolean handleIntent(Intent intent, NfcCallback callback) {
        pendingCallback = callback;
        if (intent == null) return false;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
            || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
            || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            Tag tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag.class);
            if (tag != null) {
                return handleTagDiscovered(tag);
            }
        }
        return false;
    }

    private boolean handleTagDiscovered(Tag tag) {
        if (!isProcessing.compareAndSet(false, true)) {
            Log.d(TAG, "Already processing a tag, skipping");
            return false;
        }

        NfcA nfcA = NfcA.get(tag);
        if (nfcA == null) {
            isProcessing.set(false);
            mainHandler.post(() -> {
                if (pendingCallback != null) {
                    pendingCallback.onError("Not an NFC-A tag");
                }
            });
            return true;
        }

        executor.execute(() -> {
            try {
                nfcA.connect();
                nfcA.setTimeout(TIMEOUT_MS);

                int deviceId = readDeviceIdFromTag(nfcA);
                int cachedDeviceId = cache.getDeviceId();
                String credentialHex = cache.getCredentialHex();
                int projectId = 21048;

                if (deviceId == 0) {
                    mainHandler.post(() -> {
                        if (pendingCallback != null) {
                            pendingCallback.onError("NFC tag missing device_id");
                        }
                    });
                    return;
                }

                if (cachedDeviceId != 0 && cachedDeviceId != deviceId) {
                    Log.w(TAG, "NFC tag device_id differs from cache: tag=" + deviceId
                        + " cache=" + cachedDeviceId);
                }

                if (credentialHex.isEmpty()) {
                    mainHandler.post(() -> {
                        if (pendingCallback != null) {
                            pendingCallback.onError("No credentials cached");
                        }
                    });
                    return;
                }

                byte[] command = NfcCommandBuilder.buildCommand(deviceId, credentialHex, projectId);
                if (command == null) {
                    mainHandler.post(() -> {
                        if (pendingCallback != null) {
                            pendingCallback.onError("Failed to build NFC command");
                        }
                    });
                    return;
                }

                DoorResponse lastResponse = null;
                for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                    try {
                        byte[] response = nfcA.transceive(command);
                        if (response != null) {
                            lastResponse = NfcCommandBuilder.parseResponse(deviceId, response);
                            Log.d(TAG, "Attempt " + attempt + " result: " + lastResponse.resultCode);

                            if (lastResponse.isSuccess()) {
                                if (lastResponse.updatedCredentialHex != null
                                    && lastResponse.updatedCredentialHex.matches("^[0-9A-F]{64}$")) {
                                    Log.d(TAG, "NFC returned updated credential, saving");
                                    cache.saveDoorLock(deviceId, cache.getBleMac(),
                                        lastResponse.updatedCredentialHex, cache.getCredentialId());
                                }
                                final DoorResponse resp = lastResponse;
                                mainHandler.post(() -> {
                                    if (pendingCallback != null) {
                                        pendingCallback.onSuccess(resp);
                                    }
                                });
                                return;
                            }

                            if (lastResponse.isExpired()) {
                                reSyncWithServer();
                                mainHandler.post(() -> {
                                    if (pendingCallback != null) {
                                        pendingCallback.onExpired();
                                    }
                                });
                                return;
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Transceive attempt " + attempt + " failed", e);
                    }

                    if (attempt < MAX_RETRIES - 1) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException ignored) {}
                    }
                }

                final DoorResponse resp = lastResponse;
                mainHandler.post(() -> {
                    if (pendingCallback != null) {
                        if (resp != null) {
                            pendingCallback.onError(resp.getErrorMessage());
                        } else {
                            pendingCallback.onError("NFC communication failed");
                        }
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "NFC error", e);
                mainHandler.post(() -> {
                    if (pendingCallback != null) {
                        pendingCallback.onError("NFC error: " + e.getMessage());
                    }
                });
            } finally {
                try {
                    nfcA.close();
                } catch (Exception ignored) {}
                isProcessing.set(false);
            }
        });
        return true;
    }

    public void onDestroy() {
        pendingCallback = null;
    }

    private int readDeviceIdFromTag(NfcA nfcA) throws Exception {
        byte[] userMemory = readBytes(nfcA, USER_DATA_START_PAGE, DEFAULT_USER_BYTES);
        byte[] ndefBytes = extractNdefMessageBytes(userMemory);
        if (ndefBytes == null) return 0;

        NdefMessage message = new NdefMessage(ndefBytes);
        for (NdefRecord record : message.getRecords()) {
            String payload = decodeRecordPayload(record).trim();
            if (payload.startsWith("http://") || payload.startsWith("https://")) {
                Uri uri = Uri.parse(payload);
                String value = uri.getQueryParameter("d");
                if (value == null || value.isEmpty()) {
                    value = uri.getQueryParameter("device_id");
                }
                int deviceId = parsePositiveInt(value);
                if (deviceId != 0) return deviceId;
            }
        }
        return 0;
    }

    private static byte[] readBytes(NfcA nfcA, int startPage, int byteCount) throws Exception {
        byte[] result = new byte[byteCount];
        int copied = 0;
        int currentPage = startPage;
        while (copied < byteCount) {
            byte[] block = nfcA.transceive(new byte[]{0x30, (byte) currentPage});
            if (block == null || block.length < READ_BLOCK_PAGES * PAGE_SIZE) {
                throw new IllegalStateException("NFC read returned too few bytes at page " + currentPage);
            }
            int copySize = Math.min(block.length, byteCount - copied);
            System.arraycopy(block, 0, result, copied, copySize);
            copied += copySize;
            currentPage += READ_BLOCK_PAGES;
        }
        return result;
    }

    private static byte[] extractNdefMessageBytes(byte[] memory) {
        int i = 0;
        while (i < memory.length) {
            int type = memory[i] & 0xFF;
            if (type == 0x00) {
                i++;
            } else if (type == 0xFE) {
                return null;
            } else {
                if (i + 1 >= memory.length) return null;
                int len = memory[i + 1] & 0xFF;
                int valueStart = i + 2;
                if (len == 0xFF) {
                    if (i + 3 >= memory.length) return null;
                    len = ((memory[i + 2] & 0xFF) << 8) | (memory[i + 3] & 0xFF);
                    valueStart = i + 4;
                }
                if (len == 0 || valueStart + len > memory.length) return null;
                if (type == 0x03) {
                    return Arrays.copyOfRange(memory, valueStart, valueStart + len);
                }
                i = valueStart + len;
            }
        }
        return null;
    }

    private static String decodeRecordPayload(NdefRecord record) {
        if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN
            && Arrays.equals(record.getType(), NdefRecord.RTD_URI)) {
            return decodeUriPayload(record.getPayload());
        }
        if (record.getTnf() == NdefRecord.TNF_WELL_KNOWN
            && Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) {
            return decodeTextPayload(record.getPayload());
        }
        return new String(record.getPayload(), StandardCharsets.UTF_8);
    }

    private static String decodeUriPayload(byte[] payload) {
        if (payload == null || payload.length == 0) return "";
        String prefix;
        switch (payload[0] & 0xFF) {
            case 0x01: prefix = "http://www."; break;
            case 0x02: prefix = "https://www."; break;
            case 0x03: prefix = "http://"; break;
            case 0x04: prefix = "https://"; break;
            default: prefix = ""; break;
        }
        return prefix + new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
    }

    private static String decodeTextPayload(byte[] payload) {
        if (payload == null || payload.length == 0) return "";
        int languageLength = payload[0] & 0x3F;
        int textStart = 1 + languageLength;
        if (textStart >= payload.length) return "";
        return new String(payload, textStart, payload.length - textStart, StandardCharsets.UTF_8);
    }

    private static int parsePositiveInt(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void reSyncWithServer() {
        Log.d(TAG, "Re-syncing credential with server after expired code");
        credentialApi.syncCredential(cache.getCredentialId(), new CredentialApi.SyncCallback() {
            @Override
            public void onSuccess(com.midairlogn.seudoorunlock.model.DoorLockInfo info) {
                Log.d(TAG, "Server credential re-synced after NFC expired");
            }
            @Override
            public void onError(String message) {
                Log.w(TAG, "Server credential re-sync failed: " + message);
            }
        });
    }
}
