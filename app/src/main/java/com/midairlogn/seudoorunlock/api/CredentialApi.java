package com.midairlogn.seudoorunlock.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.midairlogn.seudoorunlock.AppExecutors;
import com.midairlogn.seudoorunlock.model.DoorLockInfo;
import com.midairlogn.seudoorunlock.model.NfcActivationStep;
import com.midairlogn.seudoorunlock.storage.CredentialCache;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;

import okhttp3.HttpUrl;

public class CredentialApi {

    private static final String TAG = "ZL_CredentialApi";

    public interface SyncCallback {
        void onSuccess(DoorLockInfo info);
        void onError(String message);
    }

    public interface ActivationCallback {
        void onSuccess(NfcActivationStep step);
        void onError(String message);
    }

    private final ApiClient api;
    private final CredentialCache cache;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public CredentialApi(CredentialCache cache) {
        this.api = ApiClient.getInstance();
        this.cache = cache;
        this.executor = AppExecutors.getInstance();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void syncDoorLockInfo(SyncCallback callback) {
        executor.execute(() -> {
            try {
                String serverUrl = ApiClient.normalizeServerUrl(cache.getServerUrl());
                if (serverUrl.isEmpty()) {
                    mainHandler.post(() -> callback.onError("No server URL"));
                    return;
                }

                int projectId = cache.getProjectId();
                int appId = cache.getAppId();

                HttpUrl httpUrl = HttpUrl.parse(serverUrl + "/webapi/v1/student/accommodation/details");
                if (httpUrl == null) {
                    mainHandler.post(() -> callback.onError("Invalid server URL"));
                    return;
                }
                HttpUrl.Builder urlBuilder = httpUrl.newBuilder()
                    .addQueryParameter("user_id", cache.getUserId())
                    .addQueryParameter("identitycode", cache.getIdentityCode());

                String responseJson = api.executeBusinessRequest(urlBuilder, cache.getSessionSecret(),
                    projectId > 0 ? projectId : ApiClient.PROJECT_ID,
                    appId > 0 ? appId : ApiClient.APP_ID);

                String dataStr = ApiClient.extractDataField(responseJson);
                DoorLockInfo info = DoorLockInfo.fromJson(dataStr);

                String deviceId = info.doorLock != null ? "" + info.doorLock.deviceId : "";
                String credential = info.doorLock != null ? info.doorLock.credential : "";
                int credentialId = info.doorLock != null ? info.doorLock.credentialId : 0;
                String bleMac = info.doorLock != null ? info.doorLock.bleMac : "";

                if (deviceId.isEmpty()) {
                    mainHandler.post(() -> callback.onError("Server returned missing device_id"));
                    return;
                }

                if (credential.isEmpty() || !credential.matches("^[0-9A-Fa-f]{64}$")) {
                    Log.d(TAG, "Credential missing or invalid, fetching from credentials endpoint");
                    credential = fetchCredentialFromCredentialsEndpoint(serverUrl, deviceId, projectId, appId);
                    if (credential == null) {
                        credential = "";
                    }
                }

                String normalizedCredential = credential.toUpperCase();
                if (!normalizedCredential.matches("^[0-9A-F]{64}$")) {
                    mainHandler.post(() -> callback.onError("Server returned invalid credential"));
                    return;
                }

                cache.saveDoorLock(Integer.parseInt(deviceId), bleMac, normalizedCredential, credentialId,
                    projectId > 0 ? projectId : ApiClient.PROJECT_ID,
                    appId > 0 ? appId : ApiClient.APP_ID);

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

    private String fetchCredentialFromCredentialsEndpoint(String serverUrl, String deviceId,
                                                           int projectId, int appId) {
        try {
            HttpUrl credUrl = HttpUrl.parse(serverUrl + "/webapi/v1/staff/door_lock/credentials");
            if (credUrl == null) return null;

            HttpUrl.Builder credBuilder = credUrl.newBuilder()
                .addQueryParameter("device_id", deviceId)
                .addQueryParameter("user_id", cache.getUserId())
                .addQueryParameter("identitycode", cache.getIdentityCode());

            String credResponse = api.executeBusinessRequest(credBuilder, cache.getSessionSecret(),
                projectId > 0 ? projectId : ApiClient.PROJECT_ID,
                appId > 0 ? appId : ApiClient.APP_ID);
            String credDataStr = ApiClient.extractDataField(credResponse);
            JSONObject credData = new JSONObject(credDataStr);

            String credential = credData.optString("credential", "");
            if (credential.isEmpty()) credential = credData.optString("chain_key", "");
            if (credential.isEmpty()) credential = credData.optString("chainKey", "");

            int newCredentialId = credData.optInt("credential_id", 0);
            if (newCredentialId == 0) {
                newCredentialId = extractCredentialIdFromRows(credData);
            }
            if (newCredentialId != 0) {
                cache.saveDoorLock(Integer.parseInt(deviceId), cache.getBleMac(),
                    credential.toUpperCase(), newCredentialId, projectId, appId);
            }

            return credential;
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch credential from credentials endpoint", e);
            return null;
        }
    }

    public void syncCredential(int credentialId, SyncCallback callback) {
        executor.execute(() -> {
            try {
                String serverUrl = ApiClient.normalizeServerUrl(cache.getServerUrl());
                if (serverUrl.isEmpty()) {
                    mainHandler.post(() -> callback.onError("No server URL"));
                    return;
                }

                int projectId = cache.getProjectId();
                int appId = cache.getAppId();

                HttpUrl httpUrl = HttpUrl.parse(serverUrl + "/webapi/v1/staff/door_lock/credentials");
                if (httpUrl == null) {
                    mainHandler.post(() -> callback.onError("Invalid server URL"));
                    return;
                }
                HttpUrl.Builder urlBuilder = httpUrl.newBuilder()
                    .addQueryParameter("device_id", "" + cache.getDeviceId())
                    .addQueryParameter("user_id", cache.getUserId())
                    .addQueryParameter("identitycode", cache.getIdentityCode());

                String responseJson = api.executeBusinessRequest(urlBuilder, cache.getSessionSecret(),
                    projectId > 0 ? projectId : ApiClient.PROJECT_ID,
                    appId > 0 ? appId : ApiClient.APP_ID);

                String dataStr = ApiClient.extractDataField(responseJson);
                JSONObject data = new JSONObject(dataStr);

                String credential = data.optString("credential", "");
                if (credential.isEmpty()) credential = data.optString("chain_key", "");
                if (credential.isEmpty()) credential = data.optString("chainKey", "");
                int newCredentialId = data.optInt("credential_id", credentialId);
                if (newCredentialId == 0) {
                    newCredentialId = extractCredentialIdFromRows(data);
                }
                if (newCredentialId == 0) newCredentialId = credentialId;

                if (!credential.isEmpty()) {
                    cache.saveDoorLock(cache.getDeviceId(), cache.getBleMac(), credential.toUpperCase(),
                        newCredentialId, projectId, appId);
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

    public void fetchProjectByDeviceId(int deviceId, ActivationCallback callback) {
        executor.execute(() -> {
            try {
                HttpUrl httpUrl = HttpUrl.parse(api.getAuthBaseUrl() + "/webapi/project/get_by_device_id");
                if (httpUrl == null) {
                    mainHandler.post(() -> callback.onError("Invalid auth URL"));
                    return;
                }
                HttpUrl.Builder urlBuilder = httpUrl.newBuilder()
                    .addQueryParameter("device_id", String.valueOf(deviceId));

                String responseJson = api.executeAuthRequest(urlBuilder, false);
                String dataStr = ApiClient.extractDataField(responseJson);

                JSONObject data = new JSONObject(dataStr);
                int projectId = findPositiveInt(data, "project_id", "server_appid", "id");
                int appId = findPositiveInt(data, "app_id", "server_id");

                if (projectId > 0 && cache.getProjectId() == 0) {
                    cache.saveSession(cache.getPhone(), cache.getPassword(), cache.getUserId(),
                        cache.getIdentityCode(), cache.getPlatformToken(),
                        cache.getSessionSecret(), cache.getServerUrl(), projectId, appId);
                }

                JSONObject result = new JSONObject();
                result.put("projectId", projectId);
                result.put("appId", appId);
                mainHandler.post(() -> callback.onSuccess(
                    new NfcActivationStep(result, new ArrayList<>(), "", null)));
            } catch (Exception e) {
                Log.e(TAG, "fetchProjectByDeviceId error", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
            }
        });
    }

    public void findOrCreateDigitalCredential(int deviceId, int projectId, int appId,
                                               ActivationCallback callback) {
        executor.execute(() -> {
            try {
                String serverUrl = ApiClient.normalizeServerUrl(cache.getServerUrl());
                if (serverUrl.isEmpty()) {
                    mainHandler.post(() -> callback.onError("No server URL"));
                    return;
                }

                // Try to find existing type-3 credential
                String existingId = fetchDigitalCredentialId(serverUrl, deviceId, projectId, appId);
                if (existingId != null) {
                    JSONObject result = new JSONObject();
                    result.put("credential_id", existingId);
                    mainHandler.post(() -> callback.onSuccess(
                        new NfcActivationStep(result, new ArrayList<>(), existingId, null)));
                    return;
                }

                // Create new digital credential
                HttpUrl httpUrl = HttpUrl.parse(serverUrl + "/webapi/v1/door_lock/credential/create");
                if (httpUrl == null) {
                    mainHandler.post(() -> callback.onError("Invalid server URL"));
                    return;
                }
                HttpUrl.Builder urlBuilder = httpUrl.newBuilder()
                    .addQueryParameter("device_id", String.valueOf(deviceId))
                    .addQueryParameter("type", "3")
                    .addQueryParameter("value", String.valueOf((int) (Math.random() * 1000000)))
                    .addQueryParameter("user_id", cache.getUserId())
                    .addQueryParameter("identitycode", cache.getIdentityCode());

                String responseJson = api.executeBusinessPost(urlBuilder, cache.getSessionSecret(),
                    projectId > 0 ? projectId : ApiClient.PROJECT_ID,
                    appId > 0 ? appId : ApiClient.APP_ID);
                String dataStr = ApiClient.extractDataField(responseJson);

                JSONObject data = new JSONObject(dataStr);
                String credentialId = extractCredentialId(data);
                if (credentialId == null || credentialId.isEmpty()) {
                    mainHandler.post(() -> callback.onError("Server did not return digital credential ID"));
                    return;
                }

                JSONObject result = new JSONObject();
                result.put("credential_id", credentialId);
                mainHandler.post(() -> callback.onSuccess(
                    new NfcActivationStep(result, new ArrayList<>(), credentialId, null)));
            } catch (Exception e) {
                Log.e(TAG, "findOrCreateDigitalCredential error", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
            }
        });
    }

    private String fetchDigitalCredentialId(String serverUrl, int deviceId, int projectId, int appId) {
        try {
            HttpUrl httpUrl = HttpUrl.parse(serverUrl + "/webapi/v1/staff/door_lock/credentials");
            if (httpUrl == null) return null;

            HttpUrl.Builder urlBuilder = httpUrl.newBuilder()
                .addQueryParameter("device_id", String.valueOf(deviceId))
                .addQueryParameter("user_id", cache.getUserId())
                .addQueryParameter("identitycode", cache.getIdentityCode());

            String responseJson = api.executeBusinessRequest(urlBuilder, cache.getSessionSecret(),
                projectId > 0 ? projectId : ApiClient.PROJECT_ID,
                appId > 0 ? appId : ApiClient.APP_ID);
            String dataStr = ApiClient.extractDataField(responseJson);
            JSONObject data = new JSONObject(dataStr);

            JSONArray rows = data.optJSONArray("rows");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null && row.optInt("type", -1) == 3) {
                        Object idVal = row.opt("id");
                        if (idVal != null && !JSONObject.NULL.equals(idVal)) {
                            return String.valueOf(idVal);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchDigitalCredentialId error", e);
        }
        return null;
    }

    public void startNfcActivation(int deviceId, String credentialId, int projectId, int appId,
                                    ActivationCallback callback) {
        executor.execute(() -> {
            try {
                NfcActivationStep step = startNfcActivationSync(deviceId, credentialId, projectId, appId);
                mainHandler.post(() -> callback.onSuccess(step));
            } catch (Exception e) {
                Log.e(TAG, "startNfcActivation error", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
            }
        });
    }

    public void startBleActivation(int deviceId, String credentialId, int projectId, int appId,
                                    ActivationCallback callback) {
        executor.execute(() -> {
            try {
                NfcActivationStep step = startBleActivationSync(deviceId, credentialId, projectId, appId);
                mainHandler.post(() -> callback.onSuccess(step));
            } catch (Exception e) {
                Log.e(TAG, "startBleActivation error", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
            }
        });
    }

    public NfcActivationStep startNfcActivationSync(int deviceId, String credentialId,
                                                      int projectId, int appId) throws Exception {
        return startDigitalCredentialActivationSync(deviceId, credentialId, "nfc", "1", projectId, appId);
    }

    public NfcActivationStep startBleActivationSync(int deviceId, String credentialId,
                                                      int projectId, int appId) throws Exception {
        return startDigitalCredentialActivationSync(deviceId, credentialId, "ble", "112", projectId, appId);
    }

    private NfcActivationStep startDigitalCredentialActivationSync(int deviceId, String credentialId,
                                                                     String transport, String command,
                                                                     int projectId, int appId) throws Exception {
        String serverUrl = ApiClient.normalizeServerUrl(cache.getServerUrl());
        if (serverUrl.isEmpty()) throw new Exception("No server URL");

        HttpUrl httpUrl = HttpUrl.parse(serverUrl + "/webapi/v1/door_lock/command/create");
        if (httpUrl == null) throw new Exception("Invalid server URL");

        HttpUrl.Builder urlBuilder = httpUrl.newBuilder()
            .addQueryParameter("device_id", String.valueOf(deviceId))
            .addQueryParameter("command", command)
            .addQueryParameter("type", transport)
            .addQueryParameter("credential_id", credentialId)
            .addQueryParameter("user_id", cache.getUserId())
            .addQueryParameter("identitycode", cache.getIdentityCode());

        String responseJson = api.executeBusinessRequest(urlBuilder, cache.getSessionSecret(),
            projectId > 0 ? projectId : ApiClient.PROJECT_ID,
            appId > 0 ? appId : ApiClient.APP_ID);
        String dataStr = ApiClient.extractDataField(responseJson);

        return parseActivationStep(dataStr, credentialId);
    }

    public void submitActivationResponses(NfcActivationStep step, List<String> responses,
                                            String transport, int projectId, int appId,
                                            ActivationCallback callback) {
        executor.execute(() -> {
            try {
                NfcActivationStep nextStep = submitActivationResponsesSync(step, responses, transport,
                    projectId, appId);
                mainHandler.post(() -> callback.onSuccess(nextStep));
            } catch (Exception e) {
                Log.e(TAG, "submitActivationResponses error", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
            }
        });
    }

    public NfcActivationStep submitActivationResponsesSync(NfcActivationStep step, List<String> responses,
                                                             String transport, int projectId,
                                                             int appId) throws Exception {
        if (responses.isEmpty()) {
            throw new Exception("Door lock returned no activation response");
        }

        String serverUrl = ApiClient.normalizeServerUrl(cache.getServerUrl());
        if (serverUrl.isEmpty()) throw new Exception("No server URL");

        HttpUrl httpUrl = HttpUrl.parse(serverUrl + "/webapi/v1/door_lock/command/parse");
        if (httpUrl == null) throw new Exception("Invalid server URL");

        // Build params from step.requestData
        HttpUrl.Builder urlBuilder = httpUrl.newBuilder();
        JSONObject reqData = step.requestData;
        Iterator<String> keys = reqData.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object val = reqData.opt(key);
            if (val != null && !JSONObject.NULL.equals(val)) {
                urlBuilder.addQueryParameter(key, String.valueOf(val));
            }
        }
        urlBuilder.addQueryParameter("payload", joinStrings(responses, ","));
        urlBuilder.addQueryParameter("type", transport);
        urlBuilder.addQueryParameter("user_id", cache.getUserId());
        urlBuilder.addQueryParameter("identitycode", cache.getIdentityCode());

        String responseJson = api.executeBusinessPost(urlBuilder, cache.getSessionSecret(),
            projectId > 0 ? projectId : ApiClient.PROJECT_ID,
            appId > 0 ? appId : ApiClient.APP_ID);
        String dataStr = ApiClient.extractDataField(responseJson);

        return parseActivationStep(dataStr, step.credentialId);
    }

    private NfcActivationStep parseActivationStep(String dataStr, String fallbackCredentialId)
            throws JSONException {
        JSONObject root = new JSONObject(dataStr);

        String payload = root.optString("payload", "");
        List<String> packets = new ArrayList<>();
        if (!payload.isEmpty()) {
            for (String part : payload.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    packets.add(trimmed);
                }
            }
        }

        String credentialHex = normalizeCredentialHex(
            findString(root, "code", "credential", "chain_key", "chainKey", "secret_key"));
        String credentialId = findString(root, "credential_id", "credentialId", "id");
        if (credentialId.isEmpty()) credentialId = fallbackCredentialId;

        return new NfcActivationStep(root, packets, credentialId, credentialHex);
    }

    private static String findString(JSONObject obj, String... keys) {
        for (String key : keys) {
            String val = obj.optString(key, "");
            if (!val.isEmpty()) return val;
        }
        return "";
    }

    private static int findPositiveInt(JSONObject obj, String... keys) {
        for (String key : keys) {
            int val = obj.optInt(key, 0);
            if (val > 0) return val;
        }
        return 0;
    }

    private static String normalizeCredentialHex(String value) {
        if (value == null) return null;
        String upper = value.trim().toUpperCase();
        return upper.matches("^[0-9A-F]{64}$") ? upper : null;
    }

    private static String extractCredentialId(JSONObject data) {
        String id = data.optString("credential_id", "");
        if (id.isEmpty()) id = data.optString("credentialId", "");
        if (id.isEmpty()) id = data.optString("id", "");
        return id.isEmpty() ? null : id;
    }

    private static String joinStrings(List<String> list, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static int extractCredentialIdFromRows(JSONObject data) {
        try {
            JSONArray rows = data.optJSONArray("rows");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null && row.has("id")) {
                        Object idVal = row.opt("id");
                        if (idVal != null && !JSONObject.NULL.equals(idVal)) {
                            try { return Integer.parseInt(String.valueOf(idVal)); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
