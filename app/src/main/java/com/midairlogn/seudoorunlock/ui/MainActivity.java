package com.midairlogn.seudoorunlock.ui;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.Settings;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.midairlogn.seudoorunlock.R;
import com.midairlogn.seudoorunlock.SettingsActivity;
import com.midairlogn.seudoorunlock.api.AuthApi;
import com.midairlogn.seudoorunlock.api.CredentialApi;
import com.midairlogn.seudoorunlock.ble.BleUnlockManager;
import com.midairlogn.seudoorunlock.model.DoorLockInfo;
import com.midairlogn.seudoorunlock.nfc.NfcUnlockManager;
import com.midairlogn.seudoorunlock.storage.CredentialCache;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ZL_Main";
    private static final String PREF_SELECTED_METHOD = "selected_method";
    private static final String METHOD_NFC = "nfc";
    private static final String METHOD_BLE = "ble";
    private static final long AUTO_CLOSE_DELAY_MS = 3_000;
    private static final String STATE_AUTO_CLOSE_DEADLINE = "auto_close_deadline_uptime";

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (!hasAllPermissions() && !shouldShowRationale()) {
                    showSettingsDialog();
                }
                updateStatusDisplay();
            });

    // Views
    private ImageButton btnRefresh;
    private ImageButton btnSettings;
    private ImageButton btnInfo;
    private View statusIconContainer;
    private ImageView ivStatusIcon;
    private TextView tvStatusTitle;
    private TextView tvStatusDetail;
    private com.google.android.material.button.MaterialButton btnEnableNfc;
    private com.google.android.material.button.MaterialButton btnBleUnlock;
    private com.google.android.material.button.MaterialButton btnGrantPermissions;
    private com.google.android.material.button.MaterialButtonToggleGroup toggleGroup;
    private com.google.android.material.button.MaterialButton btnLogout;

    // Managers
    private CredentialCache cache;
    private NfcUnlockManager nfcManager;
    private BleUnlockManager bleManager;
    private CredentialApi credentialApi;
    private AuthApi authApi;
    private SharedPreferences prefs;
    private String selectedMethod;

    private NfcUnlockManager getNfcManager() {
        if (nfcManager == null) {
            nfcManager = new NfcUnlockManager(this, cache);
        }
        return nfcManager;
    }

    private BleUnlockManager getBleManager() {
        if (bleManager == null) {
            bleManager = new BleUnlockManager(this, cache);
        }
        return bleManager;
    }

    private CredentialApi getCredentialApi() {
        if (credentialApi == null) {
            credentialApi = new CredentialApi(cache);
        }
        return credentialApi;
    }

    private AuthApi getAuthApi() {
        if (authApi == null) {
            authApi = new AuthApi(cache);
        }
        return authApi;
    }

    // NFC state
    private BroadcastReceiver nfcStateReceiver;
    private long lastNfcStateChangeTime = 0;
    private static final long NFC_STATE_DEBOUNCE_MS = 500;
    private boolean isResumed = false;
    private boolean isBusy = false;
    private boolean reloginPromptShowing = false;

    private final NfcUnlockManager.NfcCallback nfcCallback = new NfcUnlockManager.NfcCallback() {
        @Override
        public void onSuccess(com.midairlogn.seudoorunlock.model.DoorResponse response) {
            if (response != null) {
                showSuccessState();
                if (!scheduleAutoClose()) {
                    showToast(R.string.unlock_success, Toast.LENGTH_SHORT);
                }
            } else {
                // Activation completed
                updateStatusDisplay();
                showToast(R.string.activation_success, Toast.LENGTH_SHORT);
            }
            setBusy(false);
        }

        @Override
        public void onError(String message) {
            showErrorState(message);
            setBusy(false);
        }

        @Override
        public void onExpired() {
            cancelAutoClose(false);
            tvStatusTitle.setText(R.string.session_expired);
            refreshCredentials();
            setBusy(false);
        }
    };

    // Animation
    private AnimatorSet iconAnimator;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    private Toast activeToast;
    private Runnable restoreRunnable;
    private Runnable autoCloseRunnable;
    private long autoCloseDeadlineUptime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cache = CredentialCache.getInstance(this);
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
        restoreAutoClose(savedInstanceState);
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
        btnInfo = findViewById(R.id.btnInfo);
        statusIconContainer = findViewById(R.id.statusIconContainer);
        ivStatusIcon = findViewById(R.id.ivStatusIcon);
        tvStatusTitle = findViewById(R.id.tvStatusTitle);
        tvStatusDetail = findViewById(R.id.tvStatusDetail);
        btnEnableNfc = findViewById(R.id.btnEnableNfc);
        btnBleUnlock = findViewById(R.id.btnBleUnlock);
        btnGrantPermissions = findViewById(R.id.btnGrantPermissions);
        toggleGroup = findViewById(R.id.toggleGroup);
        btnLogout = findViewById(R.id.btnLogout);

        selectedMethod = prefs.getString(PREF_SELECTED_METHOD, METHOD_NFC);
        updateTabSelection();
        updateStatusDisplay();
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> {
            refreshCredentials();
            startRefreshAnimation();
        });

        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        btnInfo.setOnClickListener(v -> showCredentialInfoDialog());

        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.btnTabNfc) {
                    selectedMethod = METHOD_NFC;
                    enableNfcReaderModeIfIdle();
                } else if (checkedId == R.id.btnTabBle) {
                    selectedMethod = METHOD_BLE;
                    getNfcManager().disableReaderMode(this);
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

        btnGrantPermissions.setOnClickListener(v -> requestPermissions());

        btnLogout.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.yes, (d, w) -> {
                cache.clear();
                if (nfcManager != null) nfcManager.onDestroy();
                if (bleManager != null) bleManager.onDestroy();
                navigateToLogin();
            })
            .setNegativeButton(R.string.no, null)
            .show());
    }

    private void updateTabSelection() {
        switch (selectedMethod) {
            case METHOD_NFC:
                toggleGroup.check(R.id.btnTabNfc);
                break;
            case METHOD_BLE:
            default:
                toggleGroup.check(R.id.btnTabBle);
                break;
        }
    }

    private void registerNfcStateReceiver() {
        if (nfcStateReceiver != null) return;
        IntentFilter filter = new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        nfcStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF);
                if (state == NfcAdapter.STATE_ON || state == NfcAdapter.STATE_OFF) {
                    long now = System.currentTimeMillis();
                    if (now - lastNfcStateChangeTime < NFC_STATE_DEBOUNCE_MS) return;
                    lastNfcStateChangeTime = now;
                    
                    runOnUiThread(() -> {
                        updateStatusDisplay();
                        if (METHOD_NFC.equals(selectedMethod)
                                && state == NfcAdapter.STATE_ON) {
                            enableNfcReaderModeIfIdle();
                        } else {
                            getNfcManager().disableReaderMode(MainActivity.this);
                        }
                    });
                }
            }
        };
        // Use RECEIVER_EXPORTED for system broadcasts on API 33+
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU 
                ? ContextCompat.RECEIVER_EXPORTED 
                : 0;
        ContextCompat.registerReceiver(this, nfcStateReceiver, filter, flags);
    }

    private void unregisterNfcStateReceiver() {
        if (nfcStateReceiver != null) {
            unregisterReceiver(nfcStateReceiver);
            nfcStateReceiver = null;
        }
    }

    private void updateStatusDisplay() {
        // Reset background tint from success/error states
        android.graphics.drawable.LayerDrawable layerBg = (android.graphics.drawable.LayerDrawable) statusIconContainer.getBackground().mutate();
        ((GradientDrawable) layerBg.getDrawable(0)).setColor(ContextCompat.getColor(this, R.color.primary_container));

        if (!hasAllPermissions()) {
            ivStatusIcon.setImageResource(R.drawable.ic_warning);
            tvStatusTitle.setText(R.string.permission_required);
            tvStatusDetail.setText(R.string.permission_denied_detail);
            btnEnableNfc.setVisibility(View.GONE);
            btnBleUnlock.setVisibility(View.GONE);
            btnGrantPermissions.setVisibility(View.VISIBLE);
            statusIconContainer.setAlpha(0.7f);
            stopBreathingAnimation();
            ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary));
            return;
        }

        btnGrantPermissions.setVisibility(View.GONE);

        boolean isNfc = METHOD_NFC.equals(selectedMethod);
        boolean isReady = false;

        if (isNfc) {
            ivStatusIcon.setImageResource(R.drawable.ic_nfc);
            btnBleUnlock.setVisibility(View.GONE);

            if (!getNfcManager().isNfcSupported()) {
                tvStatusTitle.setText(R.string.nfc_not_supported);
                tvStatusDetail.setText("");
                btnEnableNfc.setVisibility(View.GONE);
                statusIconContainer.setAlpha(0.5f);
            } else {
                if (!getNfcManager().isNfcEnabled()) {
                    tvStatusTitle.setText(R.string.nfc_disabled);
                    tvStatusDetail.setText(R.string.enable_nfc_prompt);
                    btnEnableNfc.setVisibility(View.VISIBLE);
                    statusIconContainer.setAlpha(0.7f);
                } else {
                    tvStatusTitle.setText(R.string.nfc_ready);
                    tvStatusDetail.setText(R.string.main_nfc_hint);
                    btnEnableNfc.setVisibility(View.GONE);
                    statusIconContainer.setAlpha(1.0f);
                    isReady = true;
                }
            }
        } else {
            ivStatusIcon.setImageResource(R.drawable.ic_bluetooth);
            btnBleUnlock.setVisibility(View.VISIBLE);
            btnEnableNfc.setVisibility(View.GONE);

            if (!getBleManager().isBleSupported()) {
                tvStatusTitle.setText(R.string.ble_not_supported);
                tvStatusDetail.setText("");
                statusIconContainer.setAlpha(0.5f);
            } else {
                if (!getBleManager().isBleEnabled()) {
                    tvStatusTitle.setText(R.string.ble_disabled);
                    tvStatusDetail.setText(R.string.enable_ble_prompt);
                    statusIconContainer.setAlpha(0.7f);
                } else {
                    tvStatusTitle.setText(R.string.ble_ready);
                    tvStatusDetail.setText(R.string.main_ble_hint);
                    statusIconContainer.setAlpha(1.0f);
                    isReady = true;
                }
            }
        }

        if (isReady) {
            boolean activationPending = cache.requiresDigitalCredentialActivation();
            if (activationPending) {
                stopBreathingAnimation();
                if (isNfc) {
                    tvStatusTitle.setText(R.string.activation_pending);
                    tvStatusDetail.setText(R.string.activation_pending_nfc_hint);
                } else {
                    tvStatusTitle.setText(R.string.activation_pending);
                    tvStatusDetail.setText(R.string.activation_pending_ble_hint);
                    btnBleUnlock.setEnabled(true);
                }
            } else {
                startBreathingAnimation();
            }
        } else {
            stopBreathingAnimation();
        }

        ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary));
        updateDetailInfo();
    }

    private void updateDetailInfo() {
        StringBuilder detail = new StringBuilder();
        String phone = cache.getPhone();
        if (phone != null && phone.length() >= 5) {
            detail.append(getString(R.string.detail_phone, phone.substring(0, 3) + "*****" + phone.substring(phone.length() - 2)));
        } else if (phone != null) {
            detail.append(getString(R.string.detail_phone, phone));
        }

        if (cache.hasDoorLock()) {
            if (detail.length() > 0) detail.append("\n");
            if (cache.requiresDigitalCredentialActivation()) {
                detail.append(getString(R.string.activation_pending_detail));
            } else {
                detail.append(getString(R.string.detail_lock, cache.getBuildingName()));
                detail.append("\n");
                detail.append(getString(R.string.detail_battery, (int) cache.getBatteryLevel()));
            }
        }

        if (detail.length() > 0) {
            tvStatusDetail.setText(detail.toString());
        }
    }

    private void showCredentialInfoDialog() {
        int credentialId = cache.getCredentialId();
        String credential = cache.getCredentialHex();

        if (credential.isEmpty() && credentialId == 0) {
            showToast(R.string.err_credential_unavailable, Toast.LENGTH_SHORT);
            return;
        }

        String message = getString(R.string.credential_info_content, credentialId,
                credential.isEmpty() ? getString(R.string.activation_pending_detail) : credential);

        new AlertDialog.Builder(this)
            .setTitle(R.string.credential_info_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.btn_copy, (dialog, which) -> copyCredential(credential))
            .show();
    }

    private void copyCredential(String credential) {
        if (credential == null || credential.isEmpty()) {
            showToast(R.string.err_credential_unavailable, Toast.LENGTH_SHORT);
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText("credential", credential);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        showToast(R.string.copied, Toast.LENGTH_SHORT);
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

        android.graphics.drawable.LayerDrawable layerBg = (android.graphics.drawable.LayerDrawable) statusIconContainer.getBackground().mutate();
        GradientDrawable bg = (GradientDrawable) layerBg.getDrawable(0);
        bg.setColor(ContextCompat.getColor(this, R.color.success_container));
        ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.success));

        // Restore to original state after 10 seconds (door auto-locks)
        if (restoreRunnable != null) {
            handler.removeCallbacks(restoreRunnable);
        }
        restoreRunnable = () -> updateStatusDisplay();
        handler.postDelayed(restoreRunnable, 10_000);
    }

    private void showErrorState(String message) {
        cancelAutoClose(false);
        ivStatusIcon.setImageResource(R.drawable.ic_warning);
        tvStatusTitle.setText(R.string.unlock_failed);
        tvStatusDetail.setText(message);
        statusIconContainer.setAlpha(1.0f);
        stopBreathingAnimation();

        ObjectAnimator shake = ObjectAnimator.ofFloat(statusIconContainer, "translationX", 0, 15, -15, 10, -10, 5, -5, 0);
        shake.setDuration(500);
        shake.start();

        android.graphics.drawable.LayerDrawable layerBg = (android.graphics.drawable.LayerDrawable) statusIconContainer.getBackground().mutate();
        GradientDrawable bg = (GradientDrawable) layerBg.getDrawable(0);
        bg.setColor(ContextCompat.getColor(this, R.color.error_container));
        ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.error));
    }

    private boolean scheduleAutoClose() {
        if (!prefs.getBoolean(SettingsActivity.KEY_AUTO_CLOSE, true)) return false;
        cancelAutoClose(false);
        autoCloseDeadlineUptime = SystemClock.uptimeMillis() + AUTO_CLOSE_DELAY_MS;
        autoCloseRunnable = this::closeApp;
        handler.postDelayed(autoCloseRunnable, AUTO_CLOSE_DELAY_MS);
        showToast(R.string.unlock_success_auto_close, Toast.LENGTH_LONG,
                (int) (AUTO_CLOSE_DELAY_MS / 1000));
        return true;
    }

    private void restoreAutoClose(Bundle savedInstanceState) {
        if (savedInstanceState == null
                || !prefs.getBoolean(SettingsActivity.KEY_AUTO_CLOSE, true)) return;

        long deadline = savedInstanceState.getLong(STATE_AUTO_CLOSE_DEADLINE, 0);
        if (deadline <= 0) return;

        showSuccessState();
        autoCloseDeadlineUptime = deadline;
        long remaining = deadline - SystemClock.uptimeMillis();
        if (remaining <= 0) {
            closeApp();
            return;
        }

        autoCloseRunnable = this::closeApp;
        handler.postDelayed(autoCloseRunnable, remaining);
        showToast(R.string.auto_close_countdown, Toast.LENGTH_LONG,
                (int) Math.ceil(remaining / 1000.0));
    }

    private void cancelAutoClose(boolean notify) {
        if (autoCloseRunnable != null) {
            handler.removeCallbacks(autoCloseRunnable);
        }
        autoCloseRunnable = null;
        autoCloseDeadlineUptime = 0;
        if (notify) {
            showToast(R.string.auto_close_cancelled, Toast.LENGTH_SHORT);
        }
    }

    private void showToast(int messageResId, int duration, Object... formatArgs) {
        if (activeToast != null) {
            activeToast.cancel();
        }
        activeToast = Toast.makeText(this, getString(messageResId, formatArgs), duration);
        activeToast.show();
    }

    private void showToast(CharSequence message, int duration) {
        if (activeToast != null) {
            activeToast.cancel();
        }
        activeToast = Toast.makeText(this, message, duration);
        activeToast.show();
    }

    private void closeApp() {
        autoCloseRunnable = null;
        autoCloseDeadlineUptime = 0;
        if (activeToast != null) {
            activeToast.cancel();
            activeToast = null;
        }
        Log.d(TAG, "Auto-closing app after successful unlock");
        finishAndRemoveTask();
        finishAffinity();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (autoCloseRunnable != null) {
            outState.putLong(STATE_AUTO_CLOSE_DEADLINE, autoCloseDeadlineUptime);
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (autoCloseRunnable != null) {
            cancelAutoClose(true);
        }
    }

    private void attemptBleUnlock() {
        if (!getBleManager().isBleSupported()) {
            showToast(R.string.ble_not_supported, Toast.LENGTH_SHORT);
            return;
        }
        if (!getBleManager().isBleEnabled()) {
            showToast(R.string.ble_disabled, Toast.LENGTH_SHORT);
            return;
        }

        tvStatusTitle.setText(R.string.ble_connecting);
        btnBleUnlock.setEnabled(false);

        getBleManager().unlock(new BleUnlockManager.BleCallback() {
            @Override
            public void onSuccess(String message) {
                showSuccessState();
                btnBleUnlock.setEnabled(true);
                if (!scheduleAutoClose()) {
                    showToast(message, Toast.LENGTH_SHORT);
                }
            }

            @Override
            public void onActivationSuccess(String message) {
                updateStatusDisplay();
                btnBleUnlock.setEnabled(true);
                showToast(R.string.activation_success, Toast.LENGTH_SHORT);
            }

            @Override
            public void onError(String message) {
                showErrorState(message);
                btnBleUnlock.setEnabled(true);
            }

            @Override
            public void onExpired() {
                cancelAutoClose(false);
                tvStatusTitle.setText(R.string.session_expired);
                refreshCredentials();
                btnBleUnlock.setEnabled(true);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        isResumed = true;
        if (cache.hasSession()) {
            registerNfcStateReceiver();
            enableNfcReaderModeIfIdle();
            if (autoCloseRunnable == null) updateStatusDisplay();
        }
    }

    private boolean hasAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    @Override
    protected void onPause() {
        isResumed = false;
        super.onPause();
        unregisterNfcStateReceiver();
        getNfcManager().disableReaderMode(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleNfcIntent(intent);
    }

    private void handleNfcIntent(Intent intent) {
        if (intent == null || isBusy) return;
        String action = intent.getAction();
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)
            || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
            || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {

            Tag tag = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent, NfcAdapter.EXTRA_TAG, Tag.class);
            if (tag == null) return;

            if (!METHOD_NFC.equals(selectedMethod)) {
                selectedMethod = METHOD_NFC;
                updateTabSelection();
            }

            tvStatusTitle.setText(R.string.unlocking);
            tvStatusDetail.setText("");

            // Don't enable reader mode here; it resets the NFC controller and
            // interrupts the NfcA session from the intent tag. Reader mode will
            // be re-enabled after processing completes via setBusy(false).
            setBusy(true);
            if (!getNfcManager().handleIntent(intent, nfcCallback)) {
                setBusy(false);
            }
        }
    }

    private void setBusy(boolean busy) {
        isBusy = busy;
        if (!busy) enableNfcReaderModeIfIdle();
    }

    private void enableNfcReaderModeIfIdle() {
        if (!isResumed || isBusy) return;
        if (METHOD_NFC.equals(selectedMethod) && hasAllPermissions()) {
            enableNfcReaderMode();
        }
    }

    private void enableNfcReaderMode() {
        if (!getNfcManager().isNfcSupported() || !getNfcManager().isNfcEnabled()) {
            return;
        }
        getNfcManager().enableReaderMode(this, nfcCallback);
    }

    private void refreshCredentials() {
        getCredentialApi().syncDoorLockInfo(new CredentialApi.SyncCallback() {
            @Override
            public void onSuccess(DoorLockInfo info) {
                runOnUiThread(() -> updateDetailInfo());
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "Credential refresh failed: " + message);
                if (message == null || !message.contains("api_sign_error")) {
                    showToast(message != null ? message : "Credential refresh failed", Toast.LENGTH_SHORT);
                    return;
                }

                Log.w(TAG, "Session secret rejected after retry, trying full re-login");
                String phone = cache.getPhone();
                String password = cache.getPassword();
                if (phone.isEmpty() || password.isEmpty()) {
                    Log.w(TAG, "No stored credentials for fallback re-login");
                    runOnUiThread(MainActivity.this::promptReLogin);
                    return;
                }
                getAuthApi().autoReLogin(cache, new AuthApi.AuthCallback() {
                    @Override
                    public void onSuccess(com.midairlogn.seudoorunlock.model.LoginResponse response) {
                        Log.d(TAG, "Re-login succeeded, re-syncing credentials");
        getCredentialApi().syncDoorLockInfo(new CredentialApi.SyncCallback() {
                            @Override
                            public void onSuccess(DoorLockInfo info) {
                                runOnUiThread(() -> updateDetailInfo());
                            }
                            @Override
                            public void onError(String msg) {
                                Log.e(TAG, "Credential sync after re-login failed: " + msg);
                                runOnUiThread(MainActivity.this::promptReLogin);
                            }
                        });
                    }

                    @Override
                    public void onCaptchaRequired() {
                        Log.w(TAG, "Captcha required during re-login fallback");
                        runOnUiThread(MainActivity.this::promptReLogin);
                    }

                    @Override
                    public void onError(String msg) {
                        Log.e(TAG, "Fallback re-login failed: " + msg);
                        runOnUiThread(MainActivity.this::promptReLogin);
                    }
                });
            }
        });
    }

    private void navigateToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void promptReLogin() {
        if (isFinishing() || isDestroyed() || reloginPromptShowing) return;
        cancelAutoClose(false);
        reloginPromptShowing = true;
        tvStatusTitle.setText(R.string.session_expired_relogin_required);
        tvStatusDetail.setText(R.string.session_expired_relogin_detail);
        new AlertDialog.Builder(this)
            .setTitle(R.string.session_expired_relogin_required)
            .setMessage(R.string.session_expired_relogin_detail)
            .setPositiveButton(R.string.btn_login, (dialog, which) -> {
                cache.clear();
                navigateToLogin();
            })
            .setNegativeButton(R.string.btn_cancel, (dialog, which) -> reloginPromptShowing = false)
            .setOnCancelListener(dialog -> reloginPromptShowing = false)
            .show();
    }

    private void requestPermissions() {
        if (hasAllPermissions()) return;

        if (shouldShowRationale()) {
            showPermissionRationaleDialog();
        } else {
            permissionLauncher.launch(getRequiredPermissions());
        }
    }

    private String[] getRequiredPermissions() {
        java.util.List<String> perms = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return perms.toArray(new String[0]);
    }

    private boolean shouldShowRationale() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.BLUETOOTH_SCAN) ||
                   ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            return ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private void showPermissionRationaleDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_rationale_title)
                .setMessage(R.string.permission_rationale_message)
                .setPositiveButton(R.string.grant_permissions, (dialog, which) -> 
                        permissionLauncher.launch(getRequiredPermissions()))
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void showSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_required)
                .setMessage(R.string.permission_settings_message)
                .setPositiveButton(R.string.btn_go_to_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (activeToast != null) {
            activeToast.cancel();
            activeToast = null;
        }
        super.onDestroy();
        if (restoreRunnable != null) {
            handler.removeCallbacks(restoreRunnable);
        }
        cancelAutoClose(false);
        stopBreathingAnimation();
        if (nfcManager != null) nfcManager.onDestroy();
        if (bleManager != null) bleManager.onDestroy();
    }
}
