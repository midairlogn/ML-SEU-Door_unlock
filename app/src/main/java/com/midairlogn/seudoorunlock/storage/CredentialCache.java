package com.midairlogn.seudoorunlock.storage;

import android.content.Context;

public class CredentialCache {

    private static final String KEY_PHONE = "phone";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_IDENTITY_CODE = "identity_code";
    private static final String KEY_PLATFORM_TOKEN = "platform_token";
    private static final String KEY_SESSION_SECRET = "session_secret";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_AUTH_SERVER_URL = "auth_server_url";
    private static final String KEY_PROJECT_ID = "project_id";
    private static final String KEY_APP_ID = "app_id";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_BLE_MAC = "ble_mac";
    private static final String KEY_CREDENTIAL_HEX = "credential_hex";
    private static final String KEY_CREDENTIAL_ID = "credential_id";
    private static final String KEY_BUILDING_NAME = "building_name";
    private static final String KEY_BATTERY_LEVEL = "battery_level";
    private static final String KEY_UPDATED_AT = "updated_at";

    private final SecurePrefs securePrefs;

    private static CredentialCache instance;

    public static synchronized CredentialCache getInstance(Context context) {
        if (instance == null) {
            instance = new CredentialCache(context);
        }
        return instance;
    }

    private CredentialCache(Context context) {
        securePrefs = SecurePrefs.getInstance(context);
    }

    public void saveSession(String phone, String password, String userId,
                            String identityCode, String platformToken,
                            String sessionSecret, String serverUrl) {
        saveSession(phone, password, userId, identityCode, platformToken,
                    sessionSecret, serverUrl, 0, 0);
    }

    public void saveSession(String phone, String password, String userId,
                            String identityCode, String platformToken,
                            String sessionSecret, String serverUrl,
                            int projectId, int appId) {
        securePrefs.putString(KEY_PHONE, phone);
        securePrefs.putString(KEY_PASSWORD, password);
        securePrefs.putString(KEY_USER_ID, userId);
        securePrefs.putString(KEY_IDENTITY_CODE, identityCode);
        securePrefs.putString(KEY_PLATFORM_TOKEN, platformToken);
        securePrefs.putString(KEY_SESSION_SECRET, sessionSecret);
        securePrefs.putString(KEY_SERVER_URL, serverUrl);
        securePrefs.putString(KEY_AUTH_SERVER_URL, "https://pm.whxinna.com");
        if (projectId > 0) securePrefs.putString(KEY_PROJECT_ID, String.valueOf(projectId));
        if (appId > 0) securePrefs.putString(KEY_APP_ID, String.valueOf(appId));
    }

    public void saveDoorLock(int deviceId, String bleMac, String credentialHex, int credentialId) {
        securePrefs.putString(KEY_DEVICE_ID, String.valueOf(deviceId));
        securePrefs.putString(KEY_BLE_MAC, bleMac);
        securePrefs.putString(KEY_CREDENTIAL_HEX, credentialHex);
        securePrefs.putString(KEY_CREDENTIAL_ID, String.valueOf(credentialId));
    }

    public void saveDoorLock(int deviceId, String bleMac, String credentialHex, int credentialId,
                             int projectId, int appId) {
        saveDoorLock(deviceId, bleMac, credentialHex, credentialId);
        if (projectId > 0) securePrefs.putString(KEY_PROJECT_ID, String.valueOf(projectId));
        if (appId > 0) securePrefs.putString(KEY_APP_ID, String.valueOf(appId));
    }

    public void saveAccommodationInfo(String buildingName, double batteryLevel) {
        securePrefs.putString(KEY_BUILDING_NAME, buildingName);
        securePrefs.putString(KEY_BATTERY_LEVEL, String.valueOf(batteryLevel));
    }

    public void updateTimestamp() {
        securePrefs.putString(KEY_UPDATED_AT, String.valueOf(System.currentTimeMillis()));
    }

