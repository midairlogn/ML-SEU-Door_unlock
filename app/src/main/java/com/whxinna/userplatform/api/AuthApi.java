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
                Log.d(TAG, "Login response: " + responseJson.substring(0, Math.min(200, responseJson.length())));

                if (ApiClient.isCaptchaRequired(responseJson)) {
                    mainHandler.post(() -> callback.onCaptchaRequired());
                    return;
                }

                String dataStr = ApiClient.extractDataField(responseJson);
                LoginResponse loginResponse = LoginResponse.fromJson(dataStr);

                cache.saveSession(
                    phone, pwd,
                    loginResponse.userInfo.id,
                    loginResponse.userInfo.identityCode,
                    loginResponse.platformToken,
                    loginResponse.serverInfo.sessionSecret,
                    loginResponse.serverInfo.serverAddr
                );

                mainHandler.post(() -> callback.onSuccess(loginResponse));

            } catch (IOException e) {
                Log.e(TAG, "Login network error", e);
                mainHandler.post(() -> callback.onError("Network error: " + e.getMessage()));
            } catch (JSONException e) {
                Log.e(TAG, "Login parse error", e);
                mainHandler.post(() -> callback.onError("Server response error: " + e.getMessage()));
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
                HttpUrl.Builder urlBuilder = HttpUrl.parse(api.getAuthBaseUrl() + "/webapi/users/get_login_code")
                    .newBuilder()
                    .addQueryParameter("phone", phone);

                String responseJson = api.executeAuthRequest(urlBuilder);
                String dataStr = ApiClient.extractDataField(responseJson);

                JSONObject data = new JSONObject(dataStr);
                String svgCode = data.getString("codeImg");

                mainHandler.post(() -> callback.onSuccess(svgCode));

            } catch (Exception e) {
                Log.e(TAG, "Captcha error", e);
                mainHandler.post(() -> callback.onError("Failed to get captcha: " + e.getMessage()));
            }
        });
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
