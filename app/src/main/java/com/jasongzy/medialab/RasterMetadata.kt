package com.jasongzy.medialab

import java.io.File
import java.io.RandomAccessFile

internal object RasterMetadata {
    fun colorModel(file: File, mime: String): String? {
        if (mime !in listOf("image/png", "image/webp")) return null
        RandomAccessFile(file, "r").use { input ->
            if (mime == "image/png") {
                if (input.length() < 29 || input.readLong() != 0x89504e470d0a1a0auL.toLong() ||
                    input.readInt() != 13 || input.readInt() != 0x49484452) return null
                input.seek(25)
                return when (input.readUnsignedByte()) {
                    0, 4 -> "Gray"
                    2, 3, 6 -> "RGB"
                    else -> null
                }
            }
            if (input.length() < 12 || input.readInt() != 0x52494646) return null
            val end = 8 + (Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL)
            if (input.readInt() != 0x57454250 || end > input.length()) return null
            while (input.filePointer + 8 <= end) {
                val type = input.readInt()
                val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                val padded = length + length % 2
                if (length == 0L || padded > end - input.filePointer) return null
                when (type) {
                    0x56503820 -> return "YCbCr"
                    0x5650384c -> return "RGB"
                }
                input.seek(input.filePointer + padded)
            }
            return null
        }
    }

    fun clean(source: File, target: File, mime: String, check: () -> Unit) {
        RandomAccessFile(source, "r").use { input ->
            RandomAccessFile(target, "rw").use { output ->
                val png = mime == "image/png"
                val header = ByteArray(if (png) 8 else 12)
                input.readFully(header)
                output.write(header)
                while (input.filePointer < input.length()) {
                    check()
                    val lengthBytes = ByteArray(4)
                    input.readFully(lengthBytes)
                    val typeBytes = ByteArray(4)
                    input.readFully(typeBytes)
                    val type: String
                    val length: Long
                    if (png) {
                        length = java.nio.ByteBuffer.wrap(lengthBytes).int.toLong() and 0xffffffffL
                        type = typeBytes.toString(Charsets.US_ASCII)
                    } else {
                        type = lengthBytes.toString(Charsets.US_ASCII)
                        length =
                            java.nio.ByteBuffer.wrap(typeBytes)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .int
                                .toLong() and 0xffffffffL
                    }
                    require(type !in setOf("acTL", "ANIM", "ANMF")) { "Choose a still image" }
                    val padded = length + if (png) 4 else length % 2
                    require(padded <= input.length() - input.filePointer) {
                        "Truncated image chunk"
                    }
                    val keep =
                        if (png)
                            type in
                                setOf(
                                    "IHDR",
                                    "PLTE",
                                    "IDAT",
                                    "IEND",
                                    "tRNS",
                                    "iCCP",
                                    "sRGB",
                                    "gAMA",
                                    "cHRM",
                                    "cICP",
                                )
                        else type in setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ICCP")
                    if (keep) {
                        output.write(lengthBytes)
                        output.write(typeBytes)
                        var left = padded
                        if (!png && type == "VP8X") {
                            require(length == 10L)
                            output.write(input.readUnsignedByte() and 0xf3)
                            left--
                        }
                        val buffer = ByteArray(256 * 1024)
                        while (left > 0) {
                            check()
                            val count = minOf(left, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, count)
                            output.write(buffer, 0, count)
                            left -= count
                        }
                    } else input.seek(input.filePointer + padded)
                    if (png && type == "IEND") break
                }
                if (!png) {
                    require(output.length() - 8 <= 0xffffffffL)
                    output.seek(4)
                    output.writeInt(Integer.reverseBytes((output.length() - 8).toInt()))
                }
            }
        }
    }
}
