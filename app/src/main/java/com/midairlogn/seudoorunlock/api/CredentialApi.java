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
    private static final Object SESSION_REFRESH_LOCK = new Object();

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
    private final AuthApi authApi;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public CredentialApi(CredentialCache cache) {
        this.api = ApiClient.getInstance();
        this.cache = cache;
        this.authApi = new AuthApi(cache);
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

                String dataStr = executeBusinessGetData(urlBuilder, projectId, appId);
                JSONObject detailJson = new JSONObject(dataStr);
                DoorLockInfo info = DoorLockInfo.fromJson(dataStr);

                String deviceId = info.doorLock != null ? "" + info.doorLock.deviceId : "";
                String credential = info.doorLock != null ? info.doorLock.credential : "";
                int credentialId = info.doorLock != null ? info.doorLock.credentialId : 0;
                String bleMac = info.doorLock != null ? info.doorLock.bleMac : "";

                boolean credentialMissingOrInvalid = credential.isEmpty()
                    || !credential.matches("^[0-9A-Fa-f]{64}$");
                if (deviceId.isEmpty() || credentialMissingOrInvalid) {
                    Log.d(TAG, "Device ID or credential missing, fetching staff credentials endpoint");
                    CredentialRecord staffRecord = fetchStaffCredentialRecord(serverUrl, projectId, appId);
                    if (deviceId.isEmpty() && !staffRecord.deviceId.isEmpty()) deviceId = staffRecord.deviceId;
                    if (credentialId == 0 && staffRecord.credentialId != 0) credentialId = staffRecord.credentialId;
                    if (bleMac.isEmpty() && !staffRecord.bleMac.isEmpty()) bleMac = staffRecord.bleMac;
                    if (!staffRecord.credential.isEmpty()) credential = staffRecord.credential;
                }

                if (deviceId.isEmpty()) {
                    String roomId = findString(detailJson, "room_id", "roomId");
                    if (!roomId.isEmpty()) {
                        Log.d(TAG, "Device ID missing, fetching door lock list by room_id");
                        CredentialRecord roomLock = fetchDoorLockListRecord(serverUrl, roomId, projectId, appId);
                        if (!roomLock.deviceId.isEmpty()) deviceId = roomLock.deviceId;
                        if (credentialId == 0 && roomLock.credentialId != 0) credentialId = roomLock.credentialId;
                        if (bleMac.isEmpty() && !roomLock.bleMac.isEmpty()) bleMac = roomLock.bleMac;
                        if (!roomLock.credential.isEmpty()) credential = roomLock.credential;
                    }
                }

                if (deviceId.isEmpty()) {
                    Log.d(TAG, "Device ID still missing, fetching all door-lock credentials");
                    CredentialRecord lockRecord = fetchDoorLockCredentialRecord(serverUrl, "", projectId, appId);
                    if (!lockRecord.deviceId.isEmpty()) deviceId = lockRecord.deviceId;
                    if (credentialId == 0 && lockRecord.credentialId != 0) credentialId = lockRecord.credentialId;
                    if (bleMac.isEmpty() && !lockRecord.bleMac.isEmpty()) bleMac = lockRecord.bleMac;
                    if (!lockRecord.credential.isEmpty()) credential = lockRecord.credential;
                }

                if (deviceId.isEmpty()) {
                    mainHandler.post(() -> callback.onError("Server returned missing device_id"));
                    return;
                }

                int deviceIdInt;
                try {
                    deviceIdInt = Integer.parseInt(deviceId);
                } catch (NumberFormatException e) {
                    final String invalidDeviceId = deviceId;
                    mainHandler.post(() -> callback.onError("Invalid device_id: " + invalidDeviceId));
                    return;
                }

                int[] resolvedIds = resolveProjectIdsForDevice(deviceIdInt, projectId, appId);
                projectId = resolvedIds[0];
                appId = resolvedIds[1];

                if (credential.isEmpty() || !credential.matches("^[0-9A-Fa-f]{64}$")) {
                    Log.d(TAG, "Credential still missing, fetching door-lock credentials endpoint");
                    CredentialRecord lockRecord = fetchDoorLockCredentialRecord(serverUrl, deviceId, projectId, appId);
                    if (credentialId == 0 && lockRecord.credentialId != 0) credentialId = lockRecord.credentialId;
                    if (bleMac.isEmpty() && !lockRecord.bleMac.isEmpty()) bleMac = lockRecord.bleMac;
                    if (!lockRecord.credential.isEmpty()) {
                        credential = lockRecord.credential;
                    }
                }

                String normalizedCredential = credential.toUpperCase();
                if (!normalizedCredential.matches("^[0-9A-F]{64}$")) {
                    Log.d(TAG, "No offline credential returned; caching activation-pending lock");
                    normalizedCredential = "";
                }

                cache.saveDoorLock(deviceIdInt, bleMac, normalizedCredential, credentialId,
                    projectId, appId);

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

    private CredentialRecord fetchStaffCredentialRecord(String serverUrl, int projectId, int appId)
            throws Exception {
        try {
            HttpUrl credUrl = HttpUrl.parse(serverUrl + "/webapi/v1/staff/credentials");
            if (credUrl == null) return CredentialRecord.EMPTY;

            HttpUrl.Builder credBuilder = credUrl.newBuilder()
                .addQueryParameter("user_id", cache.getUserId())
                .addQueryParameter("identitycode", cache.getIdentityCode());

            return parseCredentialRecord(executeBusinessGetData(credBuilder, projectId, appId));
        } catch (Exception e) {
            if (isApiSignError(e)) throw e;
            Log.w(TAG, "Failed to fetch staff credentials", e);
            return CredentialRecord.EMPTY;
        }
    }

    private CredentialRecord fetchDoorLockCredentialRecord(String serverUrl, String deviceId,
                                                            int projectId, int appId) throws Exception {
        try {
            HttpUrl credUrl = HttpUrl.parse(serverUrl + "/webapi/v1/staff/door_lock/credentials");
            if (credUrl == null) return CredentialRecord.EMPTY;

            HttpUrl.Builder credBuilder = credUrl.newBuilder()
                .addQueryParameter("user_id", cache.getUserId())
                .addQueryParameter("identitycode", cache.getIdentityCode());
            if (deviceId != null && !deviceId.isEmpty()) {
                credBuilder.addQueryParameter("device_id", deviceId);
            }

            return parseCredentialRecord(executeBusinessGetData(credBuilder, projectId, appId));
        } catch (Exception e) {
            if (isApiSignError(e)) throw e;
            Log.w(TAG, "Failed to fetch credential from credentials endpoint", e);
            return CredentialRecord.EMPTY;
        }
    }

    private CredentialRecord fetchDoorLockListRecord(String serverUrl, String roomId,
                                                      int projectId, int appId) throws Exception {
        try {
            HttpUrl lockUrl = HttpUrl.parse(serverUrl + "/webapi/v1/door_lock/list");
            if (lockUrl == null) return CredentialRecord.EMPTY;

            HttpUrl.Builder lockBuilder = lockUrl.newBuilder()
                .addQueryParameter("room_id", roomId)
                .addQueryParameter("user_id", cache.getUserId())
                .addQueryParameter("identitycode", cache.getIdentityCode());

            return parseCredentialRecord(executeBusinessGetData(lockBuilder, projectId, appId));
        } catch (Exception e) {
            if (isApiSignError(e)) throw e;
            Log.w(TAG, "Failed to fetch door lock list", e);
            return CredentialRecord.EMPTY;
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

                int cachedDeviceId = cache.getDeviceId();
                if (cachedDeviceId > 0) {
                    int[] resolvedIds = resolveProjectIdsForDevice(cachedDeviceId, projectId, appId);
                    projectId = resolvedIds[0];
                    appId = resolvedIds[1];
                }

                String dataStr = fetchCredentialData(serverUrl, projectId, appId);
                CredentialRecord record = parseCredentialRecord(dataStr);

                String credential = normalizeCredentialHex(record.credential);
                int newCredentialId = record.credentialId != 0 ? record.credentialId : credentialId;
                int deviceId = cache.getDeviceId();
                if (!record.deviceId.isEmpty()) {
                    try { deviceId = Integer.parseInt(record.deviceId); }
                    catch (NumberFormatException ignored) {}
                }
                String bleMac = !record.bleMac.isEmpty() ? record.bleMac : cache.getBleMac();

                if (credential != null) {
                    int[] resolvedIds = resolveProjectIdsForDevice(deviceId, projectId, appId);
                    cache.saveDoorLock(deviceId, bleMac, credential,
                        newCredentialId, resolvedIds[0], resolvedIds[1]);
                }

                DoorLockInfo info = DoorLockInfo.fromJson(dataStr);
                mainHandler.post(() -> callback.onSuccess(info));

            } catch (Exception e) {
                Log.e(TAG, "Credential sync error", e);
                String message = e.getMessage();
                if (message != null && message.contains("api_sign_error")) {
                    mainHandler.post(() -> callback.onError("Session expired, please sign in again"));
                } else {
                    mainHandler.post(() -> callback.onError("Error: " + message));
                }
            }
        });
    }

    private String fetchCredentialData(String serverUrl, int projectId, int appId) throws Exception {
        HttpUrl httpUrl = HttpUrl.parse(serverUrl + "/webapi/v1/staff/door_lock/credentials");
        if (httpUrl == null) throw new IOException("Invalid server URL");

        HttpUrl.Builder urlBuilder = httpUrl.newBuilder()
            .addQueryParameter("device_id", "" + cache.getDeviceId())
            .addQueryParameter("user_id", cache.getUserId())
            .addQueryParameter("identitycode", cache.getIdentityCode());
        return executeBusinessGetData(urlBuilder, projectId, appId);
    }

    private String executeBusinessGetData(HttpUrl.Builder baseBuilder, int projectId, int appId)
            throws Exception {
        HttpUrl baseUrl = baseBuilder.build();
        for (int attempt = 0; attempt < 2; attempt++) {
            String rejectedSecret = cache.getSessionSecret();
            try {
                String responseJson = api.executeBusinessRequest(baseUrl.newBuilder(), rejectedSecret,
                    projectId > 0 ? projectId : ApiClient.PROJECT_ID,
                    appId > 0 ? appId : ApiClient.APP_ID);
                return ApiClient.extractDataField(responseJson);
            } catch (JSONException e) {
                if (attempt > 0 || !isApiSignError(e)) throw e;
                Log.w(TAG, "Session secret rejected, attempting silent re-login");
                if (!reloginSilently(rejectedSecret)) throw e;
                if (cache.getDeviceId() > 0) {
                    int[] resolvedIds = resolveProjectIdsForDevice(cache.getDeviceId(),
                        cache.getProjectId(), cache.getAppId());
                    projectId = resolvedIds[0];
                    appId = resolvedIds[1];
                }
            }
        }
        throw new IllegalStateException("Business request retry exhausted");
    }

    private String executeBusinessPostData(HttpUrl.Builder baseBuilder, int projectId, int appId)
            throws Exception {
        HttpUrl baseUrl = baseBuilder.build();
        for (int attempt = 0; attempt < 2; attempt++) {
            String rejectedSecret = cache.getSessionSecret();
            try {
                String responseJson = api.executeBusinessPost(baseUrl.newBuilder(), rejectedSecret,
                    projectId > 0 ? projectId : ApiClient.PROJECT_ID,
                    appId > 0 ? appId : ApiClient.APP_ID);
                return ApiClient.extractDataField(responseJson);
            } catch (JSONException e) {
                if (attempt > 0 || !isApiSignError(e)) throw e;
                Log.w(TAG, "Session secret rejected, attempting silent re-login");
                if (!reloginSilently(rejectedSecret)) throw e;
                if (cache.getDeviceId() > 0) {
                    int[] resolvedIds = resolveProjectIdsForDevice(cache.getDeviceId(),
                        cache.getProjectId(), cache.getAppId());
                    projectId = resolvedIds[0];
                    appId = resolvedIds[1];
                }
            }
        }
        throw new IllegalStateException("Business request retry exhausted");
    }

    private boolean isApiSignError(Throwable error) {
        String message = error.getMessage();
        return message != null && message.contains("api_sign_error");
    }

    /** Returns true if a silent re-login succeeded or another thread already refreshed the secret. */
    private boolean reloginSilently(String rejectedSecret) {
        synchronized (SESSION_REFRESH_LOCK) {
            String currentSecret = cache.getSessionSecret();
            if (currentSecret != null && !currentSecret.isEmpty()
                    && !currentSecret.equals(rejectedSecret)) {
                return true;
            }

            String phone = cache.getPhone();
            String password = cache.getPassword();
            if (phone == null || phone.isEmpty() || password == null || password.isEmpty()) {
                Log.w(TAG, "No stored credentials, cannot re-login silently");
                return false;
            }
            try {
                authApi.loginSync(phone, password, null);
                Log.i(TAG, "Silent re-login succeeded, session secret refreshed");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Silent re-login failed: " + e.getMessage());
                return false;
            }
        }
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
                int[] ids = fetchProjectIdsByDeviceIdSync(deviceId);
                int projectId = ids[0];
                int appId = ids[1];
                persistProjectIds(projectId, appId);

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

                if (cache.getCredentialId() > 0) {
                    String credentialId = String.valueOf(cache.getCredentialId());
                    JSONObject result = new JSONObject();
                    result.put("credential_id", credentialId);
                    mainHandler.post(() -> callback.onSuccess(
                        new NfcActivationStep(result, new ArrayList<>(), credentialId, null)));
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
                    .addQueryParameter("value", String.format(java.util.Locale.US, "%06d", (int) (Math.random() * 1000000)))
                    .addQueryParameter("user_id", cache.getUserId())
                    .addQueryParameter("identitycode", cache.getIdentityCode());

                String dataStr = executeBusinessPostData(urlBuilder, projectId, appId);

                Object data = parseJsonValue(dataStr);
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

    private String fetchDigitalCredentialId(String serverUrl, int deviceId, int projectId, int appId)
            throws Exception {
        try {
            HttpUrl httpUrl = HttpUrl.parse(serverUrl + "/webapi/v1/staff/door_lock/credentials");
            if (httpUrl == null) return null;

            HttpUrl.Builder urlBuilder = httpUrl.newBuilder()
                .addQueryParameter("device_id", String.valueOf(deviceId))
                .addQueryParameter("user_id", cache.getUserId())
                .addQueryParameter("identitycode", cache.getIdentityCode());

            String dataStr = executeBusinessGetData(urlBuilder, projectId, appId);
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
            if (isApiSignError(e)) throw e;
            Log.w(TAG, "fetchDigitalCredentialId error", e);
        }
        return null;
    }

    private int[] resolveProjectIdsForDevice(int deviceId, int projectId, int appId) {
        int resolvedProjectId = projectId;
        int resolvedAppId = appId;

        if (deviceId > 0 && shouldLookupProjectIds(projectId, appId)) {
            try {
                int[] ids = fetchProjectIdsByDeviceIdSync(deviceId);
                if (ids[0] > 0) resolvedProjectId = ids[0];
                if (ids[1] > 0) resolvedAppId = ids[1];
                persistProjectIds(resolvedProjectId, resolvedAppId);
            } catch (Exception e) {
                Log.w(TAG, "Project lookup failed for device_id=" + deviceId, e);
            }
        }

        if (resolvedProjectId <= 0) resolvedProjectId = ApiClient.PROJECT_ID;
        if (resolvedAppId <= 0) resolvedAppId = ApiClient.APP_ID;
        return new int[]{resolvedProjectId, resolvedAppId};
    }

    private boolean shouldLookupProjectIds(int projectId, int appId) {
        return projectId <= 0 || appId <= 0
            || (projectId == ApiClient.PROJECT_ID && appId == ApiClient.APP_ID);
    }

    private int[] fetchProjectIdsByDeviceIdSync(int deviceId) throws Exception {
        HttpUrl httpUrl = HttpUrl.parse(api.getAuthBaseUrl() + "/webapi/project/get_by_device_id");
        if (httpUrl == null) throw new Exception("Invalid auth URL");

        HttpUrl.Builder urlBuilder = httpUrl.newBuilder()
            .addQueryParameter("device_id", String.valueOf(deviceId));

        String responseJson = api.executeAuthRequest(urlBuilder, false);
        String dataStr = ApiClient.extractDataField(responseJson);
        JSONObject data = new JSONObject(dataStr);
        return new int[]{
            findPositiveInt(data, "server_appid", "project_id", "projectId"),
            findPositiveInt(data, "server_id", "app_id", "appId")
        };
    }

    private void persistProjectIds(int projectId, int appId) {
        if (projectId <= 0 && appId <= 0) return;

        int cachedProjectId = cache.getProjectId();
        int cachedAppId = cache.getAppId();
        int nextProjectId = projectId > 0 ? projectId : cachedProjectId;
        int nextAppId = appId > 0 ? appId : cachedAppId;
        if (nextProjectId == cachedProjectId && nextAppId == cachedAppId) return;

        cache.saveSession(cache.getPhone(), cache.getPassword(), cache.getUserId(),
            cache.getIdentityCode(), cache.getPlatformToken(), cache.getSessionSecret(),
            cache.getServerUrl(), nextProjectId, nextAppId);
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

        String dataStr = executeBusinessGetData(urlBuilder, projectId, appId);

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
            if (val != null && !JSONObject.NULL.equals(val)
                    && !"payload".equals(key)
                    && !"type".equals(key)
                    && !"user_id".equals(key)
                    && !"identitycode".equals(key)) {
                urlBuilder.addQueryParameter(key, String.valueOf(val));
            }
        }
        urlBuilder.addQueryParameter("payload", joinStrings(responses, ","));
        urlBuilder.addQueryParameter("type", transport);
        urlBuilder.addQueryParameter("user_id", cache.getUserId());
        urlBuilder.addQueryParameter("identitycode", cache.getIdentityCode());

        String dataStr = executeBusinessPostData(urlBuilder, projectId, appId);

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

    private static Object parseJsonValue(String dataStr) throws JSONException {
        String trimmed = dataStr.trim();
        if (trimmed.startsWith("[")) return new JSONArray(trimmed);
        if (trimmed.startsWith("{")) return new JSONObject(trimmed);
        return trimmed;
    }

    private static String findString(Object node, String... keys) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (String key : keys) {
                Object val = obj.opt(key);
                if (val != null && !JSONObject.NULL.equals(val)) {
                    String str = String.valueOf(val);
                    if (!str.isEmpty()) return str;
                }
            }
            Iterator<String> names = obj.keys();
            while (names.hasNext()) {
                Object child = obj.opt(names.next());
                if (child != null && !JSONObject.NULL.equals(child)) {
                    String result = findString(child, keys);
                    if (!result.isEmpty()) return result;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String result = findString(array.opt(i), keys);
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }

    private static int findPositiveInt(Object node, String... keys) {
        String value = findString(node, keys);
        if (!value.isEmpty()) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static String normalizeCredentialHex(String value) {
        if (value == null) return null;
        String upper = value.trim().toUpperCase();
        return upper.matches("^[0-9A-F]{64}$") ? upper : null;
    }

    private static CredentialRecord parseCredentialRecord(String dataStr) throws JSONException {
        String trimmed = dataStr.trim();
        JSONObject record;
        if (trimmed.startsWith("[")) {
            JSONArray array = new JSONArray(trimmed);
            record = array.length() > 0 ? array.optJSONObject(0) : null;
        } else {
            JSONObject root = new JSONObject(trimmed);
            JSONArray rows = root.optJSONArray("rows");
            JSONArray data = root.optJSONArray("data");
            if (rows != null && rows.length() > 0) {
                record = rows.optJSONObject(0);
            } else if (data != null && data.length() > 0) {
                record = data.optJSONObject(0);
            } else {
                record = root;
            }
        }
        if (record == null) return CredentialRecord.EMPTY;

        String credential = findString(record, "credential", "chain_key", "chainKey");
        int credentialId = findPositiveInt(record, "credential_id", "credentialId", "id");
        return new CredentialRecord(
            findString(record, "device_id", "deviceId"),
            findString(record, "ble_mac", "bleMac"),
            credential != null ? credential.toUpperCase() : "",
            credentialId
        );
    }

    private static class CredentialRecord {
        static final CredentialRecord EMPTY = new CredentialRecord("", "", "", 0);

        final String deviceId;
        final String bleMac;
        final String credential;
        final int credentialId;

        CredentialRecord(String deviceId, String bleMac, String credential, int credentialId) {
            this.deviceId = deviceId != null ? deviceId : "";
            this.bleMac = bleMac != null ? bleMac : "";
            this.credential = credential != null ? credential : "";
            this.credentialId = credentialId;
        }
    }

    private static String extractCredentialId(Object data) {
        if (!(data instanceof JSONObject) && !(data instanceof JSONArray)) {
            String id = String.valueOf(data).trim();
            return id.isEmpty() ? null : id;
        }
        String id = findString(data, "credential_id", "credentialId", "id");
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
