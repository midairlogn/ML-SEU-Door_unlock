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
                    .addQueryParameter("pwd", pwd)
                    .addQueryParameter("pid", String.valueOf(ApiClient.PROJECT_ID))
                    .addQueryParameter("appid", String.valueOf(ApiClient.APP_ID));

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
}
