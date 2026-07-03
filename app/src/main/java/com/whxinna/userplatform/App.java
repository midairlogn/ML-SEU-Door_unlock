package com.whxinna.userplatform;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.Log;

import java.util.Locale;

public class App extends Application {

    private static final String TAG = "ZL_App";

    @Override
    public void onCreate() {
        super.onCreate();
        applyLanguage();
        Log.d(TAG, "Application started");
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
}
