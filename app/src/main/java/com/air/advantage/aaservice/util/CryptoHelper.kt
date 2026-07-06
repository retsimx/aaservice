package com.air.advantage.aaservice.util

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {

    private const val KEY_BASE64 = "+07UDwu4yLmTkTpOYxe9Vc4K/2slMFRWrcvN2tuFxvc="

    private val key: ByteArray by lazy {
        try {
            val decodedKey = Base64.decode(KEY_BASE64, Base64.NO_WRAP)
            if (decodedKey != null) {
                if (decodedKey.size >= 32) decodedKey.copyOf(32) else decodedKey + ByteArray(32 - decodedKey.size)
            } else {
                ByteArray(32)
            }
        } catch (e: Throwable) {
            ByteArray(32)
        }
    }
    private val iv: ByteArray = ByteArray(16)

    @JvmStatic
    fun encrypt(plaintext: ByteArray?): ByteArray? {
        if (plaintext == null) return null
        val random = SecureRandom()
        val randomBytes = ByteArray(3)
        random.nextBytes(randomBytes)

        val randomChars = Base64.encodeToString(randomBytes, Base64.NO_WRAP).take(3)
        val randomPrefix = randomChars.toByteArray(Charsets.UTF_8)

        val padded = randomPrefix + plaintext

        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(padded)

        return Base64.encode(encrypted, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    @JvmStatic
    fun decrypt(ciphertext: ByteArray?): ByteArray? {
        if (ciphertext == null) return null
        val decoded = Base64.decode(ciphertext, Base64.NO_WRAP or Base64.URL_SAFE)
        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(decoded)

        return decrypted.copyOfRange(3, decrypted.size)
    }
}