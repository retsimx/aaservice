package com.air.advantage.aaservice.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CryptoHelperTest {
    @Test
    fun encrypt_returnsNonNullResult() {
        val plaintext = "Hello World".toByteArray()
        val encrypted = CryptoHelper.encrypt(plaintext)
        assertNotNull(encrypted)
    }

    @Test
    fun encrypt_producesByteArrayOfExpectedLength() {
        val plaintext = "Test data".toByteArray()
        val encrypted = CryptoHelper.encrypt(plaintext)
        assertTrue(encrypted!!.isNotEmpty())
        assertTrue(encrypted.size > plaintext.size)
    }

    @Test
    fun encrypt_outputIsBase64UrlSafeEncoded() {
        val plaintext = "Hello World".toByteArray()
        val encrypted = CryptoHelper.encrypt(plaintext)
        val encoded = String(encrypted!!)
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))
    }

    @Test
    fun encrypt_producesDifferentOutputEachTime() {
        val plaintext = "Test data".toByteArray()
        val encrypted1 = CryptoHelper.encrypt(plaintext)
        val encrypted2 = CryptoHelper.encrypt(plaintext)
        assertFalse(encrypted1!!.contentEquals(encrypted2!!))
    }

    @Test
    fun encryptAndDecrypt_roundTrip() {
        val plaintext = "Hello World".toByteArray()
        val encrypted = CryptoHelper.encrypt(plaintext)
        val decrypted = CryptoHelper.decrypt(encrypted!!)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun encrypt_withEmptyInput() {
        val plaintext = ByteArray(0)
        val encrypted = CryptoHelper.encrypt(plaintext)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.isNotEmpty())
    }

    @Test
    fun encrypt_withSingleByte() {
        val plaintext = byteArrayOf(42)
        val encrypted = CryptoHelper.encrypt(plaintext)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.isNotEmpty())
    }

    @Test
    fun encrypt_withLargeData() {
        val plaintext = ByteArray(1000) { it.toByte() }
        val encrypted = CryptoHelper.encrypt(plaintext)
        assertNotNull(encrypted)
        assertTrue(encrypted!!.isNotEmpty())
    }
}