    public String getPhone() { return securePrefs.getString(KEY_PHONE, ""); }
    public String getPassword() { return securePrefs.getString(KEY_PASSWORD, ""); }
    public String getUserId() { return securePrefs.getString(KEY_USER_ID, ""); }
    public String getIdentityCode() { return securePrefs.getString(KEY_IDENTITY_CODE, ""); }
    public String getPlatformToken() { return securePrefs.getString(KEY_PLATFORM_TOKEN, ""); }
    public String getSessionSecret() { return securePrefs.getString(KEY_SESSION_SECRET, ""); }
    public String getServerUrl() { return securePrefs.getString(KEY_SERVER_URL, ""); }
    public String getAuthServerUrl() { return securePrefs.getString(KEY_AUTH_SERVER_URL, "https://pm.whxinna.com"); }
    public String getBleMac() { return securePrefs.getString(KEY_BLE_MAC, ""); }
    public String getCredentialHex() { return securePrefs.getString(KEY_CREDENTIAL_HEX, ""); }
    public String getBuildingName() { return securePrefs.getString(KEY_BUILDING_NAME, ""); }

    public int getDeviceId() {
        String val = securePrefs.getString(KEY_DEVICE_ID, "0");
        try { return Integer.parseInt(val); } catch (Exception e) { return 0; }
    }

    public int getCredentialId() {
        String val = securePrefs.getString(KEY_CREDENTIAL_ID, "0");
        try { return Integer.parseInt(val); } catch (Exception e) { return 0; }
    }

    public int getProjectId() {
        String val = securePrefs.getString(KEY_PROJECT_ID, "0");
        try { return Integer.parseInt(val); } catch (Exception e) { return 0; }
    }

    public int getAppId() {
        String val = securePrefs.getString(KEY_APP_ID, "0");
        try { return Integer.parseInt(val); } catch (Exception e) { return 0; }
    }

    public double getBatteryLevel() {
        String val = securePrefs.getString(KEY_BATTERY_LEVEL, "100");
        try { return Double.parseDouble(val); } catch (Exception e) { return 100; }
    }

    public long getUpdatedAt() {
        String val = securePrefs.getString(KEY_UPDATED_AT, "0");
        try { return Long.parseLong(val); } catch (Exception e) { return 0; }
    }

    public boolean hasSession() {
        return !getUserId().isEmpty();
    }

    public boolean hasSessionFast() {
        return securePrefs.contains(KEY_USER_ID);
    }

    public boolean hasDoorLock() {
        return getDeviceId() != 0;
    }

    public boolean hasOfflineCredential() {
        String hex = getCredentialHex();
        return hex != null && hex.matches("^[0-9A-Fa-f]{64}$");
    }

    public boolean requiresDigitalCredentialActivation() {
        return getDeviceId() > 0 && !hasOfflineCredential() && !getSessionSecret().isEmpty();
    }

    public boolean needsRefresh() {
        long elapsed = System.currentTimeMillis() - getUpdatedAt();
        return elapsed > 24 * 60 * 60 * 1000; // 24 hours
    }

    public void clear() {
        securePrefs.remove(KEY_PHONE);
        securePrefs.remove(KEY_PASSWORD);
        securePrefs.remove(KEY_USER_ID);
        securePrefs.remove(KEY_IDENTITY_CODE);
        securePrefs.remove(KEY_PLATFORM_TOKEN);
        securePrefs.remove(KEY_SESSION_SECRET);
        securePrefs.remove(KEY_SERVER_URL);
        securePrefs.remove(KEY_AUTH_SERVER_URL);
        securePrefs.remove(KEY_PROJECT_ID);
        securePrefs.remove(KEY_APP_ID);
        securePrefs.remove(KEY_DEVICE_ID);
        securePrefs.remove(KEY_BLE_MAC);
        securePrefs.remove(KEY_CREDENTIAL_HEX);
        securePrefs.remove(KEY_CREDENTIAL_ID);
        securePrefs.remove(KEY_BUILDING_NAME);
        securePrefs.remove(KEY_BATTERY_LEVEL);
        securePrefs.remove(KEY_UPDATED_AT);
    }
}
