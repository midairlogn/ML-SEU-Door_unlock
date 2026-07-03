package com.whxinna.userplatform.model;

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
        JSONObject userInfoJson = root.getJSONObject("user_info");
        String platformToken = root.getString("platform_token");
        JSONObject serverInfoJson = root.getJSONObject("server_info");

        UserInfo userInfo = new UserInfo(
            userInfoJson.getString("id"),
            userInfoJson.getString("phone"),
            userInfoJson.optString("identity_code", ""),
            userInfoJson.optInt("isbind", 0),
            userInfoJson.optString("balance", "0")
        );

        ServerInfo serverInfo = new ServerInfo(
            serverInfoJson.getString("server_addr"),
            serverInfoJson.getString("session_secret"),
            serverInfoJson.optString("appsecret", ""),
            serverInfoJson.optInt("server_appid", 21048),
            serverInfoJson.optInt("server_id", 20104)
        );

        return new LoginResponse(userInfo, platformToken, serverInfo);
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

        public ServerInfo(String serverAddr, String sessionSecret, String appSecret, int serverAppId, int serverId) {
            this.serverAddr = serverAddr;
            this.sessionSecret = sessionSecret;
            this.appSecret = appSecret;
            this.serverAppId = serverAppId;
            this.serverId = serverId;
        }
    }
}
