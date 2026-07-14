package com.midairlogn.seudoorunlock.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.midairlogn.seudoorunlock.storage.CredentialCache;

public class SplashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CredentialCache cache = CredentialCache.getInstance(this);
        boolean loggedIn = cache.hasSession();

        new Handler(Looper.getMainLooper()).post(() -> {
            Intent intent;
            if (loggedIn) {
                intent = new Intent(this, MainActivity.class);
            } else {
                intent = new Intent(this, LoginActivity.class);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}
