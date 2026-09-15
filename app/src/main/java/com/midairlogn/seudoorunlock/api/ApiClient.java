package com.midairlogn.seudoorunlock.api;

import android.util.Base64;
import android.util.Log;
import com.midairlogn.seudoorunlock.crypto.CryptoUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
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
        return CryptoUtils.generateNonce(length);
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
            raw = raw.replace("\"", "").replace(" ", "");
            raw += "&key=" + secret;

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return CryptoUtils.bytesToHex(digest);
        } catch (Exception e) {
            Log.e(TAG, "Sign failed", e);
            return "";
        }
    }

    public String executeRequest(Request request) throws IOException {
        Log.d(TAG, "Request: " + describeRequest(request));
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            Log.d(TAG, "Response code: " + response.code());
            return body;
        }
    }

    private static String describeRequest(Request request) {
        HttpUrl url = request.url();
        StringBuilder sb = new StringBuilder(request.method())
            .append(' ')
            .append(url.scheme())
            .append("://")
            .append(url.host());
        if (url.port() != HttpUrl.defaultPort(url.scheme())) {
            sb.append(':').append(url.port());
        }
        sb.append(url.encodedPath());
        if (url.querySize() > 0) {
            sb.append('?');
            for (int i = 0; i < url.querySize(); i++) {
                if (i > 0) sb.append('&');
                sb.append(url.queryParameterName(i)).append("=<redacted>");
            }
        }
        return sb.toString();
    }

    /** Drops empty query params so the signed set always equals the sent set. */
    private static HttpUrl.Builder withoutEmptyParams(HttpUrl.Builder builder) {
        HttpUrl url = builder.build();
        HttpUrl.Builder cleaned = url.newBuilder();
        for (String name : url.queryParameterNames()) {
            String value = url.queryParameter(name);
            if (value == null || value.isEmpty()) {
                cleaned.removeAllQueryParameters(name);
            }
        }
        return cleaned;
    }

    public String executeAuthRequest(HttpUrl.Builder urlBuilder) throws IOException {
        return executeAuthRequest(urlBuilder, false);
    }

    public String executeAuthRequest(HttpUrl.Builder urlBuilder, boolean includeProjectIds) throws IOException {
        if (includeProjectIds) {
            urlBuilder.addQueryParameter("pid", "" + PROJECT_ID);
            urlBuilder.addQueryParameter("appid", "" + APP_ID);
        }
        String nonce = generateNonce(32);
        long ts = getTimestamp();
        urlBuilder.addQueryParameter("timestamp", "" + ts);
        urlBuilder.addQueryParameter("noncestr", nonce);
        HttpUrl.Builder signedBuilder = withoutEmptyParams(urlBuilder);
        signedBuilder.addQueryParameter("sign", signParams(signedBuilder));

        Request request = new Request.Builder()
            .url(signedBuilder.build())
            .get()
            .build();
        return executeRequest(request);
    }

    public String executeBusinessRequest(HttpUrl.Builder urlBuilder,
                                          String sessionSecret) throws IOException {
        return executeBusinessRequest(urlBuilder, sessionSecret, PROJECT_ID, APP_ID);
    }

    public String executeBusinessRequest(HttpUrl.Builder urlBuilder,
                                          String sessionSecret,
                                          int projectId, int appId) throws IOException {
        urlBuilder.addQueryParameter("pid", "" + projectId);
        urlBuilder.addQueryParameter("appid", "" + appId);
        String nonce = generateNonce(32);
        long ts = getTimestamp();
        urlBuilder.addQueryParameter("timestamp", "" + ts);
        urlBuilder.addQueryParameter("noncestr", nonce);
        HttpUrl.Builder signedBuilder = withoutEmptyParams(urlBuilder);
        signedBuilder.addQueryParameter("sign", signParams(signedBuilder, sessionSecret));

        Request request = new Request.Builder()
            .url(signedBuilder.build())
            .get()
            .build();
        return executeRequest(request);
    }

    public String executeBusinessPost(HttpUrl.Builder urlBuilder,
                                       String sessionSecret,
                                       int projectId, int appId) throws IOException {
        HttpUrl url = urlBuilder.build();

        FormBody.Builder formBuilder = new FormBody.Builder();
        for (String name : url.queryParameterNames()) {
            List<String> values = url.queryParameterValues(name);
            String value = values.isEmpty() ? null : values.get(values.size() - 1);
            if (value != null && !value.isEmpty()) {
                formBuilder.add(name, value);
            }
        }
        formBuilder.add("pid", "" + projectId);
        formBuilder.add("appid", "" + appId);
        String nonce = generateNonce(32);
        long ts = getTimestamp();
        formBuilder.add("timestamp", "" + ts);
        formBuilder.add("noncestr", nonce);

        // Sign the form params
        HttpUrl.Builder signBuilder = HttpUrl.parse("http://x/?" + formToString(formBuilder)).newBuilder();
        String sign = signParams(signBuilder, sessionSecret);
        formBuilder.add("sign", sign);

        RequestBody body = formBuilder.build();
        HttpUrl cleanUrl = HttpUrl.parse(url.scheme() + "://" + url.host()
            + (url.port() != HttpUrl.defaultPort(url.scheme()) ? ":" + url.port() : "")
            + url.encodedPath());

        Request request = new Request.Builder()
            .url(cleanUrl)
            .post(body)
            .build();
        return executeRequest(request);
    }

    private static String formToString(FormBody.Builder builder) {
        FormBody body = builder.build();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.size(); i++) {
            if (i > 0) sb.append("&");
            sb.append(body.name(i)).append("=").append(body.value(i));
        }
        return sb.toString();
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
            String code = root.optString("code", root.optString("status", root.optString("errno", "")));
            String safeMessage = serverMessage == null ? "" : serverMessage
                .replace('\n', ' ')
                .replace('\r', ' ');
            if (safeMessage.length() > 200) {
                safeMessage = safeMessage.substring(0, 200);
            }
            Log.w(TAG, "Server rejected request: code=" + code + " message=" + safeMessage);
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

    static boolean isSuccess(JSONObject root) {
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

    static String extractServerMessage(JSONObject root) {
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
            String lower = msg.toLowerCase();
            return lower.contains("本次登录需要进行验证")
                || lower.contains("captcha_required")
                || lower.contains("验证码输入错误");
        } catch (Exception e) {
            return false;
        }
    }

    public static String normalizeServerUrl(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("/+$", "");
    }

    public static String base64UrlEncode(String input) {
        try {
            return Base64.encodeToString(input.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE).trim();
        } catch (Exception e) {
            return "";
        }
    }
}
