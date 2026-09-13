package com.videosniffer.download.hls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [HlsCrypto] 测试：IV 解析、媒体序号推导 IV、AES-128-CBC 加解密往返。
 */
class HlsCryptoTest {

    private val key = ByteArray(16) { (it + 1).toByte() }
    private val iv = ByteArray(16) { (it * 3).toByte() }

    @Test
    fun `parseIv 解析 0x 前缀与不足长度左补零`() {
        val full = HlsCrypto.parseIv("0x000102030405060708090A0B0C0D0E0F")
        assertArrayEquals(ByteArray(16) { it.toByte() }, full)

        // 仅给低 2 字节，其余补 0
        val short = HlsCrypto.parseIv("0x0102")
        val expected = ByteArray(16)
        expected[14] = 1
        expected[15] = 2
        assertArrayEquals(expected, short)
    }

    @Test
    fun `parseIv 对非法输入返回 null`() {
        assertNull(HlsCrypto.parseIv(null))
        assertNull(HlsCrypto.parseIv(""))
        assertNull(HlsCrypto.parseIv("0xZZ"))
        // 超过 16 字节
        assertNull(HlsCrypto.parseIv("0x" + "01".repeat(17)))
    }

    @Test
    fun `ivFromSequence 写成 16 字节大端整数`() {
        val fromZero = HlsCrypto.ivFromSequence(0)
        assertArrayEquals(ByteArray(16), fromZero)

        val fromOne = HlsCrypto.ivFromSequence(1)
        val expectedOne = ByteArray(16)
        expectedOne[15] = 1
        assertArrayEquals(expectedOne, fromOne)

        // 0x0102 → 最后两字节 0x01 0x02
        val twoBytes = HlsCrypto.ivFromSequence(0x0102)
        val expectedTwo = ByteArray(16)
        expectedTwo[14] = 1
        expectedTwo[15] = 2
        assertArrayEquals(expectedTwo, twoBytes)
    }

    @Test
    fun `AES-128-CBC 解密与加密互逆`() {
        val plaintext = "这是一个用于验证 HLS AES-128 解密的分片内容-payload".toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)

        val decrypted = HlsCrypto.decrypt(ciphertext, key, iv)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `密钥长度非法时抛异常`() {
        val bad = runCatching { HlsCrypto.decrypt(ByteArray(32), ByteArray(8), iv) }
        assertTrue(bad.isFailure)
    }

    @Test
    fun `isSupported 只接受 NONE 与 AES-128`() {
        assertTrue(HlsCrypto.isSupported("NONE"))
        assertTrue(HlsCrypto.isSupported("none"))
        assertTrue(HlsCrypto.isSupported("AES-128"))
        assertTrue(HlsCrypto.isSupported("aes-128"))
        // SAMPLE-AES 属不支持项，必须显式失败而不是产出损坏文件
        assertFalse(HlsCrypto.isSupported("SAMPLE-AES"))
        assertFalse(HlsCrypto.isSupported("AES-256"))
    }

    @Test
    fun `HlsKey isEncrypted 判定`() {
        assertFalse(HlsKey.NONE.isEncrypted)
        assertEquals(false, HlsKey("NONE", null, null).isEncrypted)
        assertTrue(HlsKey("AES-128", "https://k/1", null).isEncrypted)
    }
}
