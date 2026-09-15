package com.midairlogn.seudoorunlock;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.appbar.MaterialToolbar;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "app_settings";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_THEME = "theme";
    public static final String KEY_AUTO_CLOSE = "auto_close";

    public static final String LANG_SYSTEM = "system";
    public static final String LANG_EN = "en";
    public static final String LANG_ZH = "zh";

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    private RadioGroup rgLanguage;
    private RadioGroup rgTheme;
    private MaterialSwitch swAutoClose;

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
        swAutoClose = findViewById(R.id.swAutoClose);

        loadSettings();
        setupListeners();

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

        swAutoClose.setChecked(prefs.getBoolean(KEY_AUTO_CLOSE, true));
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

        swAutoClose.setOnCheckedChangeListener((buttonView, isChecked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_CLOSE, isChecked).apply());
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
}
