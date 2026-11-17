package com.jasongzy.medialab

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object RasterMetadata {
    private val pngChunks = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "iCCP", "sRGB", "gAMA", "cHRM", "cICP", "sBIT", "mDCV", "cLLI",
    )
    private val webpChunks = setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ICCP")
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
                if (padded > end - input.filePointer) return null
                when (type) {
                    0x56503820 -> return "YCbCr".takeIf { length > 0 }
                    0x5650384c -> return "RGB".takeIf { length > 0 }
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
                val retained = if (png) pngChunks else webpChunks
                val header = ByteArray(if (png) 8 else 12)
                if (input.length() < header.size) invalidContainer()
                input.readFully(header)
                output.write(header)
                val end = if (png) input.length() else
                    8 + (ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL)
                if (end !in header.size.toLong()..input.length()) invalidContainer()
                val buffer = ByteArray(256 * 1024)
                while (png || input.filePointer < end) {
                    check()
                    if (end - input.filePointer < 8) invalidContainer()
                    val lengthBytes = ByteArray(4)
                    input.readFully(lengthBytes)
                    val typeBytes = ByteArray(4)
                    input.readFully(typeBytes)
                    val type: String
                    val length: Long
                    if (png) {
                        length = ByteBuffer.wrap(lengthBytes).int.toLong() and 0xffffffffL
                        type = typeBytes.toString(Charsets.US_ASCII)
                    } else {
                        type = lengthBytes.toString(Charsets.US_ASCII)
                        length =
                            ByteBuffer.wrap(typeBytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int
                                .toLong() and 0xffffffffL
                    }
                    if (type == "acTL" || type == "ANIM" || type == "ANMF")
                        throw UnsupportedMediaException(R.string.select_image_only)
                    val last = png && type == "IEND"
                    if (last && length != 0L) invalidContainer()
                    val padded = length + if (png) 4 else length % 2
                    if (padded > end - input.filePointer) invalidContainer()
                    if (type in retained) {
                        output.write(lengthBytes)
                        output.write(typeBytes)
                        var left = padded
                        if (!png && type == "VP8X") {
                            if (length != 10L) invalidContainer()
                            output.write(input.readUnsignedByte() and 0xf3)
                            left--
                        }
                        while (left > 0) {
                            check()
                            val count = minOf(left, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, count)
                            output.write(buffer, 0, count)
                            left -= count
                        }
                    } else input.seek(input.filePointer + padded)
                    if (last) break
                }
                output.setLength(output.filePointer)
                if (!png) {
                    output.seek(4)
                    output.writeInt(Integer.reverseBytes((output.length() - 8).toInt()))
                }
            }
        }
    }

    private fun invalidContainer(): Nothing = throw UnsupportedMediaException(R.string.invalid_image_container)
}
