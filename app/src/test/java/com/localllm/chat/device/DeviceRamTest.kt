package com.localllm.chat.device

import android.app.ActivityManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceRamTest {
    private val gib = 1024L * 1024L * 1024L

    private fun contextWithMemory(
        totalBytes: Long,
        availBytes: Long,
        lowMemory: Boolean = false,
    ): Context {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setMemoryInfo(
            ActivityManager.MemoryInfo().apply {
                totalMem = totalBytes
                availMem = availBytes
                this.lowMemory = lowMemory
            },
        )
        return context
    }

    @Test
    fun tierBoundariesFollowMarketedRamSizes() {
        assertEquals("low", DeviceRam.suggestTier(4 * gib))
        assertEquals("low", DeviceRam.suggestTier(4_831_838_207L))
        assertEquals("mid", DeviceRam.suggestTier(4_831_838_208L))
        assertEquals("mid", DeviceRam.suggestTier(6 * gib))
        assertEquals("high", DeviceRam.suggestTier(7_516_192_768L))
        assertEquals("high", DeviceRam.suggestTier(12 * gib))
    }

    @Test
    fun formatUsesOneDecimal() {
        assertEquals("5.5 GB", DeviceRam.formatGiB(5.45))
        assertEquals("8.0 GB", DeviceRam.formatGiB(8.0))
    }

    @Test
    fun detectDescribesAMidRangePhone() {
        val profile = DeviceRam.detect(contextWithMemory(totalBytes = 6 * gib, availBytes = 2 * gib))
        assertEquals(6 * gib, profile.totalBytes)
        assertEquals(6.0, profile.totalGiB, 0.001)
        assertEquals("mid", profile.suggestedTier)
        assertEquals("6.0 GB", profile.displayLabel)
    }

    @Test
    fun detectDescribesALowEndPhone() {
        val profile = DeviceRam.detect(contextWithMemory(totalBytes = 4 * gib, availBytes = 1 * gib))
        assertEquals("low", profile.suggestedTier)
        assertEquals("4.0 GB", profile.displayLabel)
    }

    @Test
    fun snapshotExposesSystemMemory() {
        val snapshot = DeviceRam.snapshot(contextWithMemory(totalBytes = 8 * gib, availBytes = 3 * gib))
        assertEquals(8 * gib, snapshot.totalBytes)
        assertEquals(3 * gib, snapshot.availBytes)
        assertFalse(snapshot.lowMemory)
    }

    @Test
    fun memoryIsCriticalWhenHeadroomIsMissing() {
        val context = contextWithMemory(totalBytes = 6 * gib, availBytes = 2 * gib)
        assertFalse(DeviceRam.isMemoryCriticalFor(context, 1 * gib))
        assertTrue(DeviceRam.isMemoryCriticalFor(context, 4 * gib))
    }

    @Test
    fun memoryIsCriticalWhenTheSystemReportsLowMemory() {
        val context = contextWithMemory(totalBytes = 8 * gib, availBytes = 6 * gib, lowMemory = true)
        assertTrue(DeviceRam.isMemoryCriticalFor(context, 1))
    }
}
