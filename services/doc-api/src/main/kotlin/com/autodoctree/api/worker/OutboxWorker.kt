package com.autodoctree.api.worker

import com.autodoctree.api.config.WorkerProperties
import com.autodoctree.api.db.DlqRepository
import com.autodoctree.api.db.DocumentRepository
import com.autodoctree.api.db.DocumentSectionRepository
import com.autodoctree.api.db.EmbeddingRepository
import com.autodoctree.api.db.OutboxEventRow
import com.autodoctree.api.db.OutboxRepository
import com.autodoctree.api.db.PipelineStatusRepository
import com.autodoctree.api.db.StageExecutionRepository
import com.autodoctree.api.domain.RebuildDebounceQueue
import com.autodoctree.api.domain.TreeService
import com.autodoctree.api.infra.sha256
import com.autodoctree.api.search.TenantSearchClient
import com.autodoctree.api.storage.S3StorageService
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import kotlin.math.pow

@Component
class OutboxWorker(
    private val outboxRepository: OutboxRepository,
    private val dlqRepository: DlqRepository,
    private val documentRepository: DocumentRepository,
    private val pipelineStatusRepository: PipelineStatusRepository,
    private val stageExecutionRepository: StageExecutionRepository,
    private val documentSectionRepository: DocumentSectionRepository,
    private val embeddingRepository: EmbeddingRepository,
    private val embeddingProvider: LocalStubEmbeddingProvider,
    private val tenantSearchClient: TenantSearchClient,
    private val treeService: TreeService,
    private val rebuildDebounceQueue: RebuildDebounceQueue,
    private val s3StorageService: S3StorageService,
    private val objectMapper: ObjectMapper,
    private val workerProperties: WorkerProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val chunker = SectionChunker()
    private val tika = Tika()

    @Scheduled(fixedDelayString = "\${worker.poll-interval-ms:2000}")
    fun poll() {
        flushDebouncedRebuilds()
        val events = outboxRepository.fetchBatch(20)
        events.forEach { event ->
            processEventSafely(event)
        }
    }

    private fun flushDebouncedRebuilds() {
        val dueRequests = rebuildDebounceQueue.dequeueDue()
        dueRequests.forEach { pending ->
            runCatching {
                treeService.rebuildWorkspace(pending.workspaceId)
            }.onFailure { ex ->
                logger.warn(
                    "debounced_rebuild_failed workspace_id={} triggers={} reason_count={} message={}",
                    pending.workspaceId,
                    pending.triggerCount,
                    pending.reasons.size,
                    ex.message
                )
            }
        }
    }

    private fun processEventSafely(event: OutboxEventRow) {
        outboxRepository.markProcessing(event.id)
        try {
            processEvent(event)
            outboxRepository.markDone(event.id)
        } catch (ex: Exception) {
            val retries = event.retryCount + 1
            if (retries > workerProperties.maxRetries) {
                outboxRepository.markDlq(event.id)
                dlqRepository.insert(
                    outboxEventId = event.id,
                    workspaceId = event.workspaceId,
                    reason = ex.message ?: "unknown error",
                    payloadJson = event.payloadJson
                )
                logger.warn(
                    "worker_event_dlq workspace_id={} event_id={} type={} retries={}",
                    event.workspaceId,
                    event.id,
                    event.eventType,
                    retries
                )
            } else {
                val seconds = (2.0.pow(retries.toDouble()) * 2).toLong()
                outboxRepository.markRetry(event.id, retries, LocalDateTime.now().plusSeconds(seconds))
                logger.warn(
                    "worker_event_retry workspace_id={} event_id={} type={} retry_count={}",
                    event.workspaceId,
                    event.id,
                    event.eventType,
                    retries
                )
            }
        }
    }

    private fun processEvent(event: OutboxEventRow) {
        val payload = objectMapper.readValue(event.payloadJson, Map::class.java) as Map<String, Any?>
        when (event.eventType) {
            "DocumentSaved", "DocumentUpdated" -> {
                val documentId = event.documentId ?: return
                runPipeline(event.workspaceId, documentId, attachmentObjectKey = null, onlyStage = null)
            }

            "AttachmentUploaded" -> {
                val documentId = event.documentId ?: payload["document_id"]?.toString() ?: return
                val objectKey = payload["object_key"]?.toString()
                runPipeline(event.workspaceId, documentId, attachmentObjectKey = objectKey, onlyStage = null)
            }

            "DocumentDeleted" -> {
                val documentId = event.documentId ?: payload["document_id"]?.toString() ?: return
                tenantSearchClient.delete(event.workspaceId, documentId)
            }

            "FeedbackRecorded" -> {
                rebuildDebounceQueue.request(event.workspaceId, "FEEDBACK_RECORDED")
            }

            "StageRetry" -> {
                val documentId = payload["document_id"]?.toString() ?: return
                val stage = payload["stage"]?.toString()?.let { parseStage(it) }
                runPipeline(event.workspaceId, documentId, attachmentObjectKey = null, onlyStage = stage)
            }
        }
    }

    private fun parseStage(value: String): Stage? {
        return try {
            Stage.valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun runPipeline(workspaceId: String, documentId: String, attachmentObjectKey: String?, onlyStage: Stage?) {
        val document = documentRepository.findByWorkspaceAndId(workspaceId, documentId) ?: return

        val runIngest = onlyStage == null || onlyStage == Stage.INGEST
        val runEmbed = onlyStage == null || onlyStage == Stage.EMBED
        val runIndex = onlyStage == null || onlyStage == Stage.INDEX
        val runTree = onlyStage == null || onlyStage == Stage.TREE

        if (runIngest) {
            val inputHash = sha256((document.updatedAt.toString() + (attachmentObjectKey ?: "") + Stage.INGEST.name))
            executeStage(workspaceId, documentId, Stage.INGEST, inputHash, "tika-v1") {
                val text = if (!attachmentObjectKey.isNullOrBlank()) {
                    extractTextFromObject(attachmentObjectKey)
                } else {
                    document.bodyMarkdown ?: ""
                }
                val sections = chunker.split(workspaceId, documentId, text)
                documentSectionRepository.replaceSections(workspaceId, documentId, sections)
                documentRepository.updateBodyText(workspaceId, documentId, text, "PROCESSING")
            }
        }

        if (runEmbed) {
            val sections = documentSectionRepository.listByWorkspaceAndDocument(workspaceId, documentId)
            val textForEmbedding = if (sections.isNotEmpty()) {
                sections.joinToString("\n") { it.chunkText }
            } else {
                (documentRepository.findByWorkspaceAndId(workspaceId, documentId)?.bodyText ?: "")
            }
            val inputHash = sha256(textForEmbedding + Stage.EMBED.name)
            executeStage(workspaceId, documentId, Stage.EMBED, inputHash, embeddingProvider.modelVersion()) {
                val embedding = embeddingProvider.embed(listOf(textForEmbedding)).first()
                embeddingRepository.upsert(
                    workspaceId = workspaceId,
                    documentId = documentId,
                    targetType = "DOCUMENT",
                    targetId = documentId,
                    vectorJson = objectMapper.writeValueAsString(embedding),
                    modelVersion = embeddingProvider.modelVersion()
                )
            }
        }

        if (runIndex) {
            val inputHash = sha256(document.updatedAt.toString() + Stage.INDEX.name)
            executeStage(workspaceId, documentId, Stage.INDEX, inputHash, "bm25-v1") {
                tenantSearchClient.upsert(workspaceId, documentId)
            }
        }

        if (runTree) {
            val inputHash = sha256(document.updatedAt.toString() + Stage.TREE.name)
            executeStage(workspaceId, documentId, Stage.TREE, inputHash, "tree-v1") {
                if (onlyStage == null) {
                    rebuildDebounceQueue.request(workspaceId, "PIPELINE_STAGE_TREE")
                } else {
                    treeService.rebuildWorkspace(workspaceId)
                }
            }
        }

        if (onlyStage == null) {
            documentRepository.updateStatus(workspaceId, documentId, "READY")
        }
    }

    private fun executeStage(
        workspaceId: String,
        documentId: String,
        stage: Stage,
        inputHash: String,
        modelVersion: String,
        block: () -> Unit
    ) {
        val started = stageExecutionRepository.tryStart(workspaceId, documentId, stage, inputHash, modelVersion)
        if (!started) {
            return
        }

        pipelineStatusRepository.updateStage(workspaceId, documentId, stage, StageStatus.RUNNING)
        try {
            block()
            pipelineStatusRepository.updateStage(workspaceId, documentId, stage, StageStatus.DONE)
            stageExecutionRepository.markDone(workspaceId, documentId, stage, inputHash, modelVersion)
        } catch (ex: Exception) {
            pipelineStatusRepository.updateStage(
                workspaceId,
                documentId,
                stage,
                StageStatus.FAILED,
                ex.message?.take(250) ?: "failed"
            )
            stageExecutionRepository.markFailed(
                workspaceId,
                documentId,
                stage,
                inputHash,
                modelVersion,
                ex.message ?: "failed"
            )
            throw ex
        }
    }

    private fun extractTextFromObject(objectKey: String): String {
        val bytes = s3StorageService.readObjectBytes(objectKey)
        if (bytes.isEmpty()) {
            return ""
        }
        return runCatching {
            tika.parseToString(bytes.inputStream())
        }.getOrElse {
            ""
        }
    }
}
