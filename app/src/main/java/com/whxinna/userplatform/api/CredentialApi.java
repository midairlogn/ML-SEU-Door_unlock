package com.whxinna.userplatform.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.whxinna.userplatform.model.DoorLockInfo;
import com.whxinna.userplatform.storage.CredentialCache;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;

public class CredentialApi {

    private static final String TAG = "ZL_CredentialApi";

    public interface SyncCallback {
        void onSuccess(DoorLockInfo info);
        void onError(String message);
    }

    private final ApiClient api;
    private final CredentialCache cache;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public CredentialApi(CredentialCache cache) {
        this.api = ApiClient.getInstance();
        this.cache = cache;
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void syncDoorLockInfo(SyncCallback callback) {
        executor.execute(() -> {
            try {
                String serverUrl = cache.getServerUrl();
                if (serverUrl.isEmpty()) {
                    mainHandler.post(() -> callback.onError("No server URL"));
                    return;
                }

                HttpUrl.Builder urlBuilder = HttpUrl.parse(
                    serverUrl + "/webapi/v1/student/accommodation/details")
                    .newBuilder()
                    .addQueryParameter("user_id", cache.getUserId())
                    .addQueryParameter("identitycode", cache.getIdentityCode());

                String responseJson = api.executeBusinessRequest(
                    urlBuilder, cache.getPlatformToken(), cache.getSessionSecret());

                String dataStr = ApiClient.extractDataField(responseJson);
                DoorLockInfo info = DoorLockInfo.fromJson(dataStr);

                String deviceId = info.doorLock != null ? String.valueOf(info.doorLock.deviceId) : "";
                String credential = info.doorLock != null ? info.doorLock.credential : "";
                int credentialId = info.doorLock != null ? info.doorLock.credentialId : 0;
                String bleMac = info.doorLock != null ? info.doorLock.bleMac : "";

                if (deviceId.isEmpty()) {
                    mainHandler.post(() -> callback.onError("Server returned missing device_id"));
                    return;
                }

                if (credential.isEmpty() || !credential.matches("^[0-9A-Fa-f]{64}$")) {
                    Log.d(TAG, "Credential missing or invalid, fetching from credentials endpoint");
                    HttpUrl.Builder credBuilder = HttpUrl.parse(
                        serverUrl + "/webapi/v1/staff/door_lock/credentials")
                        .newBuilder()
                        .addQueryParameter("device_id", deviceId)
                        .addQueryParameter("user_id", cache.getUserId())
                        .addQueryParameter("identitycode", cache.getIdentityCode());

                    String credResponse = api.executeBusinessRequest(
                        credBuilder, cache.getPlatformToken(), cache.getSessionSecret());
                    String credDataStr = ApiClient.extractDataField(credResponse);
                    JSONObject credData = new JSONObject(credDataStr);

                    credential = credData.optString("credential", "");
                    int newCredentialId = credData.optInt("credential_id", credentialId);
                    if (newCredentialId != 0) credentialId = newCredentialId;
                }

                String normalizedCredential = credential.toUpperCase();
                if (!normalizedCredential.matches("^[0-9A-F]{64}$")) {
                    mainHandler.post(() -> callback.onError("Server returned invalid credential"));
                    return;
                }

                cache.saveDoorLock(Integer.parseInt(deviceId), bleMac, normalizedCredential, credentialId);

                if (info.accommodation != null) {
                    double battery = info.doorLock != null ? info.doorLock.batteryLevel : 100;
                    cache.saveAccommodationInfo(info.accommodation.getDisplayName(), battery);
                }
                cache.updateTimestamp();

                mainHandler.post(() -> callback.onSuccess(info));

            } catch (IOException e) {
                Log.e(TAG, "Sync network error", e);
                mainHandler.post(() -> callback.onError("Network error: " + e.getMessage()));
            } catch (JSONException e) {
                Log.e(TAG, "Sync parse error", e);
                mainHandler.post(() -> callback.onError("Parse error: " + e.getMessage()));
            } catch (Exception e) {
                Log.e(TAG, "Sync error", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
            }
        });
    }

    public void syncCredential(int credentialId, SyncCallback callback) {
        executor.execute(() -> {
            try {
                String serverUrl = cache.getServerUrl();
                if (serverUrl.isEmpty()) {
                    mainHandler.post(() -> callback.onError("No server URL"));
                    return;
                }

                HttpUrl.Builder urlBuilder = HttpUrl.parse(
                    serverUrl + "/webapi/v1/staff/door_lock/credentials")
                    .newBuilder()
                    .addQueryParameter("device_id", String.valueOf(cache.getDeviceId()))
                    .addQueryParameter("user_id", cache.getUserId())
                    .addQueryParameter("identitycode", cache.getIdentityCode());

                String responseJson = api.executeBusinessRequest(
                    urlBuilder, cache.getPlatformToken(), cache.getSessionSecret());

                String dataStr = ApiClient.extractDataField(responseJson);
                JSONObject data = new JSONObject(dataStr);

                String credential = data.optString("credential", "");
                int newCredentialId = data.optInt("credential_id", credentialId);

                if (!credential.isEmpty()) {
                    cache.saveDoorLock(cache.getDeviceId(), cache.getBleMac(), credential, newCredentialId);
                }

                DoorLockInfo info = DoorLockInfo.fromJson(dataStr);
                mainHandler.post(() -> callback.onSuccess(info));

            } catch (Exception e) {
                Log.e(TAG, "Credential sync error", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
            }
        });
    }

    public void refreshIfNeeded(SyncCallback callback) {
        if (cache.needsRefresh() || !cache.hasDoorLock()) {
            syncDoorLockInfo(callback);
        } else {
            mainHandler.post(() -> callback.onError("Refresh not needed"));
        }
    }
}
