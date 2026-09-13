package com.videosniffer.download.hls

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS 分片解密。
 *
 * 覆盖 `#EXT-X-KEY:METHOD=AES-128`（AES-128-CBC + PKCS5/PKCS7 填充），
 * 这是 HLS 最普遍的全段加密方式。
 *
 * 不支持的 METHOD（如 `SAMPLE-AES`）会显式失败而非静默产出损坏文件 ——
 * 之前版本的实现会把加密分片直接拼接，得到无法播放的成品却标记为「已完成」。
 */
object HlsCrypto {

    const val METHOD_NONE = "NONE"
    const val METHOD_AES_128 = "AES-128"
    const val METHOD_SAMPLE_AES = "SAMPLE-AES"

    /** 该 METHOD 是否为可解密的全段加密。 */
    fun isSupported(method: String): Boolean =
        method.equals(METHOD_NONE, true) || method.equals(METHOD_AES_128, true)

    /**
     * 解密一个分片。
     *
     * @param data  密文（整个分片）
     * @param key   16 字节 AES 密钥
     * @param iv    16 字节 IV；解析不到显式 IV 时由 [ivFromSequence] 推导
     */
    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 密钥必须为 16 字节，实际 ${key.size}" }
        require(iv.size == 16) { "IV 必须为 16 字节，实际 ${iv.size}" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(data)
    }

    /**
     * 解析 `#EXT-X-KEY:IV=0x...` 的十六进制 IV。
     * 长度不足 16 字节时左侧补零（RFC 8216 允许省略前导零）。
     *
     * @return 16 字节 IV；格式非法返回 null
     */
    fun parseIv(hex: String?): ByteArray? {
        if (hex.isNullOrBlank()) return null
        val cleaned = hex.trim().removePrefix("0x").removePrefix("0X").replace(" ", "")
        if (cleaned.isEmpty() || cleaned.length > 32) return null
        if (!cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        val padded = cleaned.padStart(32, '0')
        val out = ByteArray(16)
        for (i in 0 until 16) {
            val hi = Character.digit(padded[i * 2], 16)
            val lo = Character.digit(padded[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * 无显式 IV 时，HLS 规定用「媒体序号」作为 IV（16 字节大端整数）。
     *
     * @param mediaSequence 分片的绝对媒体序号（mediaSequence + 分片下标）
     */
    fun ivFromSequence(mediaSequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = mediaSequence
        // 低 8 字节按大端写入尾部；高 8 字节保持 0（媒体序号实际不会超过 2^56）
        repeat(8) {
            val pos = 15 - it
            iv[pos] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }
}
