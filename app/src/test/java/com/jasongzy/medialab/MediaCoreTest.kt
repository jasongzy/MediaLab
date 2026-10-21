package com.jasongzy.medialab

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
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
            file.writeBytes(bytes)
            assertEquals(model, RasterMetadata.colorModel(file, "image/webp"))
            file.writeBytes(bytes.copyOf(bytes.size - 1))
            assertNull(RasterMetadata.colorModel(file, "image/webp"))
        }
    }

    @Test
    fun compoundPhotoKeepsCurrentPixelsAndGainmap() {
        val source = compoundPhoto()
        val before = Containers.inspect(source)!!
        assertNotNull(before.video)
        assertNotNull(before.edit)
        assertNotNull(before.gainmap)
        val target = folder.newFile("static.jpg")
        Containers.clean(source, target, static = true, flatten = true, metadata = true)
        val after = Containers.inspect(target)!!
        assertNull(after.video)
        assertNull(after.edit)
        assertNotNull(after.gainmap)
        val privateCleanup = folder.newFile("private.jpg")
        Containers.clean(source, privateCleanup, static = false, flatten = false, metadata = true)
        assertNull(Containers.inspect(privateCleanup)!!.video)
        assertNull(Containers.inspect(privateCleanup)!!.edit)
        assertEquals(after.gainmap!!.end, target.length())
        assertArrayEquals(scan(source, before.jpeg), scan(target, after.jpeg))
        assertArrayEquals(
            scan(source, Containers.jpeg(source, before.gainmap!!.start)),
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
        Containers.clean(source, motion, static = false, flatten = true, metadata = false)
        val motionInfo = Containers.inspect(motion)!!
        assertNull(motionInfo.edit)
        assertArrayEquals(range(source, before.video!!), range(motion, motionInfo.video!!))
        val unchanged = folder.newFile("retained.jpg")
        Containers.clean(source, unchanged, static = false, flatten = false, metadata = false)
        assertArrayEquals(
            range(source, before.edit!!),
            range(unchanged, Containers.inspect(unchanged)!!.edit!!),
        )
    }

    @Test
    fun motionMetadataRequiresAnEnabledAndPresentVideo() {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
        val video = ByteBuffer.allocate(24).putInt(24).put("ftypmp42".toByteArray()).array()
        for ((flag, offset, append) in listOf(
            Triple("0", 0, false), Triple("0", 24, true),
            Triple("1", 24, false), Triple("1", 24, true), Triple("", 24, true),
        )) {
            val attributes = "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
                (if (flag.isEmpty()) "" else "GCamera:MotionPhoto=\"$flag\" ") + "GCamera:MicroVideoOffset=\"$offset\""
            val source = folder.newFile()
            source.writeBytes(jpeg.take(2).toByteArray() + segment(0xe1, xmp(attributes)) +
                jpeg.drop(2).toByteArray() + if (append) video else byteArrayOf())
            assertEquals(append && flag != "0", Containers.inspect(source)!!.video != null)
            val output = folder.newFile()
            Containers.clean(source, output, true, true, true)
            assertNull(Containers.inspect(output)!!.video)
            assertEquals(jpeg.size.toLong(), output.length())
        }
        val source = folder.newFile()
        source.writeBytes(jpeg.take(2).toByteArray() + segment(0xe1, xmp(
            "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" GCamera:MotionPhoto=\"1\" GCamera:MicroVideoOffset=\"0\"",
            "<Item:Item xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" Item:Semantic=\"MotionPhoto\" Item:Length=\"24\"/>",
        )) + jpeg.drop(2).toByteArray() + video)
        assertEquals(24L, Containers.inspect(source)!!.video!!.length)
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
            Containers.clean(source, folder.newFile(), true, true, true) {
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
        val text = "Comment\u0000private-location".toByteArray()
        val chunk =
            ByteBuffer.allocate(text.size + 12)
                .putInt(text.size)
                .put("tEXt".toByteArray())
                .put(text)
                .putInt(0)
                .array()
        source.writeBytes(
            encoded.copyOfRange(0, 33) + chunk + encoded.copyOfRange(33, encoded.size)
        )
        val output = folder.newFile("output.png")
        RasterMetadata.clean(source, output, "image/png") {}
        assertArrayEquals(encoded, output.readBytes())
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
        val gainmap =
            jpeg.copyOfRange(0, 2) +
                segment(
                    0xe1,
                    xmp(
                        "xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\""
                    ),
                ) +
                jpeg.copyOfRange(2, jpeg.size)
        val edit = "private-original-and-edit-record".toByteArray()
        val video =
            ByteBuffer.allocate(24)
                .putInt(24)
                .put("ftypmp42".toByteArray())
                .putInt(0)
                .put("mp42isom".toByteArray())
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
            ByteBuffer.allocate(86)
                .put("MPF\u0000MM".toByteArray())
                .putShort(42)
                .putInt(8)
                .putShort(3)
        mpf.putShort(0xb000.toShort()).putShort(7).putInt(4).put("0100".toByteArray())
        mpf.putShort(0xb001.toShort()).putShort(4).putInt(1).putInt(2)
        mpf.putShort(0xb002.toShort()).putShort(7).putInt(32).putInt(50).putInt(0)
        val primaryLength = jpeg.size + xmp.size + 90
        mpf.putInt(0x030000).putInt(primaryLength).putInt(0).putInt(0)
        mpf.putInt(0).putInt(gainmap.size).putInt(primaryLength - (2 + xmp.size + 8)).putInt(0)
        return folder.newFile().apply {
            writeBytes(
                jpeg.copyOfRange(0, 2) +
                    xmp +
                    segment(0xe2, mpf.array()) +
                    jpeg.copyOfRange(2, jpeg.size) +
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

    private fun scan(file: File, jpeg: JpegLayout): ByteArray =
        range(
            file,
            ByteRange(
                jpeg.parts.first { it.marker == 0xda }.range.end,
                jpeg.end - jpeg.parts.first { it.marker == 0xda }.range.end,
            ),
        )
}
