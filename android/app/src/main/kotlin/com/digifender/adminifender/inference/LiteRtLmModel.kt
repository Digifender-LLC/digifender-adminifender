package com.digifender.adminifender.inference

import com.digifender.adminifender.core.data.device.LocalModel
import com.digifender.adminifender.core.data.device.MessageDto
import com.digifender.adminifender.core.data.device.ModelManifest
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM adapter on the CPU backend (tdd.md#android: GPU only after measured acceptance on the actual phone). The
 * engine loads off the UI thread. Sampling is greedy so a repair attempt sees the same behaviour as the first.
 */
class LiteRtLmModel(private val file: File, override val manifest: ModelManifest, private val cacheDir: File) : LocalModel, AutoCloseable {
    override val manifestHash: String = manifest.hash()
    private var engine: Engine? = null

    private suspend fun engine(): Engine = withContext(Dispatchers.Default) {
        engine ?: Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), maxNumTokens = manifest.contextTokens, cacheDir = cacheDir.absolutePath))
            .also { it.initialize() }
            .also { engine = it }
    }

    /**
     * LiteRT-LM 0.17.1 does not expose the artifact's tokenizer, so this is an upper bound: subword tokenizers emit at
     * most one token per UTF-8 byte, plus framing per message. Inputs that might not fit are declined, never cut.
     */
    override suspend fun countTokens(messages: List<MessageDto>): Int = messages.sumOf { it.content.toByteArray().size + MESSAGE_FRAMING } + PROMPT_FRAMING

    override suspend fun generate(messages: List<MessageDto>, maxOutputTokens: Int): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val turns = messages.filter { it.role != "system" }
        require(turns.lastOrNull()?.role == "user") { "last message must be from the user" }
        val history = turns.dropLast(1).map { if (it.role == "assistant") Message.model(it.content) else Message.user(it.content) }
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            initialMessages = history,
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
            maxOutputToken = maxOutputTokens,
        )
        val conversation = engine().createConversation(config)
        return try {
            // Collecting the stream keeps generation cancellable when the lease is lost or the phone overheats.
            conversation.sendMessageAsync(Message.user(turns.last().content)).fold(StringBuilder()) { acc, part ->
                part.contents.contents.filterIsInstance<Content.Text>().forEach { acc.append(it.text) }
                acc
            }.toString()
        } finally {
            conversation.close()
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val MESSAGE_FRAMING = 8
        const val PROMPT_FRAMING = 16
    }
}
