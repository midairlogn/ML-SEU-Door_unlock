package com.whxinna.userplatform.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.whxinna.userplatform.R;
import com.whxinna.userplatform.SettingsActivity;
import com.whxinna.userplatform.api.AuthApi;
import com.whxinna.userplatform.api.CredentialApi;
import com.whxinna.userplatform.ble.BleUnlockManager;
import com.whxinna.userplatform.model.DoorLockInfo;
import com.whxinna.userplatform.nfc.NfcUnlockManager;
import com.whxinna.userplatform.storage.CredentialCache;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ZL_Main";
    private static final int REQUEST_PERMISSIONS = 100;

    private MaterialToolbar toolbar;
    private TextView tvUserInfo;
    private TextView tvDoorStatus;
    private TextView tvBattery;
    private TextView tvNfcHint;
    private ImageView ivNfcIcon;
    private MaterialButton btnBleUnlock;
    private MaterialButton btnLogout;
    private ProgressBar progressBar;

    private CredentialCache cache;
    private NfcUnlockManager nfcManager;
    private BleUnlockManager bleManager;
    private CredentialApi credentialApi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        applyLanguage();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cache = CredentialCache.getInstance(this);
        nfcManager = new NfcUnlockManager(this, cache);
        bleManager = new BleUnlockManager(this, cache);
        credentialApi = new CredentialApi(cache);

        if (!cache.hasSession()) {
            navigateToLogin();
            return;
        }

        initViews();
        setupListeners();
        requestPermissions();
        refreshCredentials();
        handleNfcIntent(getIntent());
    }

    private void applyTheme() {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String theme = prefs.getString(SettingsActivity.KEY_THEME, SettingsActivity.THEME_SYSTEM);
        switch (theme) {
            case SettingsActivity.THEME_LIGHT:
                setTheme(R.style.Theme_SEUDoorLock);
                break;
            case SettingsActivity.THEME_DARK:
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        tvUserInfo = findViewById(R.id.tvUserInfo);
        tvDoorStatus = findViewById(R.id.tvDoorStatus);
        tvBattery = findViewById(R.id.tvBattery);
        tvNfcHint = findViewById(R.id.tvNfcHint);
        ivNfcIcon = findViewById(R.id.ivNfcIcon);
        btnBleUnlock = findViewById(R.id.btnBleUnlock);
        btnLogout = findViewById(R.id.btnLogout);
        progressBar = findViewById(R.id.progressBar);

        toolbar.setTitle("SEU Door Lock");
        setSupportActionBar(toolbar);

        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String defaultMethod = SettingsActivity.getDefaultMethod(prefs);
        boolean showBleButton = SettingsActivity.METHOD_BLE.equals(defaultMethod);
        btnBleUnlock.setVisibility(showBleButton ? View.VISIBLE : View.GONE);

        String phone = cache.getPhone();
        String masked = phone.length() >= 7
            ? phone.substring(0, 3) + "****" + phone.substring(7)
            : phone;
        tvUserInfo.setText("Phone: " + masked);

        if (cache.hasDoorLock()) {
            tvBattery.setText(String.format(getString(R.string.battery_level), (int) cache.getBatteryLevel()));
            tvDoorStatus.setText("Lock: " + cache.getBuildingName());
        } else {
            tvDoorStatus.setText("Syncing door lock info...");
            tvBattery.setText("");
        }
    }

    private void setupListeners() {
        btnLogout.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setMessage(R.string.logout_confirm)
                .setPositiveButton(R.string.yes, (d, w) -> {
                    cache.clear();
                    nfcManager.onDestroy();
                    bleManager.onDestroy();
                    navigateToLogin();
                })
                .setNegativeButton(R.string.no, null)
                .show();
        });

        btnBleUnlock.setOnClickListener(v -> attemptBleUnlock());
    }

    private void attemptBleUnlock() {
        if (!bleManager.isBleSupported()) {
            Toast.makeText(this, "BLE not supported", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bleManager.isBleEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);
        tvNfcHint.setText(getString(R.string.ble_connecting));

        bleManager.unlock(new BleUnlockManager.BleCallback() {
            @Override
            public void onSuccess(String message) {
                setLoading(false);
                tvNfcHint.setText(getString(R.string.door_opened));
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                tvNfcHint.setText(getString(R.string.main_nfc_hint));
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onExpired() {
                setLoading(false);
                tvNfcHint.setText("Credential expired, refreshing...");
                refreshCredentials();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cache.hasSession()) {
            enableNfcReaderMode();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        nfcManager.disableReaderMode(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleNfcIntent(intent);
    }

    private void handleNfcIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
            || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
            || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

            setLoading(true);
            tvNfcHint.setText(getString(R.string.unlocking));

            nfcManager.enableReaderMode(this, new NfcUnlockManager.NfcCallback() {
                @Override
                public void onSuccess(com.whxinna.userplatform.model.DoorResponse response) {
                    setLoading(false);
                    tvNfcHint.setText(getString(R.string.door_opened));
                    Toast.makeText(MainActivity.this, getString(R.string.unlock_success), Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String message) {
                    setLoading(false);
                    tvNfcHint.setText(getString(R.string.main_nfc_hint));
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }

                @Override
                public void onExpired() {
                    setLoading(false);
                    tvNfcHint.setText("Credential expired, refreshing...");
                    refreshCredentials();
                }
            });
            nfcManager.handleIntent(intent);
        }
    }

    private void enableNfcReaderMode() {
        if (!nfcManager.isNfcSupported()) {
            ivNfcIcon.setAlpha(0.3f);
            tvNfcHint.setText("NFC not supported");
            return;
        }
        if (!nfcManager.isNfcEnabled()) {
            ivNfcIcon.setAlpha(0.3f);
            tvNfcHint.setText("NFC disabled");
            return;
        }

        ivNfcIcon.setAlpha(1.0f);
        nfcManager.enableReaderMode(this, new NfcUnlockManager.NfcCallback() {
            @Override
            public void onSuccess(com.whxinna.userplatform.model.DoorResponse response) {
                setLoading(false);
                tvNfcHint.setText(getString(R.string.door_opened));
                Toast.makeText(MainActivity.this, getString(R.string.unlock_success), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                setLoading(false);
                tvNfcHint.setText(getString(R.string.main_nfc_hint));
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onExpired() {
                setLoading(false);
                tvNfcHint.setText("Credential expired, refreshing...");
                refreshCredentials();
            }
        });
    }

    private void refreshCredentials() {
        credentialApi.syncDoorLockInfo(new CredentialApi.SyncCallback() {
            @Override
            public void onSuccess(DoorLockInfo info) {
                if (info.doorLock != null) {
                    tvBattery.setText(String.format(getString(R.string.battery_level), (int) info.doorLock.batteryLevel));
                }
                if (info.accommodation != null) {
                    tvDoorStatus.setText("Lock: " + info.accommodation.getDisplayName());
                }
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "Credential refresh failed: " + message);
            }
        });
    }

    private void navigateToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnBleUnlock.setEnabled(!loading);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] perms = {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
            boolean needed = false;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    needed = true;
                    break;
                }
            }
            if (needed) {
                ActivityCompat.requestPermissions(this, perms, REQUEST_PERMISSIONS);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "Some permissions denied. BLE/NFC may not work.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nfcManager.onDestroy();
        bleManager.onDestroy();
    }
}
