package dev.subt.trainingwidget

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Rainbow API key, encrypted with an Android Keystore AES-GCM key (alias "rainbow_key") and kept in Prefs.
 * The key never goes into the backup, the crash log or any other log. Every call is wrapped so a phone (or a test)
 * without a working Keystore just reports "no key" instead of crashing.
 */
object SecureKey {
    private const val ALIAS = "rainbow_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val K_ENC = "rw_key_enc"
    private const val K_IV = "rw_key_iv"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("training_widget", Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER)
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Encrypts and stores [plain]; false when the Keystore isn't available (nothing is stored then). */
    fun save(ctx: Context, plain: String): Boolean = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret())
        val enc = c.doFinal(plain.trim().toByteArray(Charsets.UTF_8))
        sp(ctx).edit()
            .putString(K_ENC, Base64.encodeToString(enc, Base64.NO_WRAP))
            .putString(K_IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .apply()
        true
    }.getOrDefault(false)

    fun has(ctx: Context): Boolean = sp(ctx).contains(K_ENC)

    /** The decrypted key, or null when none is saved or it can't be read. */
    fun load(ctx: Context): String? = runCatching {
        val p = sp(ctx)
        val enc = Base64.decode(p.getString(K_ENC, null) ?: return@runCatching null, Base64.NO_WRAP)
        val iv = Base64.decode(p.getString(K_IV, null) ?: return@runCatching null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
        String(c.doFinal(enc), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    /** "••••abcd": only the last 4 characters. */
    fun masked(ctx: Context): String? {
        if (!has(ctx)) return null
        val k = load(ctx) ?: return "•••• (saved, can't be read)"
        return "••••" + k.takeLast(4)
    }

    fun remove(ctx: Context) {
        sp(ctx).edit().remove(K_ENC).remove(K_IV).apply()
        runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS) }
    }
}
