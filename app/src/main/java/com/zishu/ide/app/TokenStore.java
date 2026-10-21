package com.zishu.ide.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.zishu.ide.core.util.Base64Util;
import com.zishu.ide.core.util.IoUtil;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the GitHub access token encrypted with an AES-256-GCM key that lives in the Android
 * Keystore (the key material never enters the app's memory or storage). On Android 5.x (API 21-22)
 * the Keystore cannot hold AES keys, so there the token is kept in memory only and has to be
 * entered again after the app is closed; it is never written to disk in plain text.
 */
public final class TokenStore {
    private static final String KEY_ALIAS = "zishu_cloud_token_key";
    private static final String PREF = "zishu_secure";
    private static final String ITEM = "token";

    private static String memoryToken;

    private TokenStore() {
    }

    public static boolean canPersist() {
        return Build.VERSION.SDK_INT >= 23;
    }

    public static synchronized void save(Context c, String token) throws GeneralSecurityException, IOException {
        memoryToken = token;
        if (!canPersist()) {
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = cipher.doFinal(token.getBytes(IoUtil.UTF8));
        String stored = Base64Util.encode(cipher.getIV()) + ":" + Base64Util.encode(ct);
        prefs(c).edit().putString(ITEM, stored).apply();
    }

    /** @return the token, or null if none is stored or it can no longer be decrypted */
    public static synchronized String load(Context c) {
        if (memoryToken != null) {
            return memoryToken;
        }
        if (!canPersist()) {
            return null;
        }
        String stored = prefs(c).getString(ITEM, null);
        if (stored == null) {
            return null;
        }
        try {
            int colon = stored.indexOf(':');
            byte[] iv = Base64Util.decode(stored.substring(0, colon));
            byte[] ct = Base64Util.decode(stored.substring(colon + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            memoryToken = new String(cipher.doFinal(ct), IoUtil.UTF8);
            return memoryToken;
        } catch (GeneralSecurityException e) {
            clear(c); // key was invalidated (for example after a lock-screen change): ask for a new token
            return null;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            clear(c);
            return null;
        }
    }

    public static synchronized boolean has(Context c) {
        return memoryToken != null || (canPersist() && prefs(c).contains(ITEM));
    }

    public static synchronized void clear(Context c) {
        memoryToken = null;
        prefs(c).edit().remove(ITEM).apply();
        if (canPersist()) {
            try {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                ks.deleteEntry(KEY_ALIAS);
            } catch (GeneralSecurityException ignored) {
                // nothing more to remove
            } catch (IOException ignored) {
                // nothing more to remove
            }
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }
}
