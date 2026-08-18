package com.midairlogn.seudoorunlock.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import com.midairlogn.seudoorunlock.AppExecutors;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SecurePrefs {

    private static final String TAG = "ZL_SecurePrefs";
    private static final String KEYSTORE_ALIAS = "zl_credential_store";
    private static final String AES_MODE = "AES/GCM/NoPadding";
    private static final String PREFS_NAME = "zl_secure_prefs";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final SharedPreferences prefs;
    private volatile SecretKey secretKey;
    private volatile boolean keyReady = false;
    private final CountDownLatch keyLatch = new CountDownLatch(1);
    private final ExecutorService executor;

    private static volatile SecurePrefs instance;

    public static SecurePrefs getInstance(Context context) {
        if (instance == null) {
            synchronized (SecurePrefs.class) {
                if (instance == null) {
                    instance = new SecurePrefs(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private SecurePrefs(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        executor = AppExecutors.getInstance();
        initKeyAsync();
    }

    private void initKeyAsync() {
        executor.execute(() -> {
            try {
                initKey();
            } finally {
                keyReady = true;
                keyLatch.countDown();
            }
        });
    }

    private void awaitKey() {
        if (keyReady) return;
        try {
            keyLatch.await();
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted waiting for key, falling back to sync init");
            Thread.currentThread().interrupt();
            if (!keyReady) initKey();
            keyReady = true;
            if (keyLatch.getCount() > 0) keyLatch.countDown();
        }
    }

    public boolean isKeyReady() {
        return keyReady;
    }

    private void initKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEYSTORE_ALIAS, null);
                secretKey = entry.getSecretKey();
            } else {
                KeyGenerator keyGen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
                keyGen.init(new KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
                secretKey = keyGen.generateKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to init keystore key", e);
            secretKey = null;
        }
    }

    public void putString(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }
        awaitKey();
        if (secretKey == null) {
            Log.e(TAG, "Encryption unavailable; refusing to store key: " + key);
            remove(key);
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            String ivStr = Base64.encodeToString(iv, Base64.NO_WRAP);
            String encryptedStr = Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs.edit().putString(key, ivStr + "|" + encryptedStr).apply();
        } catch (Exception e) {
            Log.e(TAG, "Encrypt failed for key: " + key, e);
            remove(key);
        }
    }

    public String getString(String key, String defValue) {
        String stored = prefs.getString(key, null);
        if (stored == null) return defValue;

        int separator = stored.indexOf('|');
        if (separator == -1) return defValue;

        awaitKey();
        if (secretKey == null) return defValue;
        try {
            byte[] iv = Base64.decode(stored.substring(0, separator), Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(stored.substring(separator + 1), Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance(AES_MODE);
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Decrypt failed for key: " + key, e);
        }
        return defValue;
    }

    public boolean contains(String key) {
        return prefs.contains(key);
    }

    public void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
