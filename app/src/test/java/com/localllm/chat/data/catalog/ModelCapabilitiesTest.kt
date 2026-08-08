package com.localllm.chat.data.catalog

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelCapabilitiesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun capabilitiesAreLoadedFromAssets() {
        val entries = ModelCapabilities.load(context)
        assertTrue(entries.isNotEmpty())
        // Second call must hit the cache and return the very same map.
        assertTrue(entries === ModelCapabilities.load(context))
    }

    @Test
    fun everyCapabilityKeyIsAKnownCatalogModel() {
        val catalogIds = ModelCatalog.all(context).map { it.id }.toSet()
        val unknown = ModelCapabilities.load(context).keys - catalogIds
        assertEquals(emptySet<String>(), unknown)
    }

    @Test
    fun blankCatalogIdHasNoTools() {
        assertEquals(emptyList<String>(), ModelCapabilities.nativeToolsFor(context, null))
        assertEquals(emptyList<String>(), ModelCapabilities.nativeToolsFor(context, "   "))
        assertFalse(ModelCapabilities.hasNativeTools(context, null))
    }

    @Test
    fun unknownCatalogIdHasNoTools() {
        assertEquals(emptyList<String>(), ModelCapabilities.nativeToolsFor(context, "not-a-model"))
        assertFalse(ModelCapabilities.hasNativeTools(context, "not-a-model"))
    }

    @Test
    fun modelsDeclaringNativeToolsReportThem() {
        val entries = ModelCapabilities.load(context)
        val withTools = entries.entries.firstOrNull { it.value.nativeTools.isNotEmpty() } ?: return
        assertEquals(withTools.value.nativeTools, ModelCapabilities.nativeToolsFor(context, withTools.key))
        assertTrue(ModelCapabilities.hasNativeTools(context, withTools.key))
    }
}
