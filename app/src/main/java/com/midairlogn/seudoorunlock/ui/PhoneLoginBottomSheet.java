package com.midairlogn.seudoorunlock.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.midairlogn.seudoorunlock.R;
import com.midairlogn.seudoorunlock.api.AuthApi;
import com.midairlogn.seudoorunlock.model.LoginResponse;
import com.midairlogn.seudoorunlock.storage.CredentialCache;
import com.midairlogn.seudoorunlock.storage.SecurePrefs;

import java.nio.charset.StandardCharsets;

public class PhoneLoginBottomSheet extends BottomSheetDialogFragment {

    private static final String TAG = "ZL_PhoneLoginSheet";
    private static final String REMEMBER_PREFS = "remember_prefs";
    private static final String KEY_REMEMBER = "remember_me";
    private static final String KEY_PHONE = "saved_phone";
    private static final String KEY_PASSWORD = "saved_password";

    public interface LoginSuccessListener {
        void onLoginSuccess();
    }

    private LoginSuccessListener listener;

    public void setLoginSuccessListener(LoginSuccessListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.bottom_sheet_phone_login, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextInputLayout tilPhone = view.findViewById(R.id.tilPhone);
        TextInputLayout tilPassword = view.findViewById(R.id.tilPassword);
        TextInputEditText etPhone = view.findViewById(R.id.etPhone);
        TextInputEditText etPassword = view.findViewById(R.id.etPassword);
        MaterialButton btnLogin = view.findViewById(R.id.btnLogin);
        MaterialCheckBox cbRemember = view.findViewById(R.id.cbRemember);
        ProgressBar progressBar = view.findViewById(R.id.progressBar);

        CredentialCache cache = CredentialCache.getInstance(requireContext());
        AuthApi authApi = new AuthApi(cache);
        SharedPreferences rememberPrefs = requireContext().getSharedPreferences(REMEMBER_PREFS, 0);
        SecurePrefs securePrefs = SecurePrefs.getInstance(requireContext());
        migrateRememberedCredentials(rememberPrefs, securePrefs);

        // Load remembered credentials
        boolean remember = rememberPrefs.getBoolean(KEY_REMEMBER, false);
        cbRemember.setChecked(remember);
        if (remember) {
            String phone = securePrefs.getString(KEY_PHONE, "");
            String password = securePrefs.getString(KEY_PASSWORD, "");
            if (!phone.isEmpty()) etPhone.setText(phone);
            if (!password.isEmpty()) etPassword.setText(password);
        }

        // Clear errors on text change
        etPhone.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { tilPhone.setError(null); }
            @Override public void afterTextChanged(Editable s) {}
        });
        etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { tilPassword.setError(null); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnLogin.setOnClickListener(v -> {
            String phone = etPhone.getText() != null ? etPhone.getText().toString().trim() : "";
            String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

            if (!phone.matches("^1[3-9]\\d{9}$")) {
                tilPhone.setError(getString(R.string.error_phone));
                return;
            }
            if (!password.matches("^\\d{6}$")) {
                tilPassword.setError(getString(R.string.error_password));
                return;
            }

            setLoading(true, btnLogin, progressBar, etPhone, etPassword);

            authApi.login(phone, password, new AuthApi.AuthCallback() {
                @Override
                public void onSuccess(LoginResponse response) {
                    if (!isAdded()) return;
                    setLoading(false, btnLogin, progressBar, etPhone, etPassword);
                    saveRemembered(rememberPrefs, securePrefs, cbRemember.isChecked(), phone, password);
                    Toast.makeText(requireContext(), "Login successful", Toast.LENGTH_SHORT).show();
                    if (listener != null) listener.onLoginSuccess();
                    dismiss();
                }

                @Override
                public void onCaptchaRequired() {
                    if (!isAdded()) return;
                    setLoading(false, btnLogin, progressBar, etPhone, etPassword);
                    showCaptchaDialog(authApi, phone, password, btnLogin, progressBar, etPhone, etPassword,
                        rememberPrefs, securePrefs, cbRemember);
                }

                @Override
                public void onError(String message) {
                    if (!isAdded()) return;
                    setLoading(false, btnLogin, progressBar, etPhone, etPassword);
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void showCaptchaDialog(AuthApi authApi, String phone, String password,
                                     MaterialButton btnLogin, ProgressBar progressBar,
                                     TextInputEditText etPhone, TextInputEditText etPassword,
                                     SharedPreferences rememberPrefs, SecurePrefs securePrefs,
                                     MaterialCheckBox cbRemember) {
        CaptchaDialogFragment dialog = CaptchaDialogFragment.newInstance(phone);
        dialog.setOnCaptchaSubmitListener(captcha -> {
            setLoading(true, btnLogin, progressBar, etPhone, etPassword);
            authApi.login(phone, password, captcha, new AuthApi.AuthCallback() {
                @Override
                public void onSuccess(LoginResponse response) {
                    if (!isAdded()) return;
                    setLoading(false, btnLogin, progressBar, etPhone, etPassword);
                    saveRemembered(rememberPrefs, securePrefs, cbRemember.isChecked(), phone, password);
                    Toast.makeText(requireContext(), "Login successful", Toast.LENGTH_SHORT).show();
                    if (listener != null) listener.onLoginSuccess();
                    dialog.dismissAllowingStateLoss();
                    dismiss();
                }

                @Override
                public void onCaptchaRequired() {
                    if (!isAdded()) return;
                    setLoading(false, btnLogin, progressBar, etPhone, etPassword);
                    dialog.resetForRetry();
                    Toast.makeText(requireContext(), "Captcha required again", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String message) {
                    if (!isAdded()) return;
                    setLoading(false, btnLogin, progressBar, etPhone, etPassword);
                    dialog.setSubmitting(false);
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show(getParentFragmentManager(), "captcha");
    }

    private void setLoading(boolean loading, MaterialButton btnLogin, ProgressBar progressBar,
                            TextInputEditText etPhone, TextInputEditText etPassword) {
        if (!isAdded()) return;
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        etPhone.setEnabled(!loading);
        etPassword.setEnabled(!loading);
    }

    private void saveRemembered(SharedPreferences prefs, SecurePrefs securePrefs, boolean remember,
                                String phone, String password) {
        if (remember) {
            prefs.edit()
                .putBoolean(KEY_REMEMBER, true)
                .apply();
            securePrefs.putString(KEY_PHONE, phone);
            securePrefs.putString(KEY_PASSWORD, password);
        } else {
            prefs.edit()
                .putBoolean(KEY_REMEMBER, false)
                .apply();
            securePrefs.remove(KEY_PHONE);
            securePrefs.remove(KEY_PASSWORD);
        }
    }

    private void migrateRememberedCredentials(SharedPreferences prefs, SecurePrefs securePrefs) {
        String oldPhone = prefs.getString(KEY_PHONE, "");
        String oldPassword = prefs.getString(KEY_PASSWORD, "");
        if (!oldPhone.isEmpty() && securePrefs.getString(KEY_PHONE, "").isEmpty()) {
            securePrefs.putString(KEY_PHONE, oldPhone);
        }
        if (!oldPassword.isEmpty() && securePrefs.getString(KEY_PASSWORD, "").isEmpty()) {
            try {
                String decoded = new String(Base64.decode(oldPassword, Base64.NO_WRAP), StandardCharsets.UTF_8);
                securePrefs.putString(KEY_PASSWORD, decoded);
            } catch (IllegalArgumentException ignored) {}
        }
        prefs.edit().remove(KEY_PHONE).remove(KEY_PASSWORD).apply();
    }
}
