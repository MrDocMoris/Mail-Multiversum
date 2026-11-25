package de.mrdocmoris.mail;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Speichert die Zugangsdaten. Das Passwort wird mit einem Schlüssel aus dem
 * Android Keystore verschlüsselt (AES-256-GCM). Der Schlüssel selbst verlässt
 * das Gerät nie – auch nicht über Backups.
 */
final class SecureStore {
    private static final String PREFS = "mail_multiversum";
    private static final String ALIAS = "mail_multiversum_key";

    private SecureStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    static void saveLogin(Context c, String email, String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        String enc = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        prefs(c).edit().putString("email", email).putString("pw", enc).apply();
    }

    static String email(Context c) {
        return prefs(c).getString("email", null);
    }

    static String password(Context c) {
        String enc = prefs(c).getString("pw", null);
        if (enc == null) return null;
        try {
            String[] p = enc.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null; // z. B. Schlüssel nach Gerätewechsel nicht mehr vorhanden
        }
    }

    static boolean hasLogin(Context c) {
        return email(c) != null && password(c) != null;
    }

    static void clear(Context c) {
        prefs(c).edit().clear().apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignore) {}
    }
}
