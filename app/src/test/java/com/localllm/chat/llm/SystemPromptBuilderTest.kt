package com.localllm.chat.llm

import com.localllm.chat.domain.ChatMode
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptBuilderTest {
    @Test
    fun chatPromptContainsIdentity() {
        val prompt = SystemPromptBuilder.forModel("llama-3.2-1b", ChatMode.CHAT)
        assertTrue(prompt.contains("Llama 3.2"))
        assertTrue(prompt.contains("Airux Pocket AI"))
        assertTrue(prompt.contains("running offline"))
    }

    @Test
    fun codingPromptContainsIdentity() {
        val prompt = SystemPromptBuilder.forModel("Qwen3", ChatMode.CODING)
        assertTrue(prompt.contains("Qwen3"))
        assertTrue(prompt.contains("Airux Pocket AI"))
        assertTrue(prompt.contains("programming assistant"))
    }

    @Test
    fun detectsQwen3() {
        val prompt = SystemPromptBuilder.forModel("my-qwen3-1.7b", ChatMode.CHAT)
        assertTrue(prompt.contains("Qwen3"))
        assertTrue(prompt.contains("Alibaba"))
    }

    @Test
    fun detectsGemma3() {
        val prompt = SystemPromptBuilder.forModel("gemma-3-4b", ChatMode.CODING)
        assertTrue(prompt.contains("Gemma 3"))
        assertTrue(prompt.contains("Google DeepMind"))
    }

    @Test
    fun fallbackModel() {
        val prompt = SystemPromptBuilder.forModel("unknown-model", ChatMode.CHAT)
        assertTrue(prompt.contains("unknown-model"))
        assertTrue(prompt.contains("a local open-source language model"))
    }

    @Test
    fun detectsEveryKnownModelFamily() {
        val expected = mapOf(
            "qwen_3-4b" to ("Qwen3" to "Alibaba"),
            "qwen2.5-1.5b" to ("Qwen 2.5" to "Alibaba"),
            "qwen2_5-coder" to ("Qwen 2.5" to "Alibaba"),
            "qwen-vl" to ("Qwen" to "Alibaba"),
            "gemma3-4b" to ("Gemma 3" to "Google DeepMind"),
            "llama3.2-3b" to ("Llama 3.2" to "Meta"),
            "llama-3-8b" to ("Llama 3" to "Meta"),
            "llama3-8b" to ("Llama 3" to "Meta"),
            "tinyllama-1.1b" to ("Llama" to "Meta"),
            "gemma-2b" to ("Gemma" to "Google DeepMind"),
            "mistral-7b" to ("Mistral" to "Mistral AI"),
            "deepseek-coder" to ("DeepSeek" to "DeepSeek"),
            "phi-3-mini" to ("Phi" to "Microsoft"),
            "dolphin3-1b" to ("Dolphin" to "its creators"),
        )
        expected.forEach { (fileName, identity) ->
            val (name, creator) = identity
            val prompt = SystemPromptBuilder.forModel(fileName, ChatMode.CHAT)
            assertTrue("$fileName should be identified as $name", prompt.contains("You are $name —"))
            assertTrue("$fileName should credit $creator", prompt.contains(creator))
        }
    }

    @Test
    fun identityIsCaseInsensitive() {
        val prompt = SystemPromptBuilder.forModel("MISTRAL-7B-Instruct", ChatMode.CHAT)
        assertTrue(prompt.contains("You are Mistral —"))
    }

    @Test
    fun chatAndCodingPromptsDifferForTheSameModel() {
        val chat = SystemPromptBuilder.forModel("llama-3.2-1b", ChatMode.CHAT)
        val coding = SystemPromptBuilder.forModel("llama-3.2-1b", ChatMode.CODING)
        assertTrue(chat.contains("general-purpose assistant"))
        assertTrue(coding.contains("expert programming assistant"))
    }
}
