package com.whxinna.userplatform;

import android.app.Application;
import android.util.Log;

public class App extends Application {

    private static final String TAG = "ZL_App";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Application started");
    }
}
