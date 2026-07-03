package com.whxinna.userplatform;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "app_settings";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_THEME = "theme";
    public static final String KEY_DEFAULT_METHOD = "default_method";

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

        loadSettings();
        setupListeners();

        TextView tvVersion = findViewById(R.id.tvVersion);
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText(getString(R.string.settings_version, version));
        } catch (Exception e) {
            tvVersion.setText(getString(R.string.settings_version, "1.0"));
        }
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
            recreate();
        });

        rgTheme.setOnCheckedChangeListener((group, checkedId) -> {
            String theme;
            if (checkedId == R.id.rbThemeLight) theme = THEME_LIGHT;
            else if (checkedId == R.id.rbThemeDark) theme = THEME_DARK;
            else theme = THEME_SYSTEM;

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_THEME, theme).apply();
            recreate();
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
                setTheme(R.style.Theme_SEUDoorLock);
                break;
            case THEME_DARK:
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
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lang = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM);

        Locale locale;
        if (LANG_SYSTEM.equals(lang)) {
            locale = Locale.getDefault();
        } else if (LANG_ZH.equals(lang)) {
            locale = Locale.CHINESE;
        } else {
            locale = Locale.ENGLISH;
        }

        Configuration config = new Configuration(getResources().getConfiguration());
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
    }

    public static String getDefaultMethod(SharedPreferences prefs) {
        return prefs.getString(KEY_DEFAULT_METHOD, METHOD_NFC);
    }
}
