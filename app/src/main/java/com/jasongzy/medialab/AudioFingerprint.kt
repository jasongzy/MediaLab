package com.jasongzy.medialab

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal fun readNcmAudioFormat(buffer: ByteArray, read: (offset: Int, size: Int) -> Unit): String {
    read(0, 4)
    while (String(buffer, 0, 3, Charsets.US_ASCII) == "ID3") {
        val version = buffer[3].toInt()
        require(version in 2..4) { "Invalid ID3 version" }
        read(4, 6)
        var remaining = 0
        for (i in 6..9) {
            val byte = buffer[i].toInt()
            require(byte >= 0) { "Invalid ID3 size" }
            remaining = (remaining shl 7) or byte
        }
        if (version == 4 && buffer[5].toInt() and 16 != 0) remaining += 10
        while (remaining > 0) {
            val count = minOf(buffer.size, remaining)
            read(0, count)
            remaining -= count
        }
        read(0, 4)
    }
    if (String(buffer, 0, 4, Charsets.US_ASCII) == "fLaC") return "flac"
    val flags = buffer[1].toInt()
    val rates = buffer[2].toInt()
    val mpegSync = buffer[0].toInt() == -1 && flags and 0xe0 == 0xe0
    if (mpegSync && flags and 0x18 != 0x08 && flags and 0x06 == 0x02 &&
        rates and 0xf0 != 0xf0 && rates and 0x0c != 0x0c) return "mp3"
    throw IllegalArgumentException("Unsupported audio format")
}

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
            val buffer = ByteArray(256 * 1024)
            val format = readNcmAudioFormat(buffer) { offset, size ->
                check()
                input.readFully(buffer, offset, size)
            }
            require(format == extension) { "Unexpected audio format" }
            var start = input.filePointer - 4
            var end = input.length()
            val hash = MessageDigest.getInstance("SHA-256")
            fun marker(at: Long, text: String): Boolean {
                if (at < 0 || at + text.length > end) return false
                input.seek(at)
                return text.all { input.read() == it.code }
            }
            if (extension == "flac") {
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
                if (marker(end - 128, "TAG")) end -= 128
                if (marker(end - 32, "APETAGEX")) {
                    input.seek(end - 20)
                    val size = Integer.reverseBytes(input.readInt()).toLong()
                    require(size in 32..(end - start))
                    end -= size
                    if (marker(end - 32, "APETAGEX")) end -= 32
                }
                require(end - start >= 16)
            }
            input.seek(start)
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
