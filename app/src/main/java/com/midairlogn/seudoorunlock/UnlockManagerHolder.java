package com.midairlogn.seudoorunlock;

import android.content.Context;

import com.midairlogn.seudoorunlock.ble.BleUnlockManager;
import com.midairlogn.seudoorunlock.nfc.NfcUnlockManager;
import com.midairlogn.seudoorunlock.storage.CredentialCache;

/**
 * Application-scoped holder for the unlock managers so in-flight NFC/BLE
 * transactions survive activity recreation (rotation, theme change, etc.).
 * Activities attach their callbacks on resume; results are delivered to the
 * currently attached activity instance.
 */
public final class UnlockManagerHolder {

    private static volatile UnlockManagerHolder instance;

    private final NfcUnlockManager nfcManager;
    private final BleUnlockManager bleManager;

    private UnlockManagerHolder(Context context) {
        Context appContext = context.getApplicationContext();
        CredentialCache cache = CredentialCache.getInstance(appContext);
        nfcManager = new NfcUnlockManager(appContext, cache);
        bleManager = new BleUnlockManager(appContext, cache);
    }

    public static UnlockManagerHolder getInstance(Context context) {
        if (instance == null) {
            synchronized (UnlockManagerHolder.class) {
                if (instance == null) {
                    instance = new UnlockManagerHolder(context);
                }
            }
        }
        return instance;
    }

    public NfcUnlockManager nfcManager() {
        return nfcManager;
    }

    public BleUnlockManager bleManager() {
        return bleManager;
    }
}
