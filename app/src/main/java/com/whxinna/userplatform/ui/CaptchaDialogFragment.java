package com.whxinna.userplatform.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.whxinna.userplatform.R;
import com.whxinna.userplatform.api.AuthApi;

public class CaptchaDialogFragment extends DialogFragment {

    private static final String ARG_PHONE = "phone";
    private static final String ARG_PASSWORD = "password";

    private String phone;
    private String password;
    private OnCaptchaSubmitListener listener;
    private WebView webViewCaptcha;
    private TextInputEditText etCaptcha;

    public interface OnCaptchaSubmitListener {
        void onSubmit(String captcha);
    }

    public static CaptchaDialogFragment newInstance(String phone, String password) {
        CaptchaDialogFragment fragment = new CaptchaDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PHONE, phone);
        args.putString(ARG_PASSWORD, password);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnCaptchaSubmitListener(OnCaptchaSubmitListener listener) {
        this.listener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            phone = getArguments().getString(ARG_PHONE, "");
            password = getArguments().getString(ARG_PASSWORD, "");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_captcha, null);

        webViewCaptcha = view.findViewById(R.id.ivCaptcha);
        etCaptcha = view.findViewById(R.id.etCaptcha);

        // Setup WebView to render SVG captcha
        WebSettings settings = webViewCaptcha.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        loadCaptcha();

        return new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.captcha_title)
            .setView(view)
            .setPositiveButton(R.string.btn_submit, (dialog, which) -> {
                String captcha = etCaptcha.getText() != null ? etCaptcha.getText().toString().trim() : "";
                if (captcha.length() != 4) {
                    Toast.makeText(getContext(), R.string.error_captcha, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (listener != null) {
                    listener.onSubmit(captcha);
                }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .create();
    }

    private void loadCaptcha() {
        if (getContext() == null) return;

        AuthApi authApi = new AuthApi(com.whxinna.userplatform.storage.CredentialCache.getInstance(getContext()));
        authApi.getLoginCaptcha(phone, new AuthApi.SimpleCallback() {
            @Override
            public void onSuccess(String svgData) {
                if (webViewCaptcha != null && svgData != null) {
                    String html = "<html><body style='margin:0;padding:0;display:flex;justify-content:center;align-items:center;background:#F0F0F0;'>" + svgData + "</body></html>";
                    webViewCaptcha.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
            }

            @Override
            public void onError(String message) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
