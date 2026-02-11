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
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong
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
    private val embeddingProvider: EmbeddingProvider,
    private val embeddingInputPreprocessor: EmbeddingInputPreprocessor,
    private val tenantSearchClient: TenantSearchClient,
    private val treeService: TreeService,
    private val rebuildDebounceQueue: RebuildDebounceQueue,
    private val s3StorageService: S3StorageService,
    private val textExtractor: TikaTextExtractor,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val workerProperties: WorkerProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val chunker = SectionChunker()

    private val eventFailureCounter = meterRegistry.counter("worker.event.failure_total")
    private val eventSuccessCounter = meterRegistry.counter("worker.event.success_total")
    private val outboxLagSeconds = AtomicLong(0)
    private val stageSuccessCounters: Map<Stage, Counter> = Stage.entries.associateWith { stage ->
        meterRegistry.counter("worker.stage.success_total", "stage", stage.name)
    }
    private val stageFailureCounters: Map<Stage, Counter> = Stage.entries.associateWith { stage ->
        meterRegistry.counter("worker.stage.failure_total", "stage", stage.name)
    }
    private val stageDurationTimers: Map<Stage, Timer> = Stage.entries.associateWith { stage ->
        meterRegistry.timer("worker.stage.duration", "stage", stage.name)
    }
    private val embeddingCacheHitCounter = meterRegistry.counter("embedding_cache_hit_total")
    private val embeddingCacheMissCounter = meterRegistry.counter("embedding_cache_miss_total")

    init {
        meterRegistry.gauge("worker.outbox.lag_seconds", outboxLagSeconds)
    }

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
        outboxLagSeconds.set(Duration.between(event.createdAt, LocalDateTime.now()).seconds.coerceAtLeast(0))
        try {
            processEvent(event)
            outboxRepository.markDone(event.id)
            eventSuccessCounter.increment()
        } catch (ex: Exception) {
            eventFailureCounter.increment()
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
        val rawPayload = objectMapper.readValue(event.payloadJson, Map::class.java) as Map<*, *>
        val payload = rawPayload.entries.associate { (key, value) -> key.toString() to value }

        when (event.eventType) {
            "DocumentSaved", "DocumentUpdated" -> {
                val documentId = event.documentId ?: return
                runPipeline(
                    workspaceId = event.workspaceId,
                    documentId = documentId,
                    attachmentObjectKey = null,
                    attachmentContentType = null,
                    onlyStage = null
                )
            }

            "AttachmentUploaded" -> {
                val documentId = event.documentId ?: payload["document_id"]?.toString() ?: return
                val objectKey = payload["object_key"]?.toString()
                val contentType = payload["content_type"]?.toString()
                runPipeline(
                    workspaceId = event.workspaceId,
                    documentId = documentId,
                    attachmentObjectKey = objectKey,
                    attachmentContentType = contentType,
                    onlyStage = null
                )
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
                runPipeline(
                    workspaceId = event.workspaceId,
                    documentId = documentId,
                    attachmentObjectKey = null,
                    attachmentContentType = null,
                    onlyStage = stage
                )
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

    private fun runPipeline(
        workspaceId: String,
        documentId: String,
        attachmentObjectKey: String?,
        attachmentContentType: String?,
        onlyStage: Stage?
    ) {
        val document = documentRepository.findByWorkspaceAndId(workspaceId, documentId) ?: return

        val runIngest = onlyStage == null || onlyStage == Stage.INGEST
        val runEmbed = onlyStage == null || onlyStage == Stage.EMBED
        val runIndex = onlyStage == null || onlyStage == Stage.INDEX
        val runTree = onlyStage == null || onlyStage == Stage.TREE

        if (runIngest) {
            val inputHash = sha256((document.updatedAt.toString() + (attachmentObjectKey ?: "") + Stage.INGEST.name))
            executeStage(workspaceId, documentId, Stage.INGEST, inputHash, "tika-v1") {
                val text: String
                val qualityFlags: Set<String>
                if (!attachmentObjectKey.isNullOrBlank()) {
                    val extracted = extractTextFromObject(attachmentObjectKey, attachmentContentType)
                    if (extracted.failureReason != null) {
                        throw IllegalStateException(extracted.failureReason)
                    }
                    text = extracted.text
                    qualityFlags = extracted.qualityFlags
                } else {
                    text = document.bodyMarkdown ?: ""
                    qualityFlags = emptySet()
                }

                val sections = chunker.split(
                    workspaceId = workspaceId,
                    documentId = documentId,
                    text = text,
                    globalQualityFlags = qualityFlags
                )
                documentSectionRepository.replaceSections(workspaceId, documentId, sections)
                documentRepository.updateBodyText(workspaceId, documentId, text, "PROCESSING")
            }
        }

        if (runEmbed) {
            val latestDocument = documentRepository.findByWorkspaceAndId(workspaceId, documentId) ?: document
            val sections = documentSectionRepository.listByWorkspaceAndDocument(workspaceId, documentId)
            val modelVersion = embeddingProvider.modelVersion()
            val payloads = embeddingInputPreprocessor.buildPayloads(latestDocument, sections)
            val payloadHashes = payloads.associate { payload ->
                payload to sha256(payload.text + "|" + modelVersion)
            }
            val stageInputHash = sha256(
                payloadHashes.values.sorted().joinToString("|") + "|" + modelVersion + "|" + Stage.EMBED.name
            )
            executeStage(workspaceId, documentId, Stage.EMBED, stageInputHash, modelVersion) {
                val misses = mutableListOf<Pair<EmbeddingPayload, String>>()
                payloadHashes.forEach { (payload, inputHash) ->
                    val cached = embeddingRepository.findByInputHash(
                        workspaceId = workspaceId,
                        targetType = payload.targetType,
                        targetId = payload.targetId,
                        modelVersion = modelVersion,
                        inputHash = inputHash
                    )
                    if (cached == null) {
                        misses += payload to inputHash
                        embeddingCacheMissCounter.increment()
                    } else {
                        embeddingCacheHitCounter.increment()
                    }
                }

                misses.chunked(embeddingProvider.batchSize()).forEach { batch ->
                    val vectors = embeddingProvider.embed(batch.map { it.first.text })
                    if (vectors.size != batch.size) {
                        throw IllegalStateException("Embedding provider returned mismatched vector count")
                    }
                    batch.forEachIndexed { index, (payload, inputHash) ->
                        embeddingRepository.upsert(
                            workspaceId = workspaceId,
                            documentId = documentId,
                            targetType = payload.targetType,
                            targetId = payload.targetId,
                            inputHash = inputHash,
                            vectorJson = objectMapper.writeValueAsString(vectors[index]),
                            modelVersion = modelVersion
                        )
                    }
                }
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

        val timerSample = Timer.start(meterRegistry)
        pipelineStatusRepository.updateStage(workspaceId, documentId, stage, StageStatus.RUNNING)
        try {
            block()
            pipelineStatusRepository.updateStage(workspaceId, documentId, stage, StageStatus.DONE)
            stageExecutionRepository.markDone(workspaceId, documentId, stage, inputHash, modelVersion)
            stageSuccessCounters[stage]?.increment()
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
            stageFailureCounters[stage]?.increment()
            throw ex
        } finally {
            timerSample.stop(stageDurationTimers.getValue(stage))
        }
    }

    private fun extractTextFromObject(objectKey: String, contentType: String?): ExtractionResult {
        val bytes = s3StorageService.readObjectBytes(objectKey)
        return textExtractor.extract(bytes, contentType)
    }
}
