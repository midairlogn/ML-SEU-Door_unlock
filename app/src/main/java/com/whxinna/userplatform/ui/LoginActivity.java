package com.whxinna.userplatform.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.whxinna.userplatform.R;
import com.whxinna.userplatform.SettingsActivity;
import com.whxinna.userplatform.alipay.AlipayAuth;
import com.whxinna.userplatform.api.AuthApi;
import com.whxinna.userplatform.api.CredentialApi;
import com.whxinna.userplatform.model.LoginResponse;
import com.whxinna.userplatform.storage.CredentialCache;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "ZL_Login";
    private static final String REMEMBER_PREFS = "remember_prefs";
    private static final String KEY_REMEMBER = "remember_me";
    private static final String KEY_PHONE = "saved_phone";
    private static final String KEY_PASSWORD = "saved_password";

    private TextInputLayout tilPhone;
    private TextInputLayout tilPassword;
    private TextInputEditText etPhone;
    private TextInputEditText etPassword;
    private MaterialButton btnLogin;
    private MaterialButton btnAlipay;
    private ProgressBar progressBar;
    private MaterialCheckBox cbRemember;

    private AuthApi authApi;
    private CredentialCache cache;
    private SharedPreferences rememberPrefs;
    private final ExecutorService alipayExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        cache = CredentialCache.getInstance(this);
        authApi = new AuthApi(cache);
        rememberPrefs = getSharedPreferences(REMEMBER_PREFS, MODE_PRIVATE);

        if (cache.hasSession()) {
            navigateToMain();
            return;
        }

        initViews();
        loadRemembered();
        setupListeners();
    }


    private void initViews() {
        tilPhone = findViewById(R.id.tilPhone);
        tilPassword = findViewById(R.id.tilPassword);
        etPhone = findViewById(R.id.etPhone);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        btnAlipay = findViewById(R.id.btnAlipay);
        progressBar = findViewById(R.id.progressBar);
        cbRemember = findViewById(R.id.cbRemember);

        etPhone.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                tilPhone.setError(null);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        etPassword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                tilPassword.setError(null);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void loadRemembered() {
        boolean remember = rememberPrefs.getBoolean(KEY_REMEMBER, false);
        cbRemember.setChecked(remember);
        if (remember) {
            String phone = rememberPrefs.getString(KEY_PHONE, "");
            String encodedPwd = rememberPrefs.getString(KEY_PASSWORD, "");
            if (!phone.isEmpty()) {
                etPhone.setText(phone);
            }
            if (!encodedPwd.isEmpty()) {
                etPassword.setText(decodePassword(encodedPwd));
            }
        }
    }

    private void saveRemembered(String phone, String password) {
        if (cbRemember.isChecked()) {
            rememberPrefs.edit()
                .putBoolean(KEY_REMEMBER, true)
                .putString(KEY_PHONE, phone)
                .putString(KEY_PASSWORD, encodePassword(password))
                .apply();
        } else {
            rememberPrefs.edit()
                .putBoolean(KEY_REMEMBER, false)
                .remove(KEY_PHONE)
                .remove(KEY_PASSWORD)
                .apply();
        }
    }

    private String encodePassword(String password) {
        return Base64.encodeToString(password.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private String decodePassword(String encoded) {
        return new String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }

    private void setupListeners() {
        btnLogin.setOnClickListener(v -> attemptLogin());
        btnAlipay.setOnClickListener(v -> startAlipayLogin());
    }

    private void attemptLogin() {
        String phone = etPhone.getText() != null ? etPhone.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        if (!isValidPhone(phone)) {
            tilPhone.setError(getString(R.string.error_phone));
            return;
        }
        if (!isValidPassword(password)) {
            tilPassword.setError(getString(R.string.error_password));
            return;
        }

        setLoading(true);

        authApi.login(phone, password, new AuthApi.AuthCallback() {
            @Override
            public void onSuccess(LoginResponse response) {
                setLoading(false);
                saveRemembered(phone, password);
                Toast.makeText(LoginActivity.this, "Login successful", Toast.LENGTH_SHORT).show();
                navigateToMain();
            }

            @Override
            public void onCaptchaRequired() {
                setLoading(false);
                showCaptchaDialog(phone, password);
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startAlipayLogin() {
        Toast.makeText(this, R.string.alipay_loading, Toast.LENGTH_SHORT).show();
        setLoading(true);

        alipayExecutor.execute(() -> {
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
                        saveRemembered("", "");
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

    private void syncDoorLockAndNavigate() {
        CredentialApi credentialApi = new CredentialApi(cache);
        credentialApi.syncDoorLockInfo(new CredentialApi.SyncCallback() {
            @Override
            public void onSuccess(com.whxinna.userplatform.model.DoorLockInfo info) {
                navigateToMain();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "Door lock sync failed after Alipay login: " + message);
                navigateToMain();
            }
        });
    }

    private void showCaptchaDialog(String phone, String password) {
        CaptchaDialogFragment dialog = CaptchaDialogFragment.newInstance(phone, password);
        dialog.setOnCaptchaSubmitListener(captcha -> {
            setLoading(true);
            authApi.login(phone, password, captcha, new AuthApi.AuthCallback() {
                @Override
                public void onSuccess(LoginResponse response) {
                    setLoading(false);
                    saveRemembered(phone, password);
                    Toast.makeText(LoginActivity.this, "Login successful", Toast.LENGTH_SHORT).show();
                    navigateToMain();
                }

                @Override
                public void onCaptchaRequired() {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, "Captcha required again", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String message) {
                    setLoading(false);
                    Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show(getSupportFragmentManager(), "captcha");
    }

    private void navigateToMain() {
        Log.d(TAG, "navigateToMain called");
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
        Log.d(TAG, "navigateToMain completed");
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        btnAlipay.setEnabled(!loading);
        etPhone.setEnabled(!loading);
        etPassword.setEnabled(!loading);
    }

    private boolean isValidPhone(String phone) {
        return phone != null && phone.matches("^1[3-9]\\d{9}$");
    }

    private boolean isValidPassword(String password) {
        return password != null && password.matches("^\\d{6}$");
    }
}
