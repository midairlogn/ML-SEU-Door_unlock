package com.whxinna.userplatform.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.whxinna.userplatform.BuildConfig;
import com.whxinna.userplatform.R;
import com.whxinna.userplatform.SettingsActivity;
import com.whxinna.userplatform.alipay.AlipayAuth;
import com.whxinna.userplatform.api.AuthApi;
import com.whxinna.userplatform.api.CredentialApi;
import com.whxinna.userplatform.model.LoginResponse;
import com.whxinna.userplatform.storage.CredentialCache;
import com.whxinna.userplatform.wechat.WechatAuth;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "ZL_Login";

    private MaterialButton btnWechat;
    private MaterialButton btnAlipay;
    private MaterialButton btnPhoneLogin;
    private ImageButton btnSettings;
    private ProgressBar progressBar;

    private AuthApi authApi;
    private CredentialCache cache;
    private final ExecutorService oauthExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        cache = CredentialCache.getInstance(this);
        authApi = new AuthApi(cache);

        if (cache.hasSession()) {
            navigateToMain();
            return;
        }

        initViews();
        setupListeners();
        setupVersionInfo();
        WechatAuth.getInstance().init(this);
    }

    private void initViews() {
        btnWechat = findViewById(R.id.btnWechat);
        btnAlipay = findViewById(R.id.btnAlipay);
        btnPhoneLogin = findViewById(R.id.btnPhoneLogin);
        btnSettings = findViewById(R.id.btnSettings);
        progressBar = findViewById(R.id.progressBar);
    }

    private void setupListeners() {
        btnWechat.setOnClickListener(v -> startWechatLogin());
        btnAlipay.setOnClickListener(v -> startAlipayLogin());
        btnPhoneLogin.setOnClickListener(v -> showPhoneLoginSheet());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void startWechatLogin() {
        if (!WechatAuth.getInstance().isWechatInstalled()) {
            Toast.makeText(this, R.string.wechat_not_installed, Toast.LENGTH_LONG).show();
            return;
        }

        setLoading(true);
        Toast.makeText(this, R.string.wechat_loading, Toast.LENGTH_SHORT).show();

        WechatAuth.getInstance().sendAuth(this, new WechatAuth.WechatAuthCallback() {
            @Override
            public void onSuccess(String code) {
                Log.d(TAG, "WeChat auth code obtained");
                runOnUiThread(() -> Toast.makeText(LoginActivity.this, "Completing login...", Toast.LENGTH_SHORT).show());

                authApi.wechatLogin(code, new AuthApi.OAuthCallback() {
                    @Override
                    public void onSuccess(LoginResponse response) {
                        setLoading(false);
                        Toast.makeText(LoginActivity.this, "Login successful", Toast.LENGTH_SHORT).show();
                        syncDoorLockAndNavigate();
                    }

                    @Override
                    public void onPhoneBindingRequired(String openId, String oauthType, String authCode) {
                        setLoading(false);
                        Toast.makeText(LoginActivity.this, "Phone binding required. Please use phone login first.", Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        setLoading(false);
                        Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "WeChat auth error: " + message);
                setLoading(false);
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startAlipayLogin() {
        Toast.makeText(this, R.string.alipay_loading, Toast.LENGTH_SHORT).show();
        setLoading(true);

        oauthExecutor.execute(() -> {
            try {
                String authInfo = authApi.fetchAlipayAuthInfo();
                Log.d(TAG, "Got auth_info, launching Alipay...");

                String authCode = AlipayAuth.authorize(LoginActivity.this, authInfo);
                Log.d(TAG, "Got auth_code: " + authCode.substring(0, Math.min(8, authCode.length())) + "...");

                runOnUiThread(() -> Toast.makeText(LoginActivity.this, "Completing login...", Toast.LENGTH_SHORT).show());

                authApi.oauthLogin(authCode, new AuthApi.AlipayCallback() {
                    @Override
                    public void onSuccess(LoginResponse response) {
                        setLoading(false);
                        Toast.makeText(LoginActivity.this, "Login successful", Toast.LENGTH_SHORT).show();
                        syncDoorLockAndNavigate();
                    }

                    @Override
                    public void onError(String message) {
                        setLoading(false);
                        Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });

            } catch (AlipayAuth.AlipayAuthException e) {
                Log.e(TAG, "Alipay auth failed", e);
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Alipay login failed", e);
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, "Alipay login error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showPhoneLoginSheet() {
        PhoneLoginBottomSheet sheet = new PhoneLoginBottomSheet();
        sheet.setLoginSuccessListener(this::syncDoorLockAndNavigate);
        sheet.show(getSupportFragmentManager(), "phone_login");
    }

    private void syncDoorLockAndNavigate() {
        CredentialApi credentialApi = new CredentialApi(cache);
        credentialApi.syncDoorLockInfo(new CredentialApi.SyncCallback() {
            @Override
            public void onSuccess(com.whxinna.userplatform.model.DoorLockInfo info) {
                navigateToMain();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "Door lock sync failed: " + message);
                navigateToMain();
            }
        });
    }

    private void navigateToMain() {
        Log.d(TAG, "navigateToMain called");
        Intent intent = new Intent(this, com.whxinna.userplatform.ui.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
        Log.d(TAG, "navigateToMain completed");
    }

    private void setupVersionInfo() {
        TextView tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setMovementMethod(LinkMovementMethod.getInstance());
        String versionName = "1.0.0";
        try {
            versionName = BuildConfig.VERSION_NAME;
        } catch (Exception e) {
            // Use default
        }
        String infoText = "Version: v" + versionName + " | Author: <a href=\"https://github.com/midairlogn\">Midairlogn</a><br>" +
                "<a href=\"https://github.com/midairlogn/ML-SEU-Door_unlock\">ML-SEU-Door_unlock</a> \u00A9 2026 | GPLv3 LICENSE";
        tvVersion.setText(Html.fromHtml(infoText, Html.FROM_HTML_MODE_LEGACY));
    }

    private void setLoading(boolean loading) {
        runOnUiThread(() -> {
            progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
            btnWechat.setEnabled(!loading);
            btnAlipay.setEnabled(!loading);
            btnPhoneLogin.setEnabled(!loading);
        });
    }
}
