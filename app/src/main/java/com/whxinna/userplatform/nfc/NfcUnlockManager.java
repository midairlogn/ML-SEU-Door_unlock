package com.whxinna.userplatform.nfc;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.IntentCompat;

import com.whxinna.userplatform.api.CredentialApi;
import com.whxinna.userplatform.model.DoorResponse;
import com.whxinna.userplatform.storage.CredentialCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NfcUnlockManager {

    private static final String TAG = "ZL_NfcManager";
    private static final int TIMEOUT_MS = 1500;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 200;

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

    public NfcUnlockManager(Activity activity, CredentialCache cache) {
        this.cache = cache;
        this.credentialApi = new CredentialApi(cache);
        this.executor = Executors.newSingleThreadExecutor();
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
        nfcAdapter.enableReaderMode(activity, this::handleTagDiscovered, flags, null);
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

    public void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
            || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
            || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            Tag tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag.class);
            if (tag != null) {
                handleTagDiscovered(tag);
            }
        }
    }

    private void handleTagDiscovered(Tag tag) {
        NfcA nfcA = NfcA.get(tag);
        if (nfcA == null) {
            mainHandler.post(() -> {
                if (pendingCallback != null) {
                    pendingCallback.onError("Not an NFC-A tag");
                }
            });
            return;
        }

        executor.execute(() -> {
            try {
                nfcA.connect();
                nfcA.setTimeout(TIMEOUT_MS);

                int deviceId = cache.getDeviceId();
                String credentialHex = cache.getCredentialHex();
                int projectId = 21048;

                if (deviceId == 0 || credentialHex.isEmpty()) {
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
                                nfcA.close();
                                final DoorResponse resp = lastResponse;
                                mainHandler.post(() -> {
                                    if (pendingCallback != null) {
                                        pendingCallback.onSuccess(resp);
                                    }
                                });
                                return;
                            }

                            if (lastResponse.isExpired()) {
                                nfcA.close();
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

                nfcA.close();
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
                try { nfcA.close(); } catch (Exception ignored) {}
                mainHandler.post(() -> {
                    if (pendingCallback != null) {
                        pendingCallback.onError("NFC error: " + e.getMessage());
                    }
                });
            }
        });
    }

    public void onDestroy() {
        pendingCallback = null;
    }

    private void reSyncWithServer() {
        Log.d(TAG, "Re-syncing credential with server after expired code");
        credentialApi.syncCredential(cache.getCredentialId(), new CredentialApi.SyncCallback() {
            @Override
            public void onSuccess(com.whxinna.userplatform.model.DoorLockInfo info) {
                Log.d(TAG, "Server credential re-synced after NFC expired");
            }
            @Override
            public void onError(String message) {
                Log.w(TAG, "Server credential re-sync failed: " + message);
            }
        });
    }
}
