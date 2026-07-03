package com.whxinna.userplatform;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

public class App extends Application {

    private static final String TAG = "ZL_App";

    @Override
    public void onCreate() {
        super.onCreate();
        applySettings();
        Log.d(TAG, "Application started");
    }

    private void applySettings() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        
        // Language
        String lang = prefs.getString(SettingsActivity.KEY_LANGUAGE, SettingsActivity.LANG_SYSTEM);
        LocaleListCompat appLocale;
        switch (lang) {
            case SettingsActivity.LANG_ZH:
                appLocale = LocaleListCompat.forLanguageTags("zh");
                break;
            case SettingsActivity.LANG_EN:
                appLocale = LocaleListCompat.forLanguageTags("en");
                break;
            default:
                appLocale = LocaleListCompat.getEmptyLocaleList();
                break;
        }
        AppCompatDelegate.setApplicationLocales(appLocale);

        // Theme
        String theme = prefs.getString(SettingsActivity.KEY_THEME, SettingsActivity.THEME_SYSTEM);
        switch (theme) {
            case SettingsActivity.THEME_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case SettingsActivity.THEME_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }
}
