package com.whxinna.userplatform.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SecurePrefs {

    private static final String TAG = "ZL_SecurePrefs";
    private static final String KEYSTORE_ALIAS = "zl_credential_store";
    private static final String AES_MODE = "AES/GCM/NoPadding";
    private static final String PREFS_NAME = "zl_secure_prefs";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final SharedPreferences prefs;
    private SecretKey secretKey;

    private static SecurePrefs instance;

    public static synchronized SecurePrefs getInstance(Context context) {
        if (instance == null) {
            instance = new SecurePrefs(context.getApplicationContext());
        }
        return instance;
    }

    private SecurePrefs(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initKey();
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
            // Fallback: derive key from hardcoded value (less secure but functional)
            byte[] fallbackKey = new byte[32];
            byte[] seed = "ZLDoorLock2024SecureKey!!".getBytes();
            System.arraycopy(seed, 0, fallbackKey, 0, Math.min(seed.length, 32));
            secretKey = new SecretKeySpec(fallbackKey, "AES");
        }
    }

    public void putString(String key, String value) {
        try {
            Cipher cipher = Cipher.getInstance(AES_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            String encoded = Base64.encodeToString(iv, Base64.NO_WRAP) + "|" +
                           Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs.edit().putString(key, encoded).apply();
        } catch (Exception e) {
            Log.e(TAG, "Encrypt failed for key: " + key, e);
            prefs.edit().putString(key, value).apply();
        }
    }

    public String getString(String key, String defValue) {
        String stored = prefs.getString(key, null);
        if (stored == null) return defValue;

        try {
            if (stored.contains("|")) {
                String[] parts = stored.split("\\|", 2);
                byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
                byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);

                Cipher cipher = Cipher.getInstance(AES_MODE);
                GCMParameterSpec spec = new GCMParameterSpec(128, iv);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
                byte[] decrypted = cipher.doFinal(encrypted);
                return new String(decrypted, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.e(TAG, "Decrypt failed for key: " + key, e);
        }
        return stored;
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
