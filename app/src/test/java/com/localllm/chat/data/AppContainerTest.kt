package com.localllm.chat.data

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppContainerTest {
    @Test
    fun createsRepositoriesAndEngine() {
        val context = RuntimeEnvironment.getApplication()
        val container = AppContainer(context)
        assertNotNull(container.settingsRepository)
        assertNotNull(container.onboardingRepository)
        assertNotNull(container.modelRepository)
        assertNotNull(container.memoryRepository)
        assertNotNull(container.chatRepository)
        assertNotNull(container.llmRuntime)
        assertNotNull(container.chatEngine)
    }
}
