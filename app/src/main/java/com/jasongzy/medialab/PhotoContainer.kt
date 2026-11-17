package com.jasongzy.medialab

import java.io.EOFException
import java.io.File
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
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException

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

internal data class MotionVideo(val range: ByteRange, val extension: String)

internal data class PhotoContainer(
    val jpeg: JpegLayout,
    val gainmap: ByteRange? = null,
    val video: MotionVideo? = null,
    val edit: ByteRange? = null,
)

internal object Containers {
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val EDIT = "http://ns.xiaomi.com/photos/1.0/camera/reedit"
    private const val HDRGM = "http://ns.adobe.com/hdr-gain-map/1.0/"
    private val xmpPrefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
    private val isoGainmapPrefix = "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray()

    fun jpeg(file: File, start: Long = 0): JpegLayout {
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            require(input.readUnsignedShort() == 0xffd8) { "Invalid JPEG header" }
            val parts = mutableListOf<JpegPart>()
            var width = 0
            var height = 0
            var blockX = 8
            var blockY = 8
            var entropy = false
            val fileLength = input.length()
            val buffer = ByteArray(64 * 1024)
            while (input.filePointer < fileLength) {
                val byte = if (entropy) scanMarker(input, buffer) else {
                    require(input.readUnsignedByte() == 255) { "Invalid JPEG marker at ${input.filePointer - 1}" }
                    var marker: Int
                    do { marker = input.readUnsignedByte() } while (marker == 255)
                    marker
                }
                val offset = input.filePointer
                val markerStart = offset - 2
                entropy = false
                if (byte == 0xd9) return JpegLayout(parts, offset, width, height, blockX, blockY)
                require(byte != 0xd8 && byte != 0 && byte !in 0xd0..0xd7) {
                    "Unexpected JPEG marker"
                }
                if (byte == 1) continue
                val length = input.readUnsignedShort()
                require(length >= 2 && length <= fileLength - offset) {
                    "Truncated JPEG segment"
                }
                val payload = ByteArray(length - 2)
                input.readFully(payload)
                require(parts.size < 4096) { "Too many JPEG segments" }
                parts += JpegPart(byte, ByteRange(markerStart, length + 2L), payload)
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

    private fun scanMarker(input: RandomAccessFile, buffer: ByteArray): Int {
        var prefix = false
        while (true) {
            val size = input.read(buffer)
            if (size < 0) throw EOFException("Missing JPEG end marker")
            for (i in 0 until size) {
                val byte = buffer[i].toInt() and 255
                // Stuffed bytes and restart markers are part of the compressed scan.
                if (prefix && byte != 255 && byte != 0 && byte !in 0xd0..0xd7) {
                    input.seek(input.filePointer - size + i + 1)
                    return byte
                }
                prefix = byte == 255
            }
        }
    }

    fun inspect(file: File): PhotoContainer? {
        file.inputStream().use { if (it.read() != 255 || it.read() != 216) return null }
        val layout = jpeg(file)
        var motion: Boolean? = null
        var microVideo: Boolean? = null
        var videoLength: Long? = null
        var videoMime: String? = null
        var microVideoLength: Long? = null
        var edit: ByteRange? = null
        var gainmap: ByteRange? = null
        for (part in layout.parts) {
            part.xmp()?.let { doc ->
                val nodes = doc.getElementsByTagName("*")
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    element.property(EDIT, "offset")?.let { offset ->
                        val length = requireNotNull(element.property(EDIT, "len")).toLong()
                        edit = checkedRange(file, file.length() - offset.toLong(), length, layout.end)
                        require(element.property(EDIT, "version") == "1") {
                            "Unsupported Xiaomi edit version"
                        }
                    }
                    element.property(CAMERA, "MotionPhoto")?.let { motion = it == "1" }
                    element.property(CAMERA, "MicroVideo")?.let { microVideo = it == "1" }
                    if (element.property(ITEM, "Semantic") == "MotionPhoto") {
                        videoLength = element.property(ITEM, "Length")?.toLongOrNull()
                        videoMime = element.property(ITEM, "Mime")
                    }
                    element.property(CAMERA, "MicroVideoOffset")?.let { microVideoLength = it.toLongOrNull() }
                }
            }
            if (part.marker == 0xe2 && part.payload.startsWith("MPF\u0000".toByteArray())) {
                val buffer = mpf(part.payload)
                val entries = mpEntries(buffer) ?: continue
                val pictures = entries.drop(1).map { at ->
                    val length = buffer.getInt(at + 4).toLong() and 0xffffffffL
                    val offset = buffer.getInt(at + 8).toLong() and 0xffffffffL
                    checkedRange(file, part.range.start + 8 + offset, length, layout.end)
                }.sortedBy { it.start }
                require(pictures.zipWithNext().all { (a, b) -> a.end <= b.start }) { "Overlapping MP images" }
                for (range in pictures) {
                    val auxiliary = jpeg(file, range.start)
                    if (auxiliary.end == range.end && auxiliary.parts.any { it.hasGainmapMetadata() }) {
                        gainmap = range
                        break
                    }
                }
            }
        }
        var video: MotionVideo? = null
        val length = videoLength ?: microVideoLength
        val enabled = motion ?: (videoLength != null || microVideo != false)
        // Editors may retain Motion Photo XMP after stripping the appended video.
        if (enabled && length != null && length in 16..(file.length() - (gainmap?.end ?: layout.end))) {
            val start = file.length() - length
            RandomAccessFile(file, "r").use { input ->
                input.seek(start)
                val size = input.readInt().toLong() and 0xffffffffL
                if (size in 16..length && input.readInt() == 0x66747970) { // ftyp
                    val quicktime = input.readInt() == 0x71742020 || videoMime == "video/quicktime" // qt brand
                    video = MotionVideo(ByteRange(start, length), if (quicktime) "mov" else "mp4")
                }
            }
        }
        edit?.let {
            require(
                it.start >= (gainmap?.end ?: layout.end) &&
                    it.end <= (video?.range?.start ?: file.length())
            ) {
                "Overlapping image attachments"
            }
        }
        return PhotoContainer(layout, gainmap, video, edit)
    }

    private fun Element.property(namespace: String, name: String): String? {
        if (hasAttributeNS(namespace, name)) return getAttributeNS(namespace, name)
        var node = firstChild
        while (node != null) {
            if (node.namespaceURI == namespace && node.localName == name) return node.textContent.trim()
            node = node.nextSibling
        }
        return null
    }

    private fun JpegPart.hasGainmapMetadata(): Boolean {
        if (marker == 0xe2) return payload.startsWith(isoGainmapPrefix) && payload.size > isoGainmapPrefix.size + 4
        val doc = xmp() ?: return false
        val descriptions = doc.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description")
        return (0 until descriptions.length).any { (descriptions.item(it) as Element).property(HDRGM, "Version") == "1.0" }
    }

    fun clean(
        source: File,
        target: File,
        info: PhotoContainer,
        static: Boolean,
        metadata: Boolean,
        check: () -> Unit = {},
    ) {
        require(source.canonicalFile != target.canonicalFile)
        val gainmap = if (metadata) info.gainmap else null
        if (gainmap == null) {
            writeClean(source, info, target, static, metadata, null, check)
            return
        }
        val auxiliary = File.createTempFile("gainmap-", ".jpg", target.parentFile)
        val cleaned = File.createTempFile("gainmap-clean-", ".jpg", target.parentFile)
        try {
            RandomAccessFile(source, "r").use { input ->
                auxiliary.outputStream().use { copyRange(input, it, gainmap, check = check) }
            }
            val auxiliaryInfo = requireNotNull(inspect(auxiliary)) { "JPEG gainmap required" }
            writeClean(auxiliary, auxiliaryInfo, cleaned, true, true, null, check)
            writeClean(source, info, target, static, metadata, cleaned, check)
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
        metadata: Boolean,
        auxiliary: File?,
        check: () -> Unit,
        exif: ByteArray? = null,
    ): PhotoContainer {
        val keepVideo = !static && !metadata && info.video != null
        val gainmapLength = auxiliary?.length() ?: info.gainmap?.length
        val directoryItems = buildList {
            if (gainmapLength != null || keepVideo) add(Triple("Primary", 0L, "image/jpeg"))
            if (gainmapLength != null) add(Triple("GainMap", gainmapLength, "image/jpeg"))
            if (keepVideo) add(Triple("MotionPhoto", info.video.range.length, MediaFiles.mime(info.video.extension, ImportType.VIDEO)))
        }
        val rewritten = mutableListOf<Pair<JpegPart, ByteArray?>>()
        val parts = info.jpeg.parts.toMutableList()
        if (exif != null && parts.none { it.marker == 0xe1 && it.payload.startsWith("Exif\u0000\u0000".toByteArray()) })
            parts.add(0, JpegPart(0xe1, ByteRange(2, 0), "Exif\u0000\u0000".toByteArray()))
        val directoryPacket = parts.firstOrNull { it.marker == 0xe1 && it.payload.startsWith(xmpPrefix) }
        for (part in parts) {
            val content = part.payload
            val isXmp = part.marker == 0xe1 && content.startsWith(xmpPrefix)
            val isMpf = part.marker == 0xe2 && content.startsWith("MPF\u0000".toByteArray())
            val essential =
                (part.marker == 0xe2 &&
                    (content.startsWith("ICC_PROFILE".toByteArray()) ||
                        content.startsWith(isoGainmapPrefix))) ||
                    (part.marker == 0xee && content.startsWith("Adobe".toByteArray()))
            val replacement =
                when {
                    exif != null && part.marker == 0xe1 && content.startsWith("Exif\u0000\u0000".toByteArray()) -> exif
                    isMpf -> if (exif != null) content.copyOf() else info.gainmap?.let {
                        gainmapIndex(content, it.start - part.range.start - 8)
                    }
                    isXmp -> if (exif != null) content else rewriteXmp(part.xmp(), keepVideo, metadata,
                        if (part === directoryPacket) directoryItems else emptyList())
                    metadata && part.marker == 0xe0 ->
                        if (content.startsWith("JFIF\u0000".toByteArray()) && content.size >= 14)
                            content.copyOf(14).apply {
                                this[12] = 0
                                this[13] = 0
                            }
                        else null
                    exif == null && info.edit != null && part.marker == 0xe4 -> null
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
                mpEntries(buffer)?.let { entries ->
                    require(primaryLength <= 0xffffffffL)
                    buffer.putInt(entries.first + 4, primaryLength.toInt())
                    for (at in entries.drop(1)) {
                        val length = auxiliary?.length() ?: (buffer.getInt(at + 4).toLong() and 0xffffffffL)
                        val offset = if (exif != null)
                            (buffer.getInt(at + 8).toLong() and 0xffffffffL) + delta - precedingDelta
                        else primaryLength - part.range.start - precedingDelta - 8
                        require(length in 1..0xffffffffL && offset in 0..0xffffffffL)
                        buffer.putInt(at + 4, length.toInt())
                        buffer.putInt(at + 8, offset.toInt())
                    }
                }
            }
            precedingDelta += (payload?.size?.plus(4)?.toLong() ?: 0L) - part.range.length
        }
        RandomAccessFile(source, "r").use { input ->
            target.outputStream().buffered(256 * 1024).use { output ->
                val buffer = ByteArray(256 * 1024)
                fun copy(range: ByteRange, file: RandomAccessFile = input) =
                    copyRange(file, output, range, buffer, check)
                output.write(byteArrayOf(-1, -40))
                var position = 2L
                for ((part, payload) in rewritten) {
                    copy(ByteRange(position, part.range.start - position))
                    if (payload != null) {
                        output.write(255)
                        output.write(part.marker)
                        output.write((payload.size + 2) ushr 8)
                        output.write((payload.size + 2) and 255)
                        output.write(payload)
                    }
                    position = part.range.end
                }
                copy(ByteRange(position, info.jpeg.end - position))
                if (exif != null) {
                    copy(ByteRange(info.jpeg.end, source.length() - info.jpeg.end))
                } else {
                    if (auxiliary != null)
                        RandomAccessFile(auxiliary, "r").use {
                            copy(ByteRange(0, auxiliary.length()), it)
                        }
                    else info.gainmap?.let { copy(it) }
                    if (keepVideo) copy(info.video.range)
                }
            }
        }
        val result = requireNotNull(inspect(target))
        kotlin.check(result.jpeg.width == info.jpeg.width && result.jpeg.height == info.jpeg.height)
        kotlin.check((result.video != null) == keepVideo && (result.edit != null) == (exif != null && info.edit != null))
        kotlin.check(result.gainmap?.length == gainmapLength)
        return result
    }

    fun replaceExif(source: File, target: File, info: PhotoContainer, exif: ByteArray, check: () -> Unit = {}): PhotoContainer {
        require(source.canonicalFile != target.canonicalFile)
        require(exif.size <= 65533)
        return writeClean(source, info, target, false, false, null, check, exif)
    }

    private fun rewriteXmp(
        source: Document?,
        video: Boolean,
        metadata: Boolean,
        directoryItems: List<Triple<String, Long, String>>,
    ): ByteArray? {
        val doc =
            if (metadata) {
                val fresh =
                    xml(
                        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                            "<rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>"
                    )
                val destination =
                    fresh
                        .getElementsByTagNameNS(
                            "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                            "Description",
                        )
                        .item(0) as Element
                val nodes = source?.getElementsByTagName("*")
                if (nodes != null) for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    if (
                        element.namespaceURI == HDRGM &&
                            element.parentNode.localName == "Description"
                    )
                        destination.appendChild(fresh.importNode(element, true))
                    for (j in 0 until element.attributes.length) {
                        val attr = element.attributes.item(j)
                        if (attr.namespaceURI == HDRGM)
                            destination.setAttributeNS(
                                attr.namespaceURI,
                                attr.nodeName,
                                attr.nodeValue,
                            )
                    }
                }
                fresh
            } else source ?: throw UnsupportedMediaException(R.string.invalid_image_container)
        val nodes = doc.getElementsByTagName("*")
        val elements = (0 until nodes.length).map { nodes.item(it) as Element }
        fun removeProperty(namespace: String?, name: String?): Boolean {
            if (namespace != CAMERA) return namespace == EDIT
            if (video) return name == "MotionPhoto" || name == "MotionPhotoVersion"
            return name?.startsWith("MotionPhoto") == true || name?.startsWith("MicroVideo") == true
        }
        for (element in elements) {
            val attributes = element.attributes
            for (i in attributes.length - 1 downTo 0) {
                val attr = attributes.item(i)
                if (
                    removeProperty(attr.namespaceURI, attr.localName) ||
                        (metadata &&
                            attr.namespaceURI !in
                                setOf(
                                    CAMERA,
                                    CONTAINER,
                                    ITEM,
                                    HDRGM,
                                    "http://www.w3.org/2000/xmlns/",
                                    "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                                ))
                )
                    element.removeAttributeNode(attr as org.w3c.dom.Attr)
            }
            if (removeProperty(element.namespaceURI, element.localName) ||
                (element.namespaceURI == CONTAINER && element.localName == "Directory")) {
                element.parentNode?.removeChild(element)
            }
        }
        val descriptions =
            doc.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description")
        if (
            metadata &&
                directoryItems.isEmpty() &&
                (0 until descriptions.length).all { index ->
                    val element = descriptions.item(index) as Element
                    return@all !element.hasChildNodes() &&
                        (0 until element.attributes.length).none { element.attributes.item(it).namespaceURI == HDRGM }
                }
        )
            return null
        if (directoryItems.isNotEmpty()) {
            val root = descriptions.item(0) as? Element ?: throw UnsupportedMediaException(R.string.invalid_image_container)
            if (video) {
                root.setAttributeNS(CAMERA, "GCamera:MotionPhoto", "1")
                root.setAttributeNS(CAMERA, "GCamera:MotionPhotoVersion", "1")
            }
            val directory = doc.createElementNS(CONTAINER, "Container:Directory")
            val seq = doc.createElementNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdf:Seq")
            directory.appendChild(seq)
            root.appendChild(directory)
            for ((semantic, length, mime) in directoryItems) {
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

    private fun JpegPart.xmp(): Document? {
        if (marker != 0xe1 || !payload.startsWith(xmpPrefix)) return null
        val text = payload.decodeToString(xmpPrefix.size, payload.size).removePrefix("\uFEFF").trimEnd('\u0000')
        if (text.contains("<!DOCTYPE", true) || text.contains("<!ENTITY", true)) return null
        return try { xml(text) } catch (_: SAXException) { null }
    }

    private fun xml(text: String): Document =
        DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(text)))

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

    private fun mpEntries(buffer: ByteBuffer): IntProgression? {
        val offset = buffer.getInt(4)
        require(offset in 8..buffer.capacity() - 2)
        val count = buffer.getShort(offset).toInt() and 65535
        require(offset + 2 + count * 12 <= buffer.capacity())
        for (i in 0 until count) {
            val at = offset + 2 + i * 12
            if ((buffer.getShort(at).toInt() and 65535) == 0xb002) {
                val length = buffer.getInt(at + 4)
                val table = buffer.getInt(at + 8)
                require(length >= 16 && length % 16 == 0 && table in 8..buffer.capacity() - length) {
                    "Unsupported MPF layout"
                }
                return table until table + length step 16
            }
        }
        return null
    }

    // A cleaned photo retains only the current picture and its HDR gainmap.
    private fun gainmapIndex(payload: ByteArray, gainmapOffset: Long): ByteArray? {
        val buffer = mpf(payload)
        val entries = mpEntries(buffer) ?: return null
        val gainmap = entries.drop(1).firstOrNull {
            (buffer.getInt(it + 8).toLong() and 0xffffffffL) == gainmapOffset
        } ?: return null
        return ByteBuffer.allocate(86).order(buffer.order()).apply {
            put(payload, 0, 8)
            putInt(8).putShort(3)
            putShort(0xb000.toShort()).putShort(7).putInt(4).put("0100".toByteArray())
            putShort(0xb001.toShort()).putShort(4).putInt(1).putInt(2)
            putShort(0xb002.toShort()).putShort(7).putInt(32).putInt(50).putInt(0)
            for (at in listOf(entries.first, gainmap)) {
                putInt(buffer.getInt(at) and 0x3fffffff)
                putInt(buffer.getInt(at + 4)).putInt(0).putInt(0)
            }
        }.array()
    }

    fun extractVideo(source: File, target: File, range: ByteRange, check: () -> Unit = {}) {
        try {
            RandomAccessFile(source, "r").use { input ->
                target.outputStream().use { output -> copyRange(input, output, range, check = check) }
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    fun copyRange(
        input: RandomAccessFile,
        output: OutputStream,
        range: ByteRange,
        buffer: ByteArray = ByteArray(256 * 1024),
        check: () -> Unit,
    ) {
        require(range.length >= 0)
        if (range.length == 0L) return
        input.seek(range.start)
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
