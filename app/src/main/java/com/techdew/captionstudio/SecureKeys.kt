package com.techdew.captionstudio

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
 * API keys are never stored in plain text and never shipped inside the APK.
 * Each user types their own (free) key once; it is encrypted with an AES-256-GCM key that lives in the
 * Android Keystore (hardware-backed where available and not exportable), and only the ciphertext is saved.
 * If the Keystore is broken on a device (some cheap ROMs), the key is kept in memory for this session only.
 */
class SecureKeys(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("secure_keys", Context.MODE_PRIVATE)
    private val memory = HashMap<String, String>()

    /** True when keys can be persisted securely on this device. */
    var persistent: Boolean = true
        private set

    fun has(name: String): Boolean = !get(name).isNullOrEmpty()

    fun get(name: String): String? {
        memory[name]?.let { return it }
        val stored = sp.getString(name, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            null
        }
    }

    fun set(name: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) {
            remove(name)
            return
        }
        try {
            sp.edit().putString(name, encrypt(v)).apply()
            memory.remove(name)
            persistent = true
        } catch (e: Exception) {
            memory[name] = v
            persistent = false
        }
    }

    fun remove(name: String) {
        memory.remove(name)
        sp.edit().remove(name).apply()
    }

    /** "••••AB12" style hint so the full key is never shown again. */
    fun hint(name: String): String {
        val k = get(name) ?: return ""
        return if (k.length <= 4) "••••" else "••••" + k.takeLast(4)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "captionstudio_api_keys"
        const val GEMINI = "gemini_key"
        const val GROQ = "groq_key"
    }
}
