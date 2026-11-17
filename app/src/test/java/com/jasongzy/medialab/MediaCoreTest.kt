package com.jasongzy.medialab

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaCoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun colorModelsRequireRecognizedFileMarkers() {
        val jpeg = Containers.inspect(compoundPhoto())!!.jpeg
        assertEquals("YCbCr", jpeg.colorModel)
        val unmarked = jpeg.copy(parts = jpeg.parts.filter { it.marker != 0xe0 && it.marker != 0xee })
        assertNull(unmarked.colorModel)
        for ((transform, model) in listOf(0 to "RGB", 1 to "YCbCr")) {
            val adobe = "Adobe".toByteArray() + ByteArray(6) + byteArrayOf(transform.toByte())
            assertEquals(model, unmarked.copy(parts = unmarked.parts + JpegPart(0xee, ByteRange(0, 0), adobe)).colorModel)
        }
        for ((type, model) in listOf(BufferedImage.TYPE_INT_RGB to "RGB", BufferedImage.TYPE_BYTE_GRAY to "Gray")) {
            val file = folder.newFile()
            ImageIO.write(BufferedImage(2, 2, type), "png", file)
            assertEquals(model, RasterMetadata.colorModel(file, "image/png"))
        }
        for ((type, model) in listOf("VP8 " to "YCbCr", "VP8L" to "RGB")) {
            val file = folder.newFile()
            val bytes = "RIFF".toByteArray() + byteArrayOf(14, 0, 0, 0) +
                "WEBP$type".toByteArray() + byteArrayOf(2, 0, 0, 0, 0, 0)
            file.writeBytes(bytes + "appended data".toByteArray())
            assertEquals(model, RasterMetadata.colorModel(file, "image/webp"))
            val clean = folder.newFile()
            RasterMetadata.clean(file, clean, "image/webp") {}
            assertArrayEquals(bytes, clean.readBytes())
            file.writeBytes(bytes.copyOf(bytes.size - 1))
            assertNull(RasterMetadata.colorModel(file, "image/webp"))
        }
    }

    @Test
    fun compoundPhotoKeepsCurrentPixelsAndGainmap() {
        val source = compoundPhoto()
        val before = Containers.inspect(source)!!
        val videoRange = checkNotNull(before.video).range
        assertEquals(before.edit!!.start, before.gainmap!!.end)
        val tagged = folder.newFile("tagged.jpg")
        val taggedInfo = Containers.replaceExif(source, tagged, before, "Exif\u0000\u0000updated-tag".toByteArray())
        assertArrayEquals(scan(source, before.jpeg), scan(tagged, taggedInfo.jpeg))
        assertArrayEquals(range(source, ByteRange(before.jpeg.end, source.length() - before.jpeg.end)),
            range(tagged, ByteRange(taggedInfo.jpeg.end, tagged.length() - taggedInfo.jpeg.end)))
        assertArrayEquals(range(source, before.gainmap), range(tagged, taggedInfo.gainmap!!))
        assertArrayEquals(range(source, videoRange), range(tagged, taggedInfo.video!!.range))
        assertArrayEquals(range(source, before.edit), range(tagged, taggedInfo.edit!!))
        assertArrayEquals(before.jpeg.parts.first { it.marker == 0xe1 }.payload,
            taggedInfo.jpeg.parts.first { it.marker == 0xe1 &&
                it.payload.toString(Charsets.UTF_8).startsWith("http://ns.adobe.com/xap/1.0/") }.payload)
        assertEquals("mov", before.video.extension)
        val extracted = folder.newFile("motion.mov")
        Containers.extractVideo(source, extracted, videoRange)
        assertArrayEquals(range(source, videoRange), extracted.readBytes())
        assertThrows(InterruptedException::class.java) {
            Containers.extractVideo(source, extracted, videoRange) { throw InterruptedException() }
        }
        assertFalse(extracted.exists())
        val target = folder.newFile("static.jpg")
        Containers.clean(source, target, before, static = false, metadata = true)
        val after = Containers.inspect(target)!!
        assertNull(after.video)
        assertNull(after.edit)
        assertNotNull(after.gainmap)
        assertEquals(after.gainmap!!.end, target.length())
        assertEquals(after.jpeg.end, after.gainmap.start)
        assertEquals(listOf("Primary" to 0L, "GainMap" to after.gainmap.length), directory(after))
        assertArrayEquals(scan(source, before.jpeg), scan(target, after.jpeg))
        assertArrayEquals(
            scan(source, Containers.jpeg(source, before.gainmap.start)),
            scan(target, Containers.jpeg(target, after.gainmap.start)),
        )
        assertFalse(target.readBytes().toString(Charsets.ISO_8859_1).contains("private-location"))
        val original = ImageIO.read(source)
        val result = ImageIO.read(target)
        assertArrayEquals(
            original.getRGB(0, 0, 32, 24, null, 0, 32),
            result.getRGB(0, 0, 32, 24, null, 0, 32),
        )
        val motion = folder.newFile("motion.jpg")
        Containers.clean(source, motion, before, static = false, metadata = false)
        val motionInfo = Containers.inspect(motion)!!
        assertNull(motionInfo.edit)
        assertTrue(motion.readBytes().decodeToString().contains("video/quicktime"))
        assertEquals(listOf("Primary" to 0L, "GainMap" to motionInfo.gainmap!!.length,
            "MotionPhoto" to motionInfo.video!!.range.length), directory(motionInfo))
        assertEquals(motionInfo.gainmap.end, motionInfo.video.range.start)
        assertArrayEquals(range(source, videoRange), range(motion, motionInfo.video.range))
    }

    @Test
    fun scanMarkersCrossReadBoundaries() {
        val block = 64 * 1024
        val scan = ByteArray(3 * block)
        scan[block - 1] = -1 // FF 00
        scan[2 * block - 1] = -1
        scan[2 * block] = 0xd7.toByte()
        scan[scan.lastIndex - 1] = -1
        scan[scan.lastIndex] = -1
        val sos = segment(0xda, byteArrayOf(1, 1, 0, 0, 63, 0))
        val file = folder.newFile()
        file.writeBytes(byteArrayOf(-1, -40) + sos + scan + byteArrayOf(0xfe.toByte(), 0, 3, 42) +
            sos + byteArrayOf(1, -1, -39, 99))
        val layout = Containers.jpeg(file)
        assertEquals(listOf(0xda, 0xfe, 0xda), layout.parts.map { it.marker })
        assertEquals(ByteRange((2 + sos.size + scan.lastIndex).toLong(), 5), layout.parts[1].range)
        assertEquals(file.length() - 1, layout.end)
    }

    @Test
    fun motionMetadataRequiresAnEnabledAndPresentVideo() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
        val video = ByteBuffer.allocate(24).putInt(24).put("ftypmp42".toByteArray()).array()
        for ((flags, append, expected) in listOf(
            Triple("GCamera:MicroVideo=\"0\"", true, false),
            Triple("GCamera:MicroVideo=\"1\"", true, true),
            Triple("GCamera:MotionPhoto=\"0\" GCamera:MicroVideo=\"1\"", true, false),
            Triple("GCamera:MotionPhoto=\"1\" GCamera:MicroVideo=\"0\"", true, true),
            Triple("GCamera:MotionPhoto=\"1\"", false, false),
            Triple("", true, true),
        )) {
            val attributes = "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" $flags GCamera:MicroVideoOffset=\"24\""
            val source = folder.newFile()
            source.writeBytes(jpeg.take(2).toByteArray() + segment(0xe1, xmp(attributes) + byteArrayOf(0)) +
                jpeg.drop(2).toByteArray() + if (append) video else byteArrayOf())
            val info = Containers.inspect(source)!!
            assertEquals(expected, info.video != null)
            if (!append) {
                val output = folder.newFile()
                Containers.clean(source, output, info, static = true, metadata = true)
                assertArrayEquals(jpeg, output.readBytes())
            }
        }
        for (flag in listOf("0", "1")) {
            val source = folder.newFile()
            source.writeBytes(jpeg.take(2).toByteArray() + segment(0xe1, xmp(
                "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" GCamera:MicroVideoOffset=\"0\" GCamera:BurstID=\"burst-123\"",
                "<GCamera:MotionPhoto>$flag</GCamera:MotionPhoto><GCamera:SpecialTypeID>Camera feature</GCamera:SpecialTypeID>" +
                    "<Item:Item xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"><Item:Semantic>MotionPhoto</Item:Semantic><Item:Length>24</Item:Length></Item:Item>",
            )) + segment(0xe4, "camera-specific data".toByteArray()) + jpeg.drop(2).toByteArray() + video)
            val info = Containers.inspect(source)!!
            assertEquals(if (flag == "1") "mp4" else null, info.video?.extension)
            val output = folder.newFile()
            Containers.clean(source, output, info, static = true, metadata = false)
            val cleaned = output.readBytes().decodeToString()
            assertFalse(cleaned.contains("<GCamera:MotionPhoto>"))
            assertFalse(cleaned.contains("MicroVideoOffset"))
            assertTrue(cleaned.contains("burst-123") && cleaned.contains("Camera feature"))
            assertTrue(cleaned.contains("camera-specific data"))
        }
    }

    @Test
    fun unreadableXmpDoesNotHideThePictureOrValidAttachments() {
        val bytes = compoundPhoto().readBytes()
        val source = folder.newFile()
        val badPacket = segment(0xe1, "http://ns.adobe.com/xap/1.0/\u0000<broken".toByteArray())
        source.writeBytes(bytes.copyOfRange(0, 2) + badPacket + bytes.copyOfRange(2, bytes.size))
        val info = Containers.inspect(source)!!
        assertNotNull(info.video)
        assertNotNull(info.edit)
        val output = folder.newFile()
        assertThrows(UnsupportedMediaException::class.java) {
            Containers.clean(source, output, info, static = true, metadata = false)
        }
        Containers.clean(source, output, info, static = false, metadata = true)
        val cleaned = Containers.inspect(output)!!
        assertArrayEquals(scan(source, info.jpeg), scan(output, cleaned.jpeg))
        assertEquals(listOf("Primary" to 0L, "GainMap" to cleaned.gainmap!!.length), directory(cleaned))
        assertFalse(output.readBytes().decodeToString().contains("<broken"))
    }

    @Test
    fun invalidAttachmentsAndCancelledCopiesFailClosed() {
        val source = compoundPhoto()
        val broken = folder.newFile("broken.jpg")
        val data = source.readBytes()
        val text = data.toString(Charsets.ISO_8859_1)
        val start = text.indexOf("MiCamEdit:offset=\"") + "MiCamEdit:offset=\"".length
        val end = text.indexOf('"', start)
        for (i in start until end) data[i] = '9'.code.toByte()
        broken.writeBytes(data)
        assertThrows(IllegalArgumentException::class.java) { Containers.inspect(broken) }
        assertThrows(InterruptedException::class.java) {
            Containers.clean(source, folder.newFile(), Containers.inspect(source)!!, true, true) {
                throw InterruptedException()
            }
        }
    }

    @Test
    fun pngMetadataRemovalPreservesPixels() {
        val source = folder.newFile("input.png")
        val image = BufferedImage(7, 9, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(2, 3, 0x814599ff.toInt())
        val encoded = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        fun chunk(type: String, data: ByteArray): ByteArray {
            val payload = type.toByteArray(Charsets.US_ASCII) + data
            val crc = java.util.zip.CRC32().apply { update(payload) }
            return ByteBuffer.allocate(payload.size + 8).putInt(data.size).put(payload).putInt(crc.value.toInt()).array()
        }
        val color = chunk("sBIT", byteArrayOf(8, 8, 8, 8)) + chunk("cICP", byteArrayOf(9, 16, 0, 1)) +
            chunk("mDCV", ByteArray(24)) + chunk("cLLI", ByteBuffer.allocate(8).putInt(10000000).putInt(2500000).array())
        val expected = encoded.copyOfRange(0, 33) + color + encoded.copyOfRange(33, encoded.size)
        source.writeBytes(encoded.copyOfRange(0, 33) + chunk("tEXt", "Comment\u0000private-location".toByteArray()) +
            expected.copyOfRange(33, expected.size))
        val output = folder.newFile("output.png")
        output.writeBytes(source.readBytes())
        RasterMetadata.clean(source, output, "image/png") {}
        assertArrayEquals(expected, output.readBytes())
        for (end in listOf(byteArrayOf(), chunk("IEND", byteArrayOf(1)))) {
            source.writeBytes(encoded.copyOf(encoded.size - 12) + end)
            val error = assertThrows(UnsupportedMediaException::class.java) {
                RasterMetadata.clean(source, output, "image/png") {}
            }
            assertEquals(R.string.invalid_image_container, error.messageRes)
        }
    }

    @Test
    fun representativeColorsAreActualSamples() {
        val samples =
            listOf(
                0xff123456.toInt(),
                0xff133557.toInt(),
                0xffff0000.toInt(),
                0xff00ff00.toInt(),
                0xff0000ff.toInt(),
            )
        val result = representativeColors(samples + List(20) { samples[0] } + listOf(0x00123456))
        assertEquals(4, result.size)
        assertEquals(samples[0], result.first())
        assertTrue(result.all { it in samples })
        val grays = (0..3).map { 0xff808080.toInt() + it * 0x010101 }
        assertEquals(grays.toSet(), representativeColors(grays).toSet())
        assertTrue(representativeColors(listOf(0)).isEmpty())
    }

    private fun compoundPhoto(): File {
        val image = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 24) for (x in 0 until 32) image.setRGB(
            x,
            y,
            (x * 7 shl 16) or (y * 10 shl 8) or 0x55,
        )
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
        val alternate = jpeg.take(2).toByteArray() +
            segment(0xfe, "hdr-gain-map".toByteArray()) +
            segment(0xe1, xmp("", "<note>http://ns.adobe.com/hdr-gain-map/1.0/</note>")) +
            jpeg.drop(2).toByteArray()
        val gainmap =
            jpeg.copyOfRange(0, 2) +
                segment(
                    0xe1,
                    xmp(
                        "xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\""
                    ),
                ) +
                segment(0xfe, "private-location".toByteArray()) + jpeg.copyOfRange(2, jpeg.size)
        val edit = "private-original-and-edit-record".toByteArray()
        val video =
            ByteBuffer.allocate(24)
                .putInt(24)
                .put("ftypqt  ".toByteArray())
                .putInt(0)
                .put("qt  isom".toByteArray())
                .array()
        val xmp =
            segment(
                0xe1,
                xmp(
                    "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" GCamera:MotionPhoto=\"1\" GCamera:MicroVideoOffset=\"${video.size}\" " +
                        "xmlns:MiCamEdit=\"http://ns.xiaomi.com/photos/1.0/camera/reedit\" MiCamEdit:version=\"1\" MiCamEdit:offset=\"${edit.size + video.size}\" MiCamEdit:len=\"${edit.size}\"",
                    "<dc:location xmlns:dc=\"http://purl.org/dc/elements/1.1/\">private-location</dc:location>",
                ),
            )
        val mpf =
            ByteBuffer.allocate(102)
                .put("MPF\u0000MM".toByteArray())
                .putShort(42)
                .putInt(8)
                .putShort(3)
        mpf.putShort(0xb000.toShort()).putShort(7).putInt(4).put("0100".toByteArray())
        mpf.putShort(0xb001.toShort()).putShort(4).putInt(1).putInt(3)
        mpf.putShort(0xb002.toShort()).putShort(7).putInt(48).putInt(50).putInt(0)
        val fill = byteArrayOf(-1, -1)
        val primaryLength = jpeg.size + xmp.size + fill.size + 4 + mpf.capacity()
        val secondOffset = primaryLength - (2 + xmp.size + fill.size + 8)
        mpf.putInt(0x030000).putInt(primaryLength).putInt(0).putInt(0)
        mpf.putInt(0).putInt(alternate.size).putInt(secondOffset).putInt(0)
        mpf.putInt(0).putInt(gainmap.size).putInt(secondOffset + alternate.size).putInt(0)
        return folder.newFile().apply {
            writeBytes(
                jpeg.copyOfRange(0, 2) +
                    xmp + fill +
                    segment(0xe2, mpf.array()) +
                    jpeg.copyOfRange(2, jpeg.size) +
                    alternate +
                    gainmap +
                    edit +
                    video
            )
        }
    }

    private fun xmp(attributes: String, child: String = ""): ByteArray =
        ("http://ns.adobe.com/xap/1.0/\u0000" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description $attributes>$child</rdf:Description></rdf:RDF></x:xmpmeta>")
            .toByteArray()

    private fun segment(marker: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(payload.size + 4)
            .put(255.toByte())
            .put(marker.toByte())
            .putShort((payload.size + 2).toShort())
            .put(payload)
            .array()

    private fun range(file: File, range: ByteRange): ByteArray =
        file.readBytes().copyOfRange(range.start.toInt(), range.end.toInt())

    private fun directory(photo: PhotoContainer): List<Pair<String, Long>> {
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        val ns = "http://ns.google.com/photos/1.0/container/item/"
        return photo.jpeg.parts.filter { it.marker == 0xe1 && it.payload.decodeToString().startsWith("http://ns.adobe.com/xap/") }
            .flatMap { part ->
                val start = part.payload.indexOf(0) + 1
                val xml = parser.parse(part.payload.inputStream(start, part.payload.size - start))
                val items = xml.getElementsByTagNameNS("http://ns.google.com/photos/1.0/container/", "Item")
                (0 until items.length).map { index ->
                    val item = items.item(index) as Element
                    item.getAttributeNS(ns, "Semantic") to (item.getAttributeNS(ns, "Length").toLongOrNull() ?: 0L)
                }
            }
    }

    private fun scan(file: File, jpeg: JpegLayout): ByteArray =
        range(
            file,
            ByteRange(
                jpeg.parts.first { it.marker == 0xda }.range.end,
                jpeg.end - jpeg.parts.first { it.marker == 0xda }.range.end,
            ),
        )
}
