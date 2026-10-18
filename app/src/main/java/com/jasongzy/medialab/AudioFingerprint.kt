package com.jasongzy.medialab

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal object AudioFingerprint {
    fun digest(input: InputStream, check: () -> Unit = {}): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            check()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) hash.update(buffer, 0, count)
        }
        return hex(hash.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // Compare encoded audio rather than tags, which an unlocker may restore differently.
    fun payload(file: File, extension: String, check: () -> Unit = {}): String =
        RandomAccessFile(file, "r").use { input ->
            var start = 0L
            var end = input.length()
            val hash = MessageDigest.getInstance("SHA-256")
            fun marker(at: Long, text: String): Boolean {
                if (at < 0 || at + text.length > end) return false
                input.seek(at)
                return text.all { input.read() == it.code }
            }
            if (extension == "flac") {
                require(marker(0, "fLaC")) { "Invalid FLAC header" }
                input.seek(4)
                var first = true
                do {
                    val header = input.readUnsignedByte()
                    val size =
                        (input.readUnsignedByte() shl 16) or
                            (input.readUnsignedByte() shl 8) or
                            input.readUnsignedByte()
                    require(!first || (header and 127 == 0 && size == 34)) {
                        "Invalid FLAC stream info"
                    }
                    require(header and 127 != 127 && size <= end - input.filePointer)
                    if (first) hash.update(ByteArray(size).also { input.readFully(it) })
                    else input.seek(input.filePointer + size)
                    first = false
                } while (header and 128 == 0)
                start = input.filePointer
                require(end - start >= 16)
                require(
                    input.readUnsignedByte() == 255 && input.readUnsignedByte() and 254 == 248
                ) {
                    "Invalid FLAC audio"
                }
            } else {
                require(extension == "mp3")
                if (marker(0, "ID3")) {
                    input.seek(3)
                    val version = input.readUnsignedByte()
                    require(version in 2..4)
                    input.readUnsignedByte()
                    val flags = input.readUnsignedByte()
                    var size = 0L
                    repeat(4) {
                        val byte = input.readUnsignedByte()
                        require(byte and 128 == 0)
                        size = (size shl 7) or byte.toLong()
                    }
                    start = 10 + size + if (version == 4 && flags and 16 != 0) 10 else 0
                }
                if (marker(end - 128, "TAG")) end -= 128
                if (marker(end - 32, "APETAGEX")) {
                    input.seek(end - 20)
                    val size = Integer.reverseBytes(input.readInt()).toLong()
                    require(size in 32..(end - start))
                    end -= size
                    if (marker(end - 32, "APETAGEX")) end -= 32
                }
                require(end - start >= 16)
                input.seek(start)
                require(
                    input.readUnsignedByte() == 255 && input.readUnsignedByte() and 224 == 224
                ) {
                    "Invalid MPEG audio"
                }
            }
            require(end > start)
            input.seek(start)
            val buffer = ByteArray(256 * 1024)
            var remaining = end - start
            while (remaining > 0) {
                check()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(count > 0)
                hash.update(buffer, 0, count)
                remaining -= count
            }
            "$extension:${end - start}:${hex(hash.digest())}"
        }
}
