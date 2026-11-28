package app.banread.ai

import app.banread.model.AppFailure
import app.banread.model.AppFailureException
import app.banread.model.DiscoveredModel
import app.banread.model.LlmPromptRequest
import app.banread.model.LlmProviderPort
import app.banread.model.LlmStreamEvent
import app.banread.model.ProviderCapabilities
import app.banread.model.ProviderConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList

class ScriptedLlmFakeAdapter(
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsJsonSchema = true,
        supportsVision = true,
        contextWindow = 128_000,
    ),
) : LlmProviderPort {

    data class ScriptedStreamReply(
        val contentText: String = "{}",
        val thinkingText: String = "",
        val failure: AppFailure? = null,
    )

    private val streamQueue = ArrayDeque<ScriptedStreamReply>()
    private var availableModelsResult: Result<List<DiscoveredModel>> = Result.success(
        listOf(
            DiscoveredModel(
                id = "scripted-model-v1",
                displayName = "Scripted Model v1",
                ownedBy = "scripted",
                supportsVision = true,
                supportsThinking = true,
            ),
        ),
    )
    private var customConnectionResult: Result<ProviderConnectionStatus>? = null

    val recordedRequests = CopyOnWriteArrayList<LlmPromptRequest>()
    val recordedEndpoints = CopyOnWriteArrayList<String>()

    fun enqueueResponse(text: String) {
        streamQueue.addLast(ScriptedStreamReply(contentText = text))
    }

    fun enqueueReply(reply: ScriptedStreamReply) {
        streamQueue.addLast(reply)
    }

    fun enqueueFailure(failure: AppFailure) {
        streamQueue.addLast(ScriptedStreamReply(failure = failure))
    }

    fun setAvailableModels(models: List<DiscoveredModel>) {
        availableModelsResult = Result.success(models)
    }

    fun setDiscoveryFailure(failure: AppFailure) {
        availableModelsResult = Result.failure(AppFailureException(failure))
    }

    fun setConnectionStatus(result: Result<ProviderConnectionStatus>?) {
        customConnectionResult = result
    }

    override fun stream(endpoint: String, apiKey: String, payload: LlmPromptRequest): Flow<LlmStreamEvent> = flow {
        recordedEndpoints += endpoint
        recordedRequests += payload
        val next = if (streamQueue.isNotEmpty()) streamQueue.removeFirst() else ScriptedStreamReply()
        if (next.failure != null) {
            emit(LlmStreamEvent.Failed(next.failure))
            return@flow
        }
        if (next.thinkingText.isNotEmpty()) {
            emit(LlmStreamEvent.ThinkingDelta(next.thinkingText))
        }
        val reply = next.contentText
        val mid = reply.length / 2
        if (mid > 0) emit(LlmStreamEvent.ContentDelta(reply.substring(0, mid)))
        emit(LlmStreamEvent.ContentDelta(reply.substring(mid)))
        emit(LlmStreamEvent.Completed(reply))
    }

    override suspend fun listModels(endpoint: String, apiKey: String): Result<List<DiscoveredModel>> {
        recordedEndpoints += endpoint
        return availableModelsResult
    }

    override suspend fun testConnection(
        endpoint: String,
        apiKey: String,
        modelId: String?,
    ): Result<ProviderConnectionStatus> {
        recordedEndpoints += endpoint
        return customConnectionResult ?: super.testConnection(endpoint, apiKey, modelId)
    }
}
