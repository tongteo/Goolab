package dev.trung.colabterm;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AndroidTokenStore {
    private static final String SERVICE = "google-colab-cli";
    private static final String USERNAME = "oauth_token";
    private static final String KEY_ALIAS = "colabterm.oauth.aes";
    private static final String PREFS = "colabterm_secure_auth";
    private static final String PREF_IV = "oauth_iv";
    private static final String PREF_CIPHER = "oauth_cipher";
    private static volatile Context appContext;

    private AndroidTokenStore() { }

    public static void initialize(Context context) {
        appContext = context.getApplicationContext();
    }

    public static synchronized String getPassword(String service, String username) {
        if (!matches(service, username) || appContext == null) return null;
        SharedPreferences prefs = preferences();
        String ivText = prefs.getString(PREF_IV, null);
        String cipherText = prefs.getString(PREF_CIPHER, null);
        if (ivText == null || cipherText == null) return null;
        try {
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(cipherText, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] clear = cipher.doFinal(encrypted);
            return new String(clear, StandardCharsets.UTF_8);
        } catch (Exception invalidEntry) {
            prefs.edit().remove(PREF_IV).remove(PREF_CIPHER).apply();
            return null;
        }
    }

    public static synchronized void setPassword(String service, String username, String password)
            throws GeneralSecurityException {
        if (!matches(service, username) || appContext == null) {
            throw new GeneralSecurityException("Unsupported keyring entry or app is not initialized");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        SharedPreferences.Editor editor = preferences().edit()
                .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(PREF_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP));
        if (!editor.commit()) throw new GeneralSecurityException("Could not persist encrypted token");
    }

    public static synchronized boolean deletePassword(String service, String username) {
        if (!matches(service, username) || appContext == null) return false;
        return preferences().edit().remove(PREF_IV).remove(PREF_CIPHER).commit();
    }

    private static boolean matches(String service, String username) {
        return SERVICE.equals(service) && USERNAME.equals(username);
    }

    private static SharedPreferences preferences() {
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) {
                return (SecretKey) store.getKey(KEY_ALIAS, null);
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException("Android Keystore is unavailable", e);
        }
    }
}
