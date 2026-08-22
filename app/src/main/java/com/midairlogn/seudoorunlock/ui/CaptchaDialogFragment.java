package com.midairlogn.seudoorunlock.ui;

import android.app.Dialog;
import android.graphics.Picture;
import android.graphics.drawable.PictureDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.caverock.androidsvg.SVG;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.midairlogn.seudoorunlock.R;
import com.midairlogn.seudoorunlock.api.AuthApi;
import com.midairlogn.seudoorunlock.storage.CredentialCache;

public class CaptchaDialogFragment extends DialogFragment {

    private static final String ARG_PHONE = "phone";

    private String phone;
    private OnCaptchaSubmitListener listener;
    private ImageView ivCaptcha;
    private TextInputEditText etCaptcha;
    private MaterialButton btnRefresh;
    private boolean submitting;

    public interface OnCaptchaSubmitListener {
        void onSubmit(String captcha);
    }

    public static CaptchaDialogFragment newInstance(String phone) {
        CaptchaDialogFragment fragment = new CaptchaDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PHONE, phone);
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
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_captcha, null);

        ivCaptcha = view.findViewById(R.id.ivCaptcha);
        etCaptcha = view.findViewById(R.id.etCaptcha);
        btnRefresh = view.findViewById(R.id.btnRefreshCaptcha);

        btnRefresh.setOnClickListener(v -> loadCaptcha());

        loadCaptcha();

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.captcha_title)
            .setView(view)
            .setPositiveButton(R.string.btn_submit, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String captcha = etCaptcha.getText() != null ? etCaptcha.getText().toString().trim() : "";
                if (captcha.length() != 4) {
                    Toast.makeText(getContext(), R.string.error_captcha, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (listener != null) {
                    setSubmitting(true);
                    listener.onSubmit(captcha);
                }
            }));

        return dialog;
    }

    public void setSubmitting(boolean submitting) {
        this.submitting = submitting;
        Dialog currentDialog = getDialog();
        if (currentDialog instanceof AlertDialog) {
            android.widget.Button positive = ((AlertDialog) currentDialog).getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) positive.setEnabled(!submitting);
        }
        if (btnRefresh != null) btnRefresh.setEnabled(!submitting);
        if (etCaptcha != null) etCaptcha.setEnabled(!submitting);
    }

    public void resetForRetry() {
        if (etCaptcha != null) etCaptcha.setText("");
        setSubmitting(false);
        loadCaptcha();
    }

    private void loadCaptcha() {
        if (getContext() == null) return;

        btnRefresh.setEnabled(false);

        AuthApi authApi = new AuthApi(CredentialCache.getInstance(getContext()));
        authApi.getLoginCaptcha(phone, new AuthApi.SimpleCallback() {
            @Override
            public void onSuccess(String svgData) {
                if (ivCaptcha != null && svgData != null) {
                    try {
                        SVG svg = SVG.getFromString(svgData);
                        Picture picture = svg.renderToPicture();
                        ivCaptcha.setImageDrawable(new PictureDrawable(picture));
                    } catch (Exception e) {
                        Log.e("ZL_Captcha", "SVG render failed", e);
                        if (getContext() != null) {
                            Toast.makeText(getContext(), R.string.captcha_render_failed, Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                if (btnRefresh != null) btnRefresh.setEnabled(!submitting);
            }

            @Override
            public void onError(String message) {
                if (getContext() != null) {
                    Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                }
                if (btnRefresh != null) btnRefresh.setEnabled(!submitting);
            }
        });
    }
}
