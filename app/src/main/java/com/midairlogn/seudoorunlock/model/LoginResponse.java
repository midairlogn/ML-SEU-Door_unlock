package com.midairlogn.seudoorunlock.model;

import org.json.JSONException;
import org.json.JSONObject;

public class LoginResponse {

    public final UserInfo userInfo;
    public final String platformToken;
    public final ServerInfo serverInfo;

    public LoginResponse(UserInfo userInfo, String platformToken, ServerInfo serverInfo) {
        this.userInfo = userInfo;
        this.platformToken = platformToken;
        this.serverInfo = serverInfo;
    }

    public static LoginResponse fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);

        // Reference app expects flat keys: id, identity_code, server_addr, session_secret
        // Also handles nested user_info/server_info if present
        JSONObject userInfoJson = root.optJSONObject("user_info") != null
            ? root.getJSONObject("user_info") : root;
        JSONObject serverInfoJson = root.optJSONObject("server_info") != null
            ? root.getJSONObject("server_info") : root;

        UserInfo userInfo = new UserInfo(
            findString(userInfoJson, "id", "user_id", "userId", "uid"),
            findString(userInfoJson, "phone", "mobile", "phone_number"),
            findString(userInfoJson, "identity_code", "identitycode", "identityCode"),
            userInfoJson.optInt("isbind", 0),
            userInfoJson.optString("balance", "0")
        );

        ServerInfo serverInfo = new ServerInfo(
            findString(serverInfoJson, "server_addr", "serverAddr"),
            findString(serverInfoJson, "session_secret", "sessionSecret"),
            serverInfoJson.optString("appsecret", ""),
            findPositiveInt(serverInfoJson, "server_appid", "project_id", "projectId"),
            findPositiveInt(serverInfoJson, "server_id", "app_id", "appId")
        );

        String platformToken = findString(root, "platform_token", "platformToken");

        return new LoginResponse(userInfo, platformToken, serverInfo);
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

    public static class UserInfo {
        public final String id;
        public final String phone;
        public final String identityCode;
        public final int isBind;
        public final String balance;

        public UserInfo(String id, String phone, String identityCode, int isBind, String balance) {
            this.id = id;
            this.phone = phone;
            this.identityCode = identityCode;
            this.isBind = isBind;
            this.balance = balance;
        }
    }

    public static class ServerInfo {
        public final String serverAddr;
        public final String sessionSecret;
        public final String appSecret;
        public final int serverAppId;
        public final int serverId;
        public final int projectId;
        public final int appId;

        public ServerInfo(String serverAddr, String sessionSecret, String appSecret,
                          int projectId, int appId) {
            this.serverAddr = serverAddr;
            this.sessionSecret = sessionSecret;
            this.appSecret = appSecret;
            this.projectId = projectId;
            this.appId = appId;
            this.serverAppId = this.projectId;
            this.serverId = this.appId;
        }
    }
}
