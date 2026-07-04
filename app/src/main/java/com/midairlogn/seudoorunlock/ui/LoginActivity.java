package com.midairlogn.seudoorunlock.ui;

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
import com.midairlogn.seudoorunlock.BuildConfig;
import com.midairlogn.seudoorunlock.R;
import com.midairlogn.seudoorunlock.SettingsActivity;
import com.midairlogn.seudoorunlock.alipay.AlipayAuth;
import com.midairlogn.seudoorunlock.api.AuthApi;
import com.midairlogn.seudoorunlock.api.CredentialApi;
import com.midairlogn.seudoorunlock.model.LoginResponse;
import com.midairlogn.seudoorunlock.storage.CredentialCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "ZL_Login";

    private MaterialButton btnAlipay;
    private MaterialButton btnPhoneLogin;
    private ImageButton btnSettings;
    private ProgressBar progressBar;

    private AuthApi authApi;
    private CredentialCache cache;
    private final ExecutorService oauthExecutor = Executors.newSingleThreadExecutor();

    private AuthApi getAuthApi() {
        if (authApi == null) {
            authApi = new AuthApi(cache);
        }
        return authApi;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        cache = CredentialCache.getInstance(this);

        initViews();
        setupListeners();
        setupVersionInfo();
    }

    private void initViews() {
        btnAlipay = findViewById(R.id.btnAlipay);
        btnPhoneLogin = findViewById(R.id.btnPhoneLogin);
        btnSettings = findViewById(R.id.btnSettings);
        progressBar = findViewById(R.id.progressBar);
    }

    private void setupListeners() {
        btnAlipay.setOnClickListener(v -> startAlipayLogin());
        btnPhoneLogin.setOnClickListener(v -> showPhoneLoginSheet());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void startAlipayLogin() {
        Toast.makeText(this, R.string.alipay_loading, Toast.LENGTH_SHORT).show();
        setLoading(true);

        oauthExecutor.execute(() -> {
            try {
                String authInfo = getAuthApi().fetchAlipayAuthInfo();
                Log.d(TAG, "Got auth_info, launching Alipay...");

                String authCode = AlipayAuth.authorize(LoginActivity.this, authInfo);
                Log.d(TAG, "Got auth_code: " + authCode.substring(0, Math.min(8, authCode.length())) + "...");

                runOnUiThread(() -> Toast.makeText(LoginActivity.this, "Completing login...", Toast.LENGTH_SHORT).show());

                getAuthApi().oauthLogin(authCode, new AuthApi.AlipayCallback() {
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
            public void onSuccess(com.midairlogn.seudoorunlock.model.DoorLockInfo info) {
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
        Intent intent = new Intent(this, com.midairlogn.seudoorunlock.ui.MainActivity.class);
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
            btnAlipay.setEnabled(!loading);
            btnPhoneLogin.setEnabled(!loading);
        });
    }
}
