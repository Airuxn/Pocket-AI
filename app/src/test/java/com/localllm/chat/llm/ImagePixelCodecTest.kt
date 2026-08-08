package com.localllm.chat.llm

import android.graphics.BitmapFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImagePixelCodecTest {
    private fun bigEndian(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        return bigEndian(data.size) + typeBytes + data + bigEndian(crc)
    }

    /** Minimal truecolour PNG encoder — the JDK image libraries are off the unit-test classpath. */
    private fun pngBytes(width: Int, height: Int): ByteArray {
        val scanlines = ByteArrayOutputStream()
        for (y in 0 until height) {
            scanlines.write(0) // filter type: none
            for (x in 0 until width) {
                scanlines.write((x * 7) and 0xFF)
                scanlines.write((y * 11) and 0xFF)
                scanlines.write(((x + y) * 13) and 0xFF)
            }
        }
        val pixels = ByteArrayOutputStream().also { compressed ->
            DeflaterOutputStream(compressed).use { it.write(scanlines.toByteArray()) }
        }.toByteArray()

        val header = bigEndian(width) + bigEndian(height) +
            byteArrayOf(8, 2, 0, 0, 0) // 8-bit RGB, no interlace

        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            pngChunk("IHDR", header) +
            pngChunk("IDAT", pixels) +
            pngChunk("IEND", ByteArray(0))
    }

    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun detectsPngMagic() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
        assertTrue(ImagePixelCodec.isPng(png))
        assertFalse(ImagePixelCodec.isJpeg(png))
    }

    @Test
    fun detectsJpegMagic() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertTrue(ImagePixelCodec.isJpeg(jpeg))
        assertFalse(ImagePixelCodec.isPng(jpeg))
    }

    @Test
    fun emptyArrayIsNotPngOrJpeg() {
        assertFalse(ImagePixelCodec.isPng(byteArrayOf()))
        assertFalse(ImagePixelCodec.isJpeg(byteArrayOf()))
    }

    @Test
    fun shortArrayIsNotPng() {
        assertFalse(ImagePixelCodec.isPng(byteArrayOf(0x89.toByte(), 0x50)))
    }

    @Test
    fun emptyInputProducesNoImage() {
        assertNull(ImagePixelCodec.toMtmdPng(byteArrayOf()))
    }

    @Test
    fun smallPngIsPassedThroughUntouched() {
        val original = pngBytes(64, 48)
        assertArrayEquals(original, ImagePixelCodec.toMtmdPng(original))
    }

    @Test
    fun oversizedImageIsScaledWithinTheEdgeCap() {
        val normalized = ImagePixelCodec.toMtmdPng(pngBytes(1024, 512))
        assertNotNull(normalized)
        assertTrue(ImagePixelCodec.isPng(normalized!!))
        val (width, height) = dimensionsOf(normalized)
        assertTrue("longest edge $width x $height", max(width, height) <= ImagePixelCodec.MAX_EDGE)
    }

    @Test
    fun heavyVlmCapProducesSmallerImageThanDefaultCap() {
        val source = pngBytes(1024, 1024)
        val heavy = ImagePixelCodec.toMtmdPng(source, ImagePixelCodec.MAX_EDGE_HEAVY_VLM)!!
        val (width, height) = dimensionsOf(heavy)
        assertTrue(max(width, height) <= ImagePixelCodec.MAX_EDGE_HEAVY_VLM)
        assertTrue(ImagePixelCodec.MAX_EDGE_HEAVY_VLM < ImagePixelCodec.MAX_EDGE)
    }

    @Test
    fun tinyEdgeRequestIsClampedToUsableMinimum() {
        val normalized = ImagePixelCodec.toMtmdPng(pngBytes(512, 512), maxEdge = 1)!!
        val (width, height) = dimensionsOf(normalized)
        assertTrue(max(width, height) <= 64)
        assertTrue(width > 0 && height > 0)
    }
}
