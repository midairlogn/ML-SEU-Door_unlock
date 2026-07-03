package com.midairlogn.seudoorunlock.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.midairlogn.seudoorunlock.R;
import com.midairlogn.seudoorunlock.api.AuthApi;
import com.midairlogn.seudoorunlock.storage.CredentialCache;

public class CaptchaDialogFragment extends DialogFragment {

    private static final String ARG_PHONE = "phone";
    private static final String ARG_PASSWORD = "password";

    private String phone;
    private String password;
    private OnCaptchaSubmitListener listener;
    private WebView webViewCaptcha;
    private TextInputEditText etCaptcha;
    private MaterialButton btnRefresh;

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
        btnRefresh = view.findViewById(R.id.btnRefreshCaptcha);

        WebSettings settings = webViewCaptcha.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);

        btnRefresh.setOnClickListener(v -> loadCaptcha());

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

        btnRefresh.setEnabled(false);

        AuthApi authApi = new AuthApi(CredentialCache.getInstance(getContext()));
        authApi.getLoginCaptcha(phone, new AuthApi.SimpleCallback() {
            @Override
            public void onSuccess(String svgData) {
                if (webViewCaptcha != null && svgData != null) {
                    String html = "<!DOCTYPE html>"
                        + "<html><head>"
                        + "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'/>"
                        + "<style>"
                        + "body { margin:0; padding:0; display:flex; justify-content:center; align-items:center; "
                        + "background:#FFFFFF; min-height:100%; width:100%; overflow:hidden; }"
                        + "svg { max-width:100%; max-height:100%; }"
                        + "</style></head><body>"
                        + svgData
                        + "</body></html>";
                    webViewCaptcha.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
                if (btnRefresh != null) btnRefresh.setEnabled(true);
            }

            @Override
            public void onError(String message) {
                if (getContext() != null) {
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                }
                if (btnRefresh != null) btnRefresh.setEnabled(true);
            }
        });
    }
}
