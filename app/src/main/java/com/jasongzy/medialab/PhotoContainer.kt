package com.jasongzy.medialab

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal data class ByteRange(val start: Long, val length: Long) {
    val end
        get() = start + length
}

internal data class JpegPart(val marker: Int, val range: ByteRange, val payload: ByteArray)

internal data class JpegLayout(
    val parts: List<JpegPart>,
    val end: Long,
    val width: Int,
    val height: Int,
    val blockX: Int,
    val blockY: Int,
) {
    val colorModel: String?
        get() {
            val frame = parts.firstOrNull { it.marker in listOf(0xc0, 0xc1, 0xc2) }?.payload ?: return null
            val count = frame[5].toInt() and 255
            val adobe = parts.firstOrNull {
                it.marker == 0xee && it.payload.size >= 12 &&
                    it.payload.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "Adobe"
            }?.payload?.get(11)?.toInt()
            return when (count) {
                1 -> "Gray"
                3 -> when {
                    parts.any { it.marker == 0xe0 && it.payload.size >= 5 &&
                        it.payload.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "JFIF\u0000" } -> "YCbCr"
                    adobe == 0 -> "RGB"
                    adobe == 1 -> "YCbCr"
                    else -> null
                }
                4 -> when (adobe) {
                    0 -> "CMYK"
                    2 -> "YCCK"
                    else -> null
                }
                else -> null
            }
        }
}

internal data class PhotoContainer(
    val jpeg: JpegLayout,
    val gainmap: ByteRange? = null,
    val video: ByteRange? = null,
    val edit: ByteRange? = null,
)

internal object Containers {
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val EDIT = "http://ns.xiaomi.com/photos/1.0/camera/reedit"
    private val xmpPrefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()

    fun jpeg(file: File, start: Long = 0): JpegLayout {
        FileInputStream(file).use { source ->
            source.channel.position(start)
            val input = DataInputStream(BufferedInputStream(source, 256 * 1024))
            require(input.readUnsignedShort() == 0xffd8) { "Invalid JPEG header" }
            var offset = start + 2
            val parts = mutableListOf<JpegPart>()
            var width = 0
            var height = 0
            var blockX = 8
            var blockY = 8
            var entropy = false
            val fileLength = file.length()
            while (offset < fileLength) {
                var byte = input.readUnsignedByte()
                offset++
                if (entropy && byte != 255) continue
                require(byte == 255) { "Invalid JPEG marker at ${offset - 1}" }
                val markerStart = offset - 1
                do {
                    byte = input.readUnsignedByte()
                    offset++
                } while (byte == 255)
                if (entropy && (byte == 0 || byte in 0xd0..0xd7)) continue
                entropy = false
                if (byte == 0xd9) return JpegLayout(parts, offset, width, height, blockX, blockY)
                require(byte != 0xd8 && byte != 0 && byte !in 0xd0..0xd7) {
                    "Unexpected JPEG marker"
                }
                if (byte == 1) continue
                val length = input.readUnsignedShort()
                offset += 2
                require(length >= 2 && offset + length - 2 <= fileLength) {
                    "Truncated JPEG segment"
                }
                val payload = ByteArray(length - 2)
                input.readFully(payload)
                offset += payload.size
                require(parts.size < 4096) { "Too many JPEG segments" }
                parts += JpegPart(byte, ByteRange(markerStart, offset - markerStart), payload)
                if (byte in listOf(0xc0, 0xc1, 0xc2)) {
                    require(payload.size >= 6)
                    height = u16(payload, 1)
                    width = u16(payload, 3)
                    val components = payload[5].toInt() and 255
                    require(payload.size >= 6 + components * 3)
                    for (i in 0 until components) {
                        val sample = payload[7 + i * 3].toInt() and 255
                        blockX = maxOf(blockX, (sample ushr 4) * 8)
                        blockY = maxOf(blockY, (sample and 15) * 8)
                    }
                }
                entropy = byte == 0xda
            }
            error("Missing JPEG end marker")
        }
    }

