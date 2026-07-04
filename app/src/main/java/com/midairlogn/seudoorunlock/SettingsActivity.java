package com.midairlogn.seudoorunlock;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.FileProvider;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "app_settings";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_THEME = "theme";
    public static final String KEY_DEFAULT_METHOD = "default_method";
    public static final String KEY_PRESERVE_LOGS = "preserve_logs";

    public static final String LANG_SYSTEM = "system";
    public static final String LANG_EN = "en";
    public static final String LANG_ZH = "zh";

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    public static final String METHOD_NFC = "nfc";
    public static final String METHOD_BLE = "ble";

    private RadioGroup rgLanguage;
    private RadioGroup rgTheme;
    private RadioGroup rgDefaultMethod;
    private MaterialSwitch switchPreserveLogs;
    private MaterialButton btnExportLogs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        applyLanguage();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        rgLanguage = findViewById(R.id.rgLanguage);
        rgTheme = findViewById(R.id.rgTheme);
        rgDefaultMethod = findViewById(R.id.rgDefaultMethod);
        switchPreserveLogs = findViewById(R.id.switchPreserveLogs);
        btnExportLogs = findViewById(R.id.btnExportLogs);

        loadSettings();
        setupListeners();
        setupDebugSection();

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

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        String lang = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM);
        switch (lang) {
            case LANG_EN: rgLanguage.check(R.id.rbLangEn); break;
            case LANG_ZH: rgLanguage.check(R.id.rbLangZh); break;
            default: rgLanguage.check(R.id.rbLangSystem); break;
        }

        String theme = prefs.getString(KEY_THEME, THEME_SYSTEM);
        switch (theme) {
            case THEME_LIGHT: rgTheme.check(R.id.rbThemeLight); break;
            case THEME_DARK: rgTheme.check(R.id.rbThemeDark); break;
            default: rgTheme.check(R.id.rbThemeSystem); break;
        }

        String method = prefs.getString(KEY_DEFAULT_METHOD, METHOD_NFC);
        switch (method) {
            case METHOD_BLE: rgDefaultMethod.check(R.id.rbMethodBle); break;
            default: rgDefaultMethod.check(R.id.rbMethodNfc); break;
        }
    }

    private void setupListeners() {
        rgLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            String lang;
            if (checkedId == R.id.rbLangEn) lang = LANG_EN;
            else if (checkedId == R.id.rbLangZh) lang = LANG_ZH;
            else lang = LANG_SYSTEM;

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, lang).apply();
            applyLanguage();
        });

        rgTheme.setOnCheckedChangeListener((group, checkedId) -> {
            String theme;
            if (checkedId == R.id.rbThemeLight) theme = THEME_LIGHT;
            else if (checkedId == R.id.rbThemeDark) theme = THEME_DARK;
            else theme = THEME_SYSTEM;

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_THEME, theme).apply();
            applyTheme();
        });

        rgDefaultMethod.setOnCheckedChangeListener((group, checkedId) -> {
            String method = (checkedId == R.id.rbMethodBle) ? METHOD_BLE : METHOD_NFC;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_DEFAULT_METHOD, method).apply();
        });
    }

    private void applyTheme() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String theme = prefs.getString(KEY_THEME, THEME_SYSTEM);
        switch (theme) {
            case THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    private void applyLanguage() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lang = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM);

        LocaleListCompat appLocale;
        if (LANG_ZH.equals(lang)) {
            appLocale = LocaleListCompat.forLanguageTags("zh");
        } else if (LANG_EN.equals(lang)) {
            appLocale = LocaleListCompat.forLanguageTags("en");
        } else {
            appLocale = LocaleListCompat.getEmptyLocaleList();
        }
        AppCompatDelegate.setApplicationLocales(appLocale);
    }

    public static String getDefaultMethod(SharedPreferences prefs) {
        return prefs.getString(KEY_DEFAULT_METHOD, METHOD_NFC);
    }

    private void setupDebugSection() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean preserveLogs = prefs.getBoolean(KEY_PRESERVE_LOGS, false);
        switchPreserveLogs.setChecked(preserveLogs);
        updateExportButtonVisibility();

        switchPreserveLogs.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_PRESERVE_LOGS, isChecked).apply();
            LogManager logManager = LogManager.getInstance();
            if (logManager != null) {
                logManager.setEnabled(isChecked);
            }
            updateExportButtonVisibility();
        });

        btnExportLogs.setOnClickListener(v -> exportLogs());
    }

    private void updateExportButtonVisibility() {
        LogManager logManager = LogManager.getInstance();
        boolean hasLogs = logManager != null && logManager.hasLogFile();
        btnExportLogs.setVisibility(
                switchPreserveLogs.isChecked() && hasLogs ? View.VISIBLE : View.GONE);
    }

    private void exportLogs() {
        LogManager logManager = LogManager.getInstance();
        if (logManager == null || !logManager.hasLogFile()) return;

        logManager.flush();
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider",
                    logManager.getLogFile());
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.settings_export_logs)));
        } catch (Exception e) {
            android.util.Log.e("ZL_Settings", "Failed to export logs", e);
        }
    }
}
