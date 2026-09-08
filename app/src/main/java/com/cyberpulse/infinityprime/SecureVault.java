package com.cyberpulse.infinityprime;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureVault {
    private static final String KEY_ALIAS = "InfinityPrimeVaultKey";
    private static final String STORE = "infinity_prime_vault";
    private final SharedPreferences prefs;

    public SecureVault(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    public synchronized void put(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            prefs.edit().remove(name).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            ByteBuffer buf = ByteBuffer.allocate(4 + iv.length + encrypted.length);
            buf.putInt(iv.length).put(iv).put(encrypted);
            prefs.edit().putString(name, Base64.encodeToString(buf.array(), Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Vault write failed", e);
        }
    }

    public synchronized String get(String name) {
        String raw = prefs.getString(name, "");
        if (raw == null || raw.isEmpty()) return "";
        try {
            byte[] packed = Base64.decode(raw, Base64.NO_WRAP);
            ByteBuffer buf = ByteBuffer.wrap(packed);
            int ivLen = buf.getInt();
            if (ivLen < 12 || ivLen > 32) return "";
            byte[] iv = new byte[ivLen];
            buf.get(iv);
            byte[] encrypted = new byte[buf.remaining()];
            buf.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public boolean has(String name) {
        return !get(name).isEmpty();
    }

    public void remove(String name) {
        prefs.edit().remove(name).apply();
    }
}
