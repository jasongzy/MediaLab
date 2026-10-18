package com.jasongzy.medialab

import android.annotation.SuppressLint
import java.io.DataInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal data class NcmResult(val extension: String, val metadata: String?, val cover: ByteArray)

// NCM container algorithm: taurusxin/ncmdump and Pacoshao/DeNcm (MIT).
internal object Ncm {
    private val coreKey = "687a4852416d736f356b496e62617857".hex()
    private val metaKey = "2331346c6a6b5f215c5d2630553c2728".hex()

    fun matches(file: File): Boolean = file.inputStream().use(::matches)

    fun matches(input: InputStream): Boolean = "CTENFDAM".all { input.read() == it.code }

    fun decode(
        source: File,
        output: File,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): NcmResult =
        source.inputStream().use { decode(it, output, source.length(), progress = progress) }

    fun decode(
        source: InputStream,
        output: File,
        total: Long = -1,
        onFormat: (String) -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): NcmResult {
        try {
            val counted = CountingInput(source.buffered())
            DataInputStream(counted).use { input ->
                fun block(limit: Int): ByteArray {
                    val size = input.littleInt()
                    require(size <= limit && (total < 0 || size <= total - counted.position)) {
                        "Invalid NCM block length"
                    }
                    return ByteArray(size).also { input.readFully(it) }
                }
                val magic = ByteArray(8).also { input.readFully(it) }
                require(magic.contentEquals("CTENFDAM".toByteArray())) { "Invalid NCM header" }
                input.readUnsignedShort()
                val encryptedKey =
                    block(16 * 1024 * 1024).map { (it.toInt() xor 0x64).toByte() }.toByteArray()
                val key = decrypt(coreKey, encryptedKey)
                require(
                    key.size > 17 &&
                        key.copyOfRange(0, 17).contentEquals("neteasecloudmusic".toByteArray())
                ) {
                    "Invalid NCM key"
                }
                val stream = keyStream(key.copyOfRange(17, key.size))
                val meta = block(16 * 1024 * 1024)
                val metadata =
                    if (meta.isEmpty()) null
                    else {
                        val encoded = meta.map { (it.toInt() xor 0x63).toByte() }.toByteArray()
                        require(
                            encoded.size > 22 &&
                                encoded
                                    .copyOfRange(0, 22)
                                    .contentEquals("163 key(Don't modify):".toByteArray())
                        ) {
                            "Invalid NCM metadata"
                        }
                        val plain =
                            decrypt(
                                    metaKey,
                                    Base64.getDecoder()
                                        .decode(encoded.copyOfRange(22, encoded.size)),
                                )
                                .toString(Charsets.UTF_8)
                        require(plain.startsWith("music:")) { "Invalid NCM metadata prefix" }
                        plain.substring(6)
                    }
                input.readInt()
                input.readUnsignedByte()
                val frameLength = input.littleInt()
                val cover = block(32 * 1024 * 1024)
                require(frameLength >= cover.size && frameLength <= 64 * 1024 * 1024) {
                    "Invalid NCM cover frame"
                }
                var padding = frameLength - cover.size
                val buffer = ByteArray(128 * 1024)
                while (padding > 0) {
                    progress(counted.position, total)
                    val count = minOf(padding, buffer.size)
                    input.readFully(buffer, 0, count)
                    padding -= count
                }
                var offset = 0L
                var extension: String? = null
                output.outputStream().buffered().use { target ->
                    while (true) {
                        progress(counted.position, total)
                        val count =
                            if (offset == 0L) {
                                input.readFully(buffer, 0, 4)
                                4
                            } else input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        for (i in 0 until count) buffer[i] =
                            (buffer[i].toInt() xor stream[((offset + i) and 255).toInt()]).toByte()
                        if (extension == null) {
                            require(count >= 4)
                            extension =
                                when {
                                    buffer.copyOfRange(0, 4).contentEquals("fLaC".toByteArray()) ->
                                        "flac"
                                    buffer.copyOfRange(0, 3).contentEquals("ID3".toByteArray()) ||
                                        ((buffer[0].toInt() and 255) == 255 &&
                                            (buffer[1].toInt() and 0xe0) == 0xe0) -> "mp3"
                                    else -> error("Unsupported NCM audio format")
                                }
                            onFormat(checkNotNull(extension))
                        }
                        target.write(buffer, 0, count)
                        offset += count
                    }
                }
                require(total < 0 || counted.position == total) { "Truncated NCM audio" }
                progress(counted.position, counted.position)
                return NcmResult(checkNotNull(extension), metadata, cover)
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        }
    }

    private fun keyStream(key: ByteArray): IntArray {
        require(key.isNotEmpty())
        val box = IntArray(256) { it }
        var last = 0
        for (i in box.indices) {
            val swap = box[i]
            val at = (swap + last + (key[i % key.size].toInt() and 255)) and 255
            box[i] = box[at]
            box[at] = swap
            last = at
        }
        return IntArray(256) { i ->
            val j = (i + 1) and 255
            box[(box[j] + box[(box[j] + j) and 255]) and 255]
        }
    }

    @SuppressLint("GetInstance") // NCM specifies AES-ECB for its container keys and metadata.
    private fun decrypt(key: ByteArray, bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty() && bytes.size % 16 == 0) { "Invalid AES block length" }
        return Cipher.getInstance("AES/ECB/PKCS5Padding")
            .apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            }
            .doFinal(bytes)
    }

    private fun DataInputStream.littleInt(): Int =
        Integer.reverseBytes(readInt()).also { require(it >= 0) { "Invalid NCM length" } }

    private class CountingInput(input: InputStream) : FilterInputStream(input) {
        var position = 0L

        override fun read(): Int = `in`.read().also { if (it >= 0) position++ }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            `in`.read(bytes, offset, length).also { if (it > 0) position += it }

        override fun skip(count: Long): Long = `in`.skip(count).also { position += it }
    }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
