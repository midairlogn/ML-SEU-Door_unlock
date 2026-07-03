package com.whxinna.userplatform.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.whxinna.userplatform.model.LoginResponse;
import com.whxinna.userplatform.storage.CredentialCache;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;

public class AuthApi {

    private static final String TAG = "ZL_AuthApi";

    public interface AuthCallback {
        void onSuccess(LoginResponse response);
        void onCaptchaRequired();
        void onError(String message);
    }

    public interface SimpleCallback {
        void onSuccess(String data);
        void onError(String message);
    }

    public interface AlipayCallback {
        void onSuccess(LoginResponse response);
        void onError(String message);
    }

    private final ApiClient api;
    private final CredentialCache cache;
    private final ExecutorService executor;
    private final Handler mainHandler;

    public AuthApi(CredentialCache cache) {
        this.api = ApiClient.getInstance();
        this.cache = cache;
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void login(String phone, String pwd, String captcha, AuthCallback callback) {
        executor.execute(() -> {
            try {
                HttpUrl.Builder urlBuilder = HttpUrl.parse(api.getAuthBaseUrl() + "/webapi/users/login")
                    .newBuilder()
                    .addQueryParameter("phone", phone)
                    .addQueryParameter("pwd", pwd);

                if (captcha != null && !captcha.isEmpty()) {
                    urlBuilder.addQueryParameter("code", captcha);
                }

                String responseJson = api.executeAuthRequest(urlBuilder);
                Log.d(TAG, "Login response length: " + responseJson.length());
                Log.d(TAG, "Login response preview: " + responseJson.substring(0, Math.min(300, responseJson.length())));

                if (ApiClient.isCaptchaRequired(responseJson)) {
                    Log.d(TAG, "Captcha required");
                    mainHandler.post(() -> callback.onCaptchaRequired());
                    return;
                }

                String dataStr;
                try {
                    dataStr = ApiClient.extractDataField(responseJson);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to extract data field from response", e);
                    Log.d(TAG, "Raw response: " + responseJson);
                    mainHandler.post(() -> callback.onError("Server response error: " + e.getMessage()));
                    return;
                }

                Log.d(TAG, "Decoded data preview: " + dataStr.substring(0, Math.min(300, dataStr.length())));

                LoginResponse loginResponse;
                try {
                    loginResponse = LoginResponse.fromJson(dataStr);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse login response from data", e);
                    Log.d(TAG, "Data string: " + dataStr);
                    mainHandler.post(() -> callback.onError("Failed to parse server response: " + e.getMessage()));
                    return;
                }

                if (loginResponse.userInfo == null || loginResponse.userInfo.id.isEmpty()) {
                    Log.e(TAG, "Login response missing user info");
                    mainHandler.post(() -> callback.onError("Server did not return user info"));
                    return;
                }

                if (loginResponse.serverInfo == null || loginResponse.serverInfo.serverAddr.isEmpty()) {
                    Log.e(TAG, "Login response missing server info");
                    mainHandler.post(() -> callback.onError("Server did not return server info"));
                    return;
                }

                cache.saveSession(
                    phone, pwd,
                    loginResponse.userInfo.id,
                    loginResponse.userInfo.identityCode,
                    loginResponse.platformToken,
                    loginResponse.serverInfo.sessionSecret,
                    loginResponse.serverInfo.serverAddr
                );

                Log.d(TAG, "Session saved, navigating to main");
                mainHandler.post(() -> callback.onSuccess(loginResponse));

            } catch (IOException e) {
                Log.e(TAG, "Login network error", e);
                mainHandler.post(() -> callback.onError("Network error: " + e.getMessage()));
            } catch (Exception e) {
                Log.e(TAG, "Login error", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
            }
        });
    }

    public void login(String phone, String pwd, AuthCallback callback) {
        login(phone, pwd, null, callback);
    }

    public void getLoginCaptcha(String phone, SimpleCallback callback) {
        executor.execute(() -> {
            try {
                String svgCode = fetchLoginCaptchaSvg(phone);
                mainHandler.post(() -> callback.onSuccess(svgCode));
            } catch (Exception e) {
                Log.e(TAG, "Captcha error", e);
                mainHandler.post(() -> callback.onError("Failed to get captcha: " + e.getMessage()));
            }
        });
    }

    private String fetchLoginCaptchaSvg(String phone) throws Exception {
        int maxAttempts = 2;
        long retryDelayMs = 200;
        Exception lastError = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return fetchLoginCaptchaSvgOnce(phone);
            } catch (Exception e) {
                lastError = e;
                if (attempt + 1 < maxAttempts) {
                    try { Thread.sleep(retryDelayMs); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw lastError != null ? lastError : new Exception("验证码获取失败");
    }

    private String fetchLoginCaptchaSvgOnce(String phone) throws Exception {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(api.getAuthBaseUrl() + "/webapi/users/get_login_code")
            .newBuilder()
            .addQueryParameter("phone", phone);

        String responseJson = api.executeAuthRequest(urlBuilder);
        String dataStr = ApiClient.extractDataField(responseJson);

        JSONObject data = new JSONObject(dataStr);
        String codeImg = data.optString("codeImg", "");
        if (codeImg.isEmpty()) {
            throw new Exception("验证码获取失败");
        }

        int svgStart = codeImg.indexOf("<svg");
        if (svgStart < 0) throw new Exception("验证码获取失败");
        int svgEnd = codeImg.toLowerCase().lastIndexOf("</svg>");
        if (svgEnd <= svgStart) throw new Exception("验证码获取失败");
        return codeImg.substring(svgStart, svgEnd + 6);
    }

    public void autoReLogin(CredentialCache cache, AuthCallback callback) {
        String phone = cache.getPhone();
        String password = cache.getPassword();
        if (phone.isEmpty() || password.isEmpty()) {
            mainHandler.post(() -> callback.onError("No stored credentials"));
            return;
        }
        login(phone, password, callback);
    }

    public String fetchAlipayAuthInfo() throws Exception {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(api.getAuthBaseUrl() + "/webapi/oauth/alipay/auth_info")
            .newBuilder();

        String responseJson = api.executeAuthRequest(urlBuilder);

        // Parse raw response manually (like reference app's authGetRaw)
        org.json.JSONObject outer = new org.json.JSONObject(responseJson);
        if (!ApiClient.isSuccess(outer)) {
            String msg = ApiClient.extractServerMessage(outer);
            throw new Exception(msg != null ? msg : "Failed to fetch Alipay auth_info");
        }

        String dataRaw = outer.optString("data", "");
        if (dataRaw.isEmpty()) {
            throw new Exception("Server returned empty auth_info data");
        }

        // data is base64-encoded, decode it
        String decoded = ApiClient.base64Decode(dataRaw).trim();
        if (decoded.isEmpty()) {
            throw new Exception("Failed to decode auth_info");
        }

        // decoded is a JSON string literal (with quotes), strip them
        String authInfo = unquoteJsonString(decoded);
        if (authInfo.isEmpty()) {
            throw new Exception("Server returned empty auth_info");
        }
        return authInfo;
    }

    private static String unquoteJsonString(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("\"")) return trimmed;
        try {
            org.json.JSONArray arr = new org.json.JSONArray("[" + trimmed + "]");
            return arr.getString(0);
        } catch (Exception e) {
            return trimmed.replaceAll("^\"|\"$", "");
        }
    }

    public void oauthLogin(String authCode, AlipayCallback callback) {
        executor.execute(() -> {
            try {
                String systemInfoJson = new org.json.JSONObject()
                    .put("appVersion", "1.0.0")
                    .put("systemType", "android")
                    .put("systemVersion", android.os.Build.VERSION.RELEASE)
                    .put("deviceModel", android.os.Build.MODEL)
                    .put("deviceToken", "")
                    .toString();

                String authInfoJson = new org.json.JSONObject()
                    .put("auth_code", authCode)
                    .put("oauth_type", "alipay_app")
                    .put("sign_type", "RSA")
                    .toString();

                String b64Sys = ApiClient.base64UrlEncode(systemInfoJson);
                String b64Auth = ApiClient.base64UrlEncode(authInfoJson);

                HttpUrl.Builder urlBuilder = HttpUrl.parse(api.getAuthBaseUrl() + "/webapi/oauth/login")
                    .newBuilder()
                    .addQueryParameter("base64_systemInfo", b64Sys)
                    .addQueryParameter("base64_authInfo", b64Auth)
                    .addQueryParameter("app_version", "1.0.0");

                String responseJson = api.executeAuthRequest(urlBuilder);
                Log.d(TAG, "OAuth login response length: " + responseJson.length());

                if (ApiClient.isCaptchaRequired(responseJson)) {
                    mainHandler.post(() -> callback.onError("Captcha required for OAuth login"));
                    return;
                }

                String dataStr;
                try {
                    dataStr = ApiClient.extractDataField(responseJson);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to extract data from OAuth response", e);
                    mainHandler.post(() -> callback.onError("Server response error: " + e.getMessage()));
                    return;
                }

                LoginResponse loginResponse;
                try {
                    loginResponse = LoginResponse.fromJson(dataStr);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse OAuth login response", e);
                    mainHandler.post(() -> callback.onError("Failed to parse server response: " + e.getMessage()));
                    return;
                }

                if (loginResponse.userInfo == null || loginResponse.userInfo.id.isEmpty()) {
                    mainHandler.post(() -> callback.onError("Server did not return user info"));
                    return;
                }

                if (loginResponse.serverInfo == null || loginResponse.serverInfo.serverAddr.isEmpty()) {
                    mainHandler.post(() -> callback.onError("Server did not return server info"));
                    return;
                }

                String phone = loginResponse.userInfo.phone != null ? loginResponse.userInfo.phone : "";

                cache.saveSession(
                    phone, "",
                    loginResponse.userInfo.id,
                    loginResponse.userInfo.identityCode,
                    loginResponse.platformToken,
                    loginResponse.serverInfo.sessionSecret,
                    loginResponse.serverInfo.serverAddr
                );

                Log.d(TAG, "OAuth session saved");
                mainHandler.post(() -> callback.onSuccess(loginResponse));

            } catch (Exception e) {
                Log.e(TAG, "OAuth login error", e);
                mainHandler.post(() -> callback.onError("Error: " + e.getMessage()));
            }
        });
    }
}
