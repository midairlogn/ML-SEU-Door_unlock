package com.whxinna.userplatform.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.whxinna.userplatform.R;
import com.whxinna.userplatform.SettingsActivity;
import com.whxinna.userplatform.api.AuthApi;
import com.whxinna.userplatform.model.LoginResponse;
import com.whxinna.userplatform.storage.CredentialCache;

import java.util.Locale;

public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "ZL_Login";

    private TextInputLayout tilPhone;
    private TextInputLayout tilPassword;
    private TextInputEditText etPhone;
    private TextInputEditText etPassword;
    private MaterialButton btnLogin;
    private ProgressBar progressBar;

    private AuthApi authApi;
    private CredentialCache cache;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        applyLanguage();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        cache = CredentialCache.getInstance(this);
        authApi = new AuthApi(cache);

        // If already logged in, skip to main
        if (cache.hasSession()) {
            navigateToMain();
            return;
        }

        initViews();
        setupListeners();
    }

    private void applyTheme() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String theme = prefs.getString(SettingsActivity.KEY_THEME, SettingsActivity.THEME_SYSTEM);
        switch (theme) {
            case SettingsActivity.THEME_LIGHT:
                setTheme(R.style.Theme_SEUDoorLock);
                break;
            case SettingsActivity.THEME_DARK:
                setTheme(R.style.Theme_SEUDoorLock_Dark);
                break;
            default:
                int nightMode = getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
                if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                    setTheme(R.style.Theme_SEUDoorLock_Dark);
                } else {
                    setTheme(R.style.Theme_SEUDoorLock);
                }
                break;
        }
    }

    private void applyLanguage() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String lang = prefs.getString(SettingsActivity.KEY_LANGUAGE, SettingsActivity.LANG_SYSTEM);

        Locale locale;
        if (SettingsActivity.LANG_ZH.equals(lang)) {
            locale = Locale.CHINESE;
        } else if (SettingsActivity.LANG_EN.equals(lang)) {
            locale = Locale.ENGLISH;
        } else {
            locale = Locale.getDefault();
        }

        Configuration config = new Configuration(getResources().getConfiguration());
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
    }

    private void initViews() {
        tilPhone = findViewById(R.id.tilPhone);
        tilPassword = findViewById(R.id.tilPassword);
        etPhone = findViewById(R.id.etPhone);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        progressBar = findViewById(R.id.progressBar);

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

    private void setupListeners() {
        btnLogin.setOnClickListener(v -> attemptLogin());
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

    private void showCaptchaDialog(String phone, String password) {
        CaptchaDialogFragment dialog = CaptchaDialogFragment.newInstance(phone, password);
        dialog.setOnCaptchaSubmitListener(captcha -> {
            setLoading(true);
            authApi.login(phone, password, captcha, new AuthApi.AuthCallback() {
                @Override
                public void onSuccess(LoginResponse response) {
                    setLoading(false);
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
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
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
