package com.whxinna.userplatform.api;

import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ApiClient {

    private static final String TAG = "ZL_ApiClient";
    private static final String AUTH_BASE = "https://pm.whxinna.com";
    public static final int PROJECT_ID = 21048;
    public static final int APP_ID = 20104;
    private static final String AUTH_SIGN_SECRET = "6d5dbb85b949447a95ff8fda9a9b759b";

    private final OkHttpClient client;
    private static ApiClient instance;

    private ApiClient() {
        client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    }

    public static synchronized ApiClient getInstance() {
        if (instance == null) {
            instance = new ApiClient();
        }
        return instance;
    }

    public String getAuthBaseUrl() {
        return AUTH_BASE;
    }

    public String getBusinessUrl(String serverUrl) {
        return serverUrl;
    }

    public String generateNonce() {
        return generateNonce(32);
    }

    public String generateNonce(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public long getTimestamp() {
        return System.currentTimeMillis() / 1000;
    }

    public String signParams(HttpUrl.Builder urlBuilder) {
        return signParams(urlBuilder, AUTH_SIGN_SECRET);
    }

    public String signParams(HttpUrl.Builder urlBuilder, String secret) {
        try {
            StringBuilder sb = new StringBuilder();
            HttpUrl url = urlBuilder.build();
            java.util.List<String> sorted = new java.util.ArrayList<>(url.queryParameterNames());
            java.util.Collections.sort(sorted);

            for (String name : sorted) {
                if ("sign".equals(name)) continue;
                String val = url.queryParameter(name);
                if (val != null && !val.isEmpty()) {
                    sb.append(name).append("=").append(val).append("&");
                }
            }
            String raw = sb.toString();
            if (raw.endsWith("&")) raw = raw.substring(0, raw.length() - 1);
            raw += "&key=" + secret;

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02X", b & 0xFF));
            }
            return hex.toString();
        } catch (Exception e) {
            Log.e(TAG, "Sign failed", e);
            return "";
        }
    }

    public String signBusinessParams(HttpUrl.Builder urlBuilder, String sessionSecret) {
        return signParams(urlBuilder, sessionSecret);
    }

    public String executeRequest(Request request) throws IOException {
        Log.d(TAG, "Request: " + request.url());
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "Response code: " + response.code());
            return body;
        }
    }

    public String executeAuthRequest(HttpUrl.Builder urlBuilder) throws IOException {
        String nonce = generateNonce(32);
        long ts = getTimestamp();
        urlBuilder.addQueryParameter("timestamp", String.valueOf(ts));
        urlBuilder.addQueryParameter("noncestr", nonce);
        urlBuilder.addQueryParameter("sign", signParams(urlBuilder));

        Request request = new Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build();
        return executeRequest(request);
    }

    public String executeBusinessRequest(HttpUrl.Builder urlBuilder,
                                          String platformToken,
                                          String sessionSecret) throws IOException {
        String nonce = generateNonce(16);
        long ts = getTimestamp();
        urlBuilder.addQueryParameter("timestamp", String.valueOf(ts));
        urlBuilder.addQueryParameter("noncestr", nonce);
        urlBuilder.addQueryParameter("sign", signBusinessParams(urlBuilder, sessionSecret));

        Request.Builder reqBuilder = new Request.Builder()
            .url(urlBuilder.build())
            .get();
        if (platformToken != null && !platformToken.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + platformToken);
        }
        return executeRequest(reqBuilder.build());
    }

    public static String base64Decode(String input) {
        try {
            String normalized = input.trim()
                .replace('-', '+')
                .replace('_', '/');
            int padding = normalized.length() % 4;
            if (padding == 2) {
                normalized += "==";
            } else if (padding == 3) {
                normalized += "=";
            }
            byte[] decoded = Base64.decode(normalized, Base64.DEFAULT);
            return new String(decoded, "UTF-8");
        } catch (Exception e) {
            return input;
        }
    }

    public static String extractDataField(String responseJson) throws JSONException {
        JSONObject root = new JSONObject(responseJson);
        if (!isSuccess(root)) {
            String serverMessage = extractServerMessage(root);
            if (serverMessage != null && !serverMessage.isEmpty()) {
                throw new JSONException(serverMessage);
            }
            throw new JSONException("Server error");
        }
        if (!root.has("data")) {
            return responseJson;
        }
        Object data = root.get("data");
        return decodeData(data);
    }

    private static boolean isSuccess(JSONObject root) {
        if (root.has("success") && !root.optBoolean("success", true)) {
            return false;
        }
        if (root.has("result") && !root.optBoolean("result", true)) {
            return false;
        }
        Object codeValue = root.opt("code");
        if (codeValue == null) codeValue = root.opt("status");
        if (codeValue == null) codeValue = root.opt("errno");
        if (codeValue == null) return true;
        String code = String.valueOf(codeValue);
        return "0".equals(code) || "1".equals(code) || "200".equals(code);
    }

    private static String extractServerMessage(JSONObject root) {
        String msg = root.optString("err_msg", "");
        if (msg.isEmpty()) msg = root.optString("msg", "");
        if (msg.isEmpty()) msg = root.optString("message", "");
        return msg.isEmpty() ? null : msg;
    }

    private static String decodeData(Object raw) {
        if (raw instanceof JSONObject || raw instanceof org.json.JSONArray) {
            return raw.toString();
        }
        String value = String.valueOf(raw).trim();
        if (value.startsWith("{") || value.startsWith("[")) {
            return value;
        }
        String decoded = base64Decode(value);
        if (decoded != null && (decoded.startsWith("{") || decoded.startsWith("["))) {
            return decoded;
        }
        return value;
    }

    public static boolean isCaptchaRequired(String responseJson) {
        try {
            JSONObject root = new JSONObject(responseJson);
            String msg = extractServerMessage(root);
            if (msg == null) return false;
            return msg.contains("本次登录需要进行验证")
                || msg.contains("CAPTCHA_REQUIRED")
                || msg.contains("验证码输入错误");
        } catch (Exception e) {
            return false;
        }
    }
}
