package com.whxinna.userplatform.ui;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.whxinna.userplatform.R;
import com.whxinna.userplatform.SettingsActivity;
import com.whxinna.userplatform.api.CredentialApi;
import com.whxinna.userplatform.ble.BleUnlockManager;
import com.whxinna.userplatform.model.DoorLockInfo;
import com.whxinna.userplatform.nfc.NfcUnlockManager;
import com.whxinna.userplatform.storage.CredentialCache;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ZL_Main";
    private static final int REQUEST_PERMISSIONS = 100;
    private static final String PREF_SELECTED_METHOD = "selected_method";
    private static final String METHOD_NFC = "nfc";
    private static final String METHOD_BLE = "ble";

    // Views
    private ImageButton btnRefresh;
    private ImageButton btnSettings;
    private View statusIconContainer;
    private ImageView ivStatusIcon;
    private TextView tvStatusTitle;
    private TextView tvStatusDetail;
    private com.google.android.material.button.MaterialButton btnEnableNfc;
    private com.google.android.material.button.MaterialButton btnBleUnlock;
    private com.google.android.material.button.MaterialButtonToggleGroup toggleGroup;
    private com.google.android.material.button.MaterialButton btnLogout;

    // Managers
    private CredentialCache cache;
    private NfcUnlockManager nfcManager;
    private BleUnlockManager bleManager;
    private CredentialApi credentialApi;
    private SharedPreferences prefs;
    private String selectedMethod;

    // Animation
    private AnimatorSet iconAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cache = CredentialCache.getInstance(this);
        nfcManager = new NfcUnlockManager(this, cache);
        bleManager = new BleUnlockManager(this, cache);
        credentialApi = new CredentialApi(cache);
        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE);

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
        btnRefresh = findViewById(R.id.btnRefresh);
        btnSettings = findViewById(R.id.btnSettings);
        statusIconContainer = findViewById(R.id.statusIconContainer);
        ivStatusIcon = findViewById(R.id.ivStatusIcon);
        tvStatusTitle = findViewById(R.id.tvStatusTitle);
        tvStatusDetail = findViewById(R.id.tvStatusDetail);
        btnEnableNfc = findViewById(R.id.btnEnableNfc);
        btnBleUnlock = findViewById(R.id.btnBleUnlock);
        toggleGroup = findViewById(R.id.toggleGroup);
        btnLogout = findViewById(R.id.btnLogout);

        String defaultMethod = SettingsActivity.getDefaultMethod(prefs);
        selectedMethod = prefs.getString(PREF_SELECTED_METHOD, defaultMethod);
        updateTabSelection();
        updateStatusDisplay();
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> {
            refreshCredentials();
            startRefreshAnimation();
        });

        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.btnTabNfc) {
                    selectedMethod = METHOD_NFC;
                } else if (checkedId == R.id.btnTabBle) {
                    selectedMethod = METHOD_BLE;
                }
                prefs.edit().putString(PREF_SELECTED_METHOD, selectedMethod).apply();
                updateStatusDisplay();
            }
        });

        btnBleUnlock.setOnClickListener(v -> attemptBleUnlock());

        btnEnableNfc.setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_NFC_SETTINGS);
            startActivity(intent);
        });

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
    }

    private void updateTabSelection() {
        if (METHOD_NFC.equals(selectedMethod)) {
            toggleGroup.check(R.id.btnTabNfc);
        } else {
            toggleGroup.check(R.id.btnTabBle);
        }
    }

    private void updateStatusDisplay() {
        boolean isNfc = METHOD_NFC.equals(selectedMethod);

        if (isNfc) {
            ivStatusIcon.setImageResource(R.drawable.ic_nfc);
            btnBleUnlock.setVisibility(View.GONE);

            if (!nfcManager.isNfcSupported()) {
                tvStatusTitle.setText(R.string.nfc_not_supported);
                tvStatusDetail.setText("");
                btnEnableNfc.setVisibility(View.GONE);
                statusIconContainer.setAlpha(0.5f);
            } else if (!nfcManager.isNfcEnabled()) {
                tvStatusTitle.setText(R.string.nfc_disabled);
                tvStatusDetail.setText(R.string.enable_nfc_prompt);
                btnEnableNfc.setVisibility(View.VISIBLE);
                statusIconContainer.setAlpha(0.7f);
                startBreathingAnimation();
            } else {
                tvStatusTitle.setText(R.string.nfc_ready);
                tvStatusDetail.setText(R.string.main_nfc_hint);
                btnEnableNfc.setVisibility(View.GONE);
                statusIconContainer.setAlpha(1.0f);
                startBreathingAnimation();
            }
        } else {
            ivStatusIcon.setImageResource(R.drawable.ic_bluetooth);
            btnBleUnlock.setVisibility(View.VISIBLE);
            btnEnableNfc.setVisibility(View.GONE);

            if (!bleManager.isBleSupported()) {
                tvStatusTitle.setText(R.string.ble_not_supported);
                tvStatusDetail.setText("");
                statusIconContainer.setAlpha(0.5f);
                stopBreathingAnimation();
            } else if (!bleManager.isBleEnabled()) {
                tvStatusTitle.setText(R.string.ble_disabled);
                tvStatusDetail.setText(R.string.enable_ble_prompt);
                statusIconContainer.setAlpha(0.7f);
                stopBreathingAnimation();
            } else {
                tvStatusTitle.setText(R.string.ble_ready);
                tvStatusDetail.setText(R.string.main_ble_hint);
                statusIconContainer.setAlpha(1.0f);
                stopBreathingAnimation();
            }
        }

        updateDetailInfo();
    }

    private void updateDetailInfo() {
        StringBuilder detail = new StringBuilder();
        String phone = cache.getPhone();
        if (phone != null && phone.length() >= 7) {
            detail.append("Phone: ").append(phone.substring(0, 3)).append("****").append(phone.substring(7));
        } else if (phone != null) {
            detail.append("Phone: ").append(phone);
        }

        if (cache.hasDoorLock()) {
            if (detail.length() > 0) detail.append("\n");
            detail.append("Lock: ").append(cache.getBuildingName());
            if (detail.length() > 0) detail.append("\n");
            detail.append("Battery: ").append((int) cache.getBatteryLevel()).append("%");
        }

        if (detail.length() > 0) {
            tvStatusDetail.setText(detail.toString());
        }
    }

    private void startBreathingAnimation() {
        stopBreathingAnimation();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(statusIconContainer, "scaleX", 1.0f, 1.06f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(statusIconContainer, "scaleY", 1.0f, 1.06f);
        scaleX.setRepeatCount(ValueAnimator.INFINITE);
        scaleX.setRepeatMode(ValueAnimator.REVERSE);
        scaleY.setRepeatCount(ValueAnimator.INFINITE);
        scaleY.setRepeatMode(ValueAnimator.REVERSE);
        scaleX.setDuration(1500);
        scaleY.setDuration(1500);
        iconAnimator = new AnimatorSet();
        iconAnimator.playTogether(scaleX, scaleY);
        iconAnimator.start();
    }

    private void stopBreathingAnimation() {
        if (iconAnimator != null && iconAnimator.isRunning()) {
            iconAnimator.cancel();
            statusIconContainer.setScaleX(1.0f);
            statusIconContainer.setScaleY(1.0f);
        }
    }

    private void startRefreshAnimation() {
        ObjectAnimator rotation = ObjectAnimator.ofFloat(btnRefresh, "rotation", 0f, 360f);
        rotation.setDuration(800);
        rotation.setInterpolator(new DecelerateInterpolator());
        rotation.start();
    }

    private void showSuccessState() {
        ivStatusIcon.setImageResource(R.drawable.ic_check_circle);
        tvStatusTitle.setText(R.string.door_opened);
        statusIconContainer.setAlpha(1.0f);
        stopBreathingAnimation();

        statusIconContainer.setScaleX(0.8f);
        statusIconContainer.setScaleY(0.8f);
        statusIconContainer.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(400)
            .setInterpolator(new OvershootInterpolator())
            .start();

        GradientDrawable bg = (GradientDrawable) statusIconContainer.getBackground();
        bg.setColor(ContextCompat.getColor(this, R.color.success_container));
        ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.success));
    }

    private void showErrorState(String message) {
        ivStatusIcon.setImageResource(R.drawable.ic_warning);
        tvStatusTitle.setText(R.string.unlock_failed);
        tvStatusDetail.setText(message);
        statusIconContainer.setAlpha(1.0f);
        stopBreathingAnimation();

        ObjectAnimator shake = ObjectAnimator.ofFloat(statusIconContainer, "translationX", 0, 15, -15, 10, -10, 5, -5, 0);
        shake.setDuration(500);
        shake.start();

        GradientDrawable bg = (GradientDrawable) statusIconContainer.getBackground();
        bg.setColor(ContextCompat.getColor(this, R.color.error_container));
        ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.error));
    }

    private void attemptBleUnlock() {
        if (!bleManager.isBleSupported()) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bleManager.isBleEnabled()) {
            Toast.makeText(this, R.string.ble_disabled, Toast.LENGTH_SHORT).show();
            return;
        }

        tvStatusTitle.setText(R.string.ble_connecting);
        tvStatusDetail.setText("");
        btnBleUnlock.setEnabled(false);

        bleManager.unlock(new BleUnlockManager.BleCallback() {
            @Override
            public void onSuccess(String message) {
                showSuccessState();
                btnBleUnlock.setEnabled(true);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                showErrorState(message);
                btnBleUnlock.setEnabled(true);
            }

            @Override
            public void onExpired() {
                tvStatusTitle.setText(R.string.session_expired);
                refreshCredentials();
                btnBleUnlock.setEnabled(true);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cache.hasSession()) {
            enableNfcReaderMode();
            updateStatusDisplay();
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

            if (!METHOD_NFC.equals(selectedMethod)) {
                return;
            }

            tvStatusTitle.setText(R.string.unlocking);
            tvStatusDetail.setText("");

            nfcManager.enableReaderMode(this, new NfcUnlockManager.NfcCallback() {
                @Override
                public void onSuccess(com.whxinna.userplatform.model.DoorResponse response) {
                    showSuccessState();
                    Toast.makeText(MainActivity.this, R.string.unlock_success, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String message) {
                    showErrorState(message);
                }

                @Override
                public void onExpired() {
                    tvStatusTitle.setText(R.string.session_expired);
                    refreshCredentials();
                }
            });
            nfcManager.handleIntent(intent);
        }
    }

    private void enableNfcReaderMode() {
        if (!nfcManager.isNfcSupported() || !nfcManager.isNfcEnabled()) {
            return;
        }

        nfcManager.enableReaderMode(this, new NfcUnlockManager.NfcCallback() {
            @Override
            public void onSuccess(com.whxinna.userplatform.model.DoorResponse response) {
                showSuccessState();
                Toast.makeText(MainActivity.this, R.string.unlock_success, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                showErrorState(message);
            }

            @Override
            public void onExpired() {
                tvStatusTitle.setText(R.string.session_expired);
                refreshCredentials();
            }
        });
    }

    private void refreshCredentials() {
        credentialApi.syncDoorLockInfo(new CredentialApi.SyncCallback() {
            @Override
            public void onSuccess(DoorLockInfo info) {
                runOnUiThread(() -> updateDetailInfo());
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
            updateStatusDisplay();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBreathingAnimation();
        nfcManager.onDestroy();
        bleManager.onDestroy();
    }
}
