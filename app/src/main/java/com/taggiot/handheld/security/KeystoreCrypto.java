package com.taggiot.handheld.security;

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
 * Enkripsi string kecil (device key, token) dengan kunci AES-256/GCM yang hidup di Android Keystore.
 * Kunci tidak pernah keluar dari Keystore dan ikut terhapus saat aplikasi di-uninstall,
 * sehingga pasang ulang memaksa pairing baru (checklist penerimaan).
 * Format tersimpan: base64(iv[12] + ciphertext+tag).
 */
public final class KeystoreCrypto {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "taggiot_handheld_secrets";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private KeystoreCrypto() {}

    private static synchronized SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(STORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    public static String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    public static String decrypt(String stored) throws Exception {
        byte[] all = Base64.decode(stored, Base64.NO_WRAP);
        if (all.length <= IV_LEN) throw new IllegalArgumentException("ciphertext terlalu pendek");
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(all, 0, iv, 0, IV_LEN);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
        byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
        return new String(pt, StandardCharsets.UTF_8);
    }
}