    fun inspect(file: File): PhotoContainer? {
        file.inputStream().use { if (it.read() != 255 || it.read() != 216) return null }
        val layout = jpeg(file)
        var video: ByteRange? = null
        var edit: ByteRange? = null
        var gainmap: ByteRange? = null
        for (part in layout.parts) {
            if (part.marker == 0xe1 && part.payload.startsWith(xmpPrefix)) {
                val doc = xml(part.payload.copyOfRange(xmpPrefix.size, part.payload.size))
                val nodes = doc.getElementsByTagName("*")
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    if (element.hasAttributeNS(EDIT, "offset")) {
                        val offset = element.getAttributeNS(EDIT, "offset").toLong()
                        val length = element.getAttributeNS(EDIT, "len").toLong()
                        edit = checkedRange(file, file.length() - offset, length, layout.end)
                        require(element.getAttributeNS(EDIT, "version") == "1") {
                            "Unsupported Xiaomi edit version"
                        }
                    }
                    if (element.getAttributeNS(ITEM, "Semantic") == "MotionPhoto") {
                        video =
                            checkedRange(
                                file,
                                file.length() - element.getAttributeNS(ITEM, "Length").toLong(),
                                element.getAttributeNS(ITEM, "Length").toLong(),
                                layout.end,
                            )
                    }
                    if (video == null && element.hasAttributeNS(CAMERA, "MicroVideoOffset")) {
                        val length = element.getAttributeNS(CAMERA, "MicroVideoOffset").toLong()
                        video = checkedRange(file, file.length() - length, length, layout.end)
                    }
                }
            }
            if (part.marker == 0xe2 && part.payload.startsWith("MPF\u0000".toByteArray())) {
                val buffer = mpf(part.payload)
                val table = mpEntries(buffer) ?: continue
                val count = (buffer.capacity() - table) / 16
                if (count >= 2) {
                    val length = buffer.getInt(table + 20).toLong() and 0xffffffffL
                    val offset = buffer.getInt(table + 24).toLong() and 0xffffffffL
                    val range =
                        checkedRange(file, part.range.start + 8 + offset, length, layout.end)
                    val auxiliary = jpeg(file, range.start)
                    if (
                        auxiliary.end == range.end &&
                            auxiliary.parts.any {
                                it.payload.toString(Charsets.ISO_8859_1).contains("hdr-gain-map") ||
                                    it.payload
                                        .toString(Charsets.ISO_8859_1)
                                        .contains("urn:iso:std:iso:ts:21496")
                            }
                    )
                        gainmap = range
                }
            }
        }
        video?.let { range ->
            RandomAccessFile(file, "r").use { input ->
                input.seek(range.start)
                val size = input.readInt().toLong() and 0xffffffffL
                require(size in 8..range.length && input.readInt() == 0x66747970) {
                    "Invalid motion video container"
                }
            }
        }
        edit?.let {
            require(
                it.start >= (gainmap?.end ?: layout.end) &&
                    it.end <= (video?.start ?: file.length())
            ) {
                "Overlapping image attachments"
            }
        }
        video?.let {
            require(it.start >= (gainmap?.end ?: layout.end)) { "Overlapping motion video" }
        }
        return PhotoContainer(layout, gainmap, video, edit)
    }

    fun clean(
        source: File,
        target: File,
        static: Boolean,
        flatten: Boolean,
        metadata: Boolean,
        check: () -> Unit = {},
    ) {
        require(source.canonicalFile != target.canonicalFile)
        val info = requireNotNull(inspect(source)) { "JPEG container required" }
        val gainmap = if (metadata) info.gainmap else null
        if (gainmap == null) {
            writeClean(source, info, target, static, flatten, metadata, null, check)
            return
        }
        val auxiliary = File.createTempFile("gainmap-", ".jpg", target.parentFile)
        val cleaned = File.createTempFile("gainmap-clean-", ".jpg", target.parentFile)
        try {
            RandomAccessFile(source, "r").use { input ->
                auxiliary.outputStream().use { copyRange(input, it, gainmap, check) }
            }
            val auxiliaryInfo = requireNotNull(inspect(auxiliary)) { "JPEG gainmap required" }
            writeClean(auxiliary, auxiliaryInfo, cleaned, true, true, true, null, check)
            writeClean(source, info, target, static, flatten, metadata, cleaned, check)
        } finally {
            auxiliary.delete()
            cleaned.delete()
        }
    }

    private fun writeClean(
        source: File,
        info: PhotoContainer,
        target: File,
        static: Boolean,
        flatten: Boolean,
        metadata: Boolean,
        auxiliary: File?,
        check: () -> Unit,
    ) {
        val keepVideo = !static && !metadata && info.video != null
        val keepEdit = !flatten && !static && !metadata && info.edit != null
        val rewritten = mutableListOf<Pair<JpegPart, ByteArray?>>()
        for (part in info.jpeg.parts) {
            val content = part.payload
            val isXmp = part.marker == 0xe1 && content.startsWith(xmpPrefix)
            val isMpf = part.marker == 0xe2 && content.startsWith("MPF\u0000".toByteArray())
            val essential =
                (part.marker == 0xe2 &&
                    (content.startsWith("ICC_PROFILE".toByteArray()) ||
                        content.startsWith("urn:iso:std:iso:ts:21496".toByteArray()))) ||
                    (part.marker == 0xee && content.startsWith("Adobe".toByteArray()))
            val replacement =
                when {
                    isMpf -> if (info.gainmap == null) null else content.copyOf()
                    isXmp -> rewriteXmp(content, keepVideo, keepEdit, metadata, info)
                    metadata && part.marker == 0xe0 ->
                        if (content.startsWith("JFIF\u0000".toByteArray()) && content.size >= 14)
                            content.copyOf(14).apply {
                                this[12] = 0
                                this[13] = 0
                            }
                        else null
                    (metadata || flatten || static) && part.marker == 0xe4 -> null
                    metadata && part.marker in 0xe1..0xef && !essential -> null
                    metadata && part.marker == 0xfe -> null
                    else -> content
                }
            rewritten += part to replacement
        }
        val delta = rewritten.sumOf { (part, payload) ->
            (payload?.size?.plus(4)?.toLong() ?: 0L) - part.range.length
        }
        val primaryLength = info.jpeg.end + delta
        var precedingDelta = 0L
        for ((part, payload) in rewritten) {
            if (
                payload != null &&
                    part.marker == 0xe2 &&
                    payload.startsWith("MPF\u0000".toByteArray())
            ) {
                val buffer = mpf(payload)
                val table = requireNotNull(mpEntries(buffer))
                val gainmapLength = auxiliary?.length() ?: requireNotNull(info.gainmap).length
                require(primaryLength <= 0xffffffffL && gainmapLength <= 0xffffffffL)
                buffer.putInt(table + 4, primaryLength.toInt())
                buffer.putInt(table + 20, gainmapLength.toInt())
                buffer.putInt(
                    table + 24,
                    (primaryLength - part.range.start - precedingDelta - 8).toInt(),
                )
            }
            precedingDelta += (payload?.size?.plus(4)?.toLong() ?: 0L) - part.range.length
        }
        RandomAccessFile(source, "r").use { input ->
            target.outputStream().buffered(256 * 1024).use { output ->
                output.write(byteArrayOf(-1, -40))
                var position = 2L
                for ((part, payload) in rewritten) {
                    copyRange(
                        input,
                        output,
                        ByteRange(position, part.range.start - position),
                        check,
                    )
                    if (payload != null) {
                        output.write(255)
                        output.write(part.marker)
                        output.write((payload.size + 2) ushr 8)
                        output.write((payload.size + 2) and 255)
                        output.write(payload)
                    }
                    position = part.range.end
                }
                copyRange(input, output, ByteRange(position, info.jpeg.end - position), check)
                if (auxiliary != null)
                    RandomAccessFile(auxiliary, "r").use {
                        copyRange(it, output, ByteRange(0, auxiliary.length()), check)
                    }
                else info.gainmap?.let { copyRange(input, output, it, check) }
                if (keepEdit) copyRange(input, output, info.edit, check)
                if (keepVideo) copyRange(input, output, info.video, check)
            }
        }
        val result = requireNotNull(inspect(target))
        kotlin.check(result.jpeg.width == info.jpeg.width && result.jpeg.height == info.jpeg.height)
        kotlin.check((result.video != null) == keepVideo && (result.edit != null) == keepEdit)
    }

    private fun rewriteXmp(
        bytes: ByteArray,
        video: Boolean,
        edit: Boolean,
        metadata: Boolean,
        info: PhotoContainer,
    ): ByteArray? {
        val source = xml(bytes.copyOfRange(xmpPrefix.size, bytes.size))
        val doc =
            if (metadata) {
                val fresh =
                    xml(
                        ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                                "<rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>")
                            .toByteArray()
                    )
                val destination =
                    fresh
                        .getElementsByTagNameNS(
                            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                            "Description",
                        )
                        .item(0) as Element
                val nodes = source.getElementsByTagName("*")
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    if (
                        element.namespaceURI == "http://ns.adobe.com/hdr-gain-map/1.0/" &&
                            element.parentNode.localName == "Description"
                    )
                        destination.appendChild(fresh.importNode(element, true))
                    for (j in 0 until element.attributes.length) {
                        val attr = element.attributes.item(j)
                        if (
                            attr.namespaceURI == "http://ns.adobe.com/hdr-gain-map/1.0/" ||
                                (video && attr.namespaceURI == CAMERA)
                        )
                            destination.setAttributeNS(
                                attr.namespaceURI,
                                attr.nodeName,
                                attr.nodeValue,
                            )
                    }
                }
                fresh
            } else source
        val nodes = doc.getElementsByTagName("*")
        val elements = (0 until nodes.length).map { nodes.item(it) as Element }
        for (element in elements) {
            val attributes = element.attributes
            for (i in attributes.length - 1 downTo 0) {
                val attr = attributes.item(i)
                if (
                    (attr.namespaceURI == CAMERA && !video) ||
                        (attr.namespaceURI == EDIT && !edit) ||
                        (metadata &&
                            attr.namespaceURI !in
                                setOf(
                                    CAMERA,
                                    CONTAINER,
                                    ITEM,
                                    "http://ns.adobe.com/hdr-gain-map/1.0/",
                                    "http://www.w3.org/2000/xmlns/",
                                    "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                                ))
                )
                    element.removeAttributeNode(attr as org.w3c.dom.Attr)
            }
            if (element.namespaceURI == CONTAINER && element.localName == "Directory") {
                element.parentNode?.removeChild(element)
            }
        }
        val descriptions =
            doc.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description")
        if (
            metadata &&
                !video &&
                (0 until descriptions.length).all { index ->
                    val element = descriptions.item(index) as Element
                    return@all !element.hasChildNodes() &&
                        (0 until element.attributes.length).none {
                            element.attributes.item(it).namespaceURI ==
                                "http://ns.adobe.com/hdr-gain-map/1.0/"
                        }
                }
        )
            return null
        if (video) {
            val root = descriptions.item(0) as? Element ?: error("Missing XMP description")
            root.setAttributeNS(CAMERA, "GCamera:MotionPhoto", "1")
            root.setAttributeNS(CAMERA, "GCamera:MotionPhotoVersion", "1")
            val directory = doc.createElementNS(CONTAINER, "Container:Directory")
            val seq = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:Seq")
            directory.appendChild(seq)
            root.appendChild(directory)
            for ((semantic, length, mime) in
                listOf(
                    Triple("Primary", 0L, "image/jpeg"),
                    Triple("MotionPhoto", info.video!!.length, "video/mp4"),
                )) {
                val li =
                    doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:li")
                li.setAttributeNS(
                    "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                    "rdf:parseType",
                    "Resource",
                )
                val item = doc.createElementNS(CONTAINER, "Container:Item")
                item.setAttributeNS(ITEM, "Item:Semantic", semantic)
                item.setAttributeNS(ITEM, "Item:Mime", mime)
                if (length > 0) item.setAttributeNS(ITEM, "Item:Length", length.toString())
                li.appendChild(item)
                seq.appendChild(li)
            }
        }
        val writer = StringWriter()
        TransformerFactory.newInstance()
            .newTransformer()
            .apply { setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") }
            .transform(DOMSource(doc), StreamResult(writer))
        val serialized = writer.toString()
        val packet =
            if (serialized.contains("<?xpacket")) serialized
            else
                "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>$serialized<?xpacket end=\"w\"?>"
        val result = xmpPrefix + packet.toByteArray()
        require(result.size <= 65533) { "XMP too large" }
        return result
    }

    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) {
            "Unsupported XML declaration"
        }
        return DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(text)))
    }

    private fun checkedRange(file: File, start: Long, length: Long, minimum: Long): ByteRange {
        require(
            start >= minimum &&
                length > 0 &&
                start <= file.length() &&
                length <= file.length() - start
        ) {
            "Invalid attachment range"
        }
        return ByteRange(start, length)
    }

    private fun mpf(bytes: ByteArray): ByteBuffer {
        require(bytes.size >= 12)
        val buffer = ByteBuffer.wrap(bytes, 4, bytes.size - 4).slice()
        buffer.order(if (bytes[4] == 73.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        require(buffer.getShort(2).toInt() == 42) { "Invalid MPF" }
        return buffer
    }

    private fun mpEntries(buffer: ByteBuffer): Int? {
        val offset = buffer.getInt(4)
        require(offset >= 8 && offset + 2 <= buffer.capacity())
        val count = buffer.getShort(offset).toInt() and 65535
        require(count <= 128 && offset + 2 + count * 12 <= buffer.capacity())
        for (i in 0 until count) {
            val at = offset + 2 + i * 12
            if ((buffer.getShort(at).toInt() and 65535) == 0xb002) {
                val length = buffer.getInt(at + 4)
                val table = buffer.getInt(at + 8)
                require(length == 32 && table >= 8 && table + length <= buffer.capacity()) {
                    "Unsupported MPF layout"
                }
                return table
            }
        }
        return null
    }

    private fun copyRange(
        input: RandomAccessFile,
        output: OutputStream,
        range: ByteRange,
        check: () -> Unit,
    ) {
        require(range.length >= 0)
        input.seek(range.start)
        val buffer = ByteArray(256 * 1024)
        var remaining = range.length
        while (remaining > 0) {
            check()
            val count = minOf(buffer.size.toLong(), remaining).toInt()
            input.readFully(buffer, 0, count)
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun u16(bytes: ByteArray, at: Int) =
        ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
}
