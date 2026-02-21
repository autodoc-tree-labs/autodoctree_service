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
import com.autodoctree.api.domain.EmbeddingAggregationService
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
    private val embeddingQualityScorer: EmbeddingQualityScorer,
    private val embeddingAggregationService: EmbeddingAggregationService,
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
                    onlyStage = stage,
                    cascadeFromStage = true
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
        onlyStage: Stage?,
        cascadeFromStage: Boolean = false
    ) {
        val document = documentRepository.findByWorkspaceAndId(workspaceId, documentId) ?: return

        fun shouldRun(stage: Stage): Boolean {
            if (onlyStage == null) {
                return true
            }
            if (!cascadeFromStage) {
                return stage == onlyStage
            }
            return stage.ordinal >= onlyStage.ordinal
        }

        val runIngest = shouldRun(Stage.INGEST)
        val runEmbed = shouldRun(Stage.EMBED)
        val runIndex = shouldRun(Stage.INDEX)
        val runTree = shouldRun(Stage.TREE)
        val allowReopenDone = onlyStage != null

        if (runIngest) {
            val inputHash = sha256((document.updatedAt.toString() + (attachmentObjectKey ?: "") + Stage.INGEST.name))
            val ingestStatus = executeStage(
                workspaceId = workspaceId,
                documentId = documentId,
                stage = Stage.INGEST,
                inputHash = inputHash,
                modelVersion = "tika-v1",
                allowReopenDone = allowReopenDone
            ) {
                val text: String
                val qualityFlags: Set<String>
                if (!attachmentObjectKey.isNullOrBlank()) {
                    val extracted = extractTextFromObject(workspaceId, attachmentObjectKey, attachmentContentType)
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
            if (ingestStatus != StageStatus.DONE) {
                return
            }
        }

        if (runEmbed) {
            val latestDocument = documentRepository.findByWorkspaceAndId(workspaceId, documentId) ?: document
            val sections = documentSectionRepository.listByWorkspaceAndDocument(workspaceId, documentId)
            val modelVersion = embeddingProvider.modelVersion()
            val qualityScore = embeddingQualityScorer.score(
                bodyText = latestDocument.bodyText ?: latestDocument.bodyMarkdown ?: "",
                sections = sections
            )
            val payloads = embeddingInputPreprocessor
                .buildPayloads(latestDocument, sections)
                .filter { payload ->
                    when (payload.targetType.uppercase()) {
                        "BODY_SUMMARY" -> qualityScore.qBody >= 0.20
                        "SECTION" -> qualityScore.qLayout >= 0.15
                        else -> true
                    }
                }
            val payloadHashes = payloads.associate { payload ->
                payload to sha256(payload.text + "|" + modelVersion)
            }
            val stageInputHash = sha256(
                payloadHashes.values.sorted().joinToString("|") + "|" + modelVersion + "|" + Stage.EMBED.name
            )
            val embedStatus = executeStage(
                workspaceId = workspaceId,
                documentId = documentId,
                stage = Stage.EMBED,
                inputHash = stageInputHash,
                modelVersion = modelVersion,
                allowReopenDone = allowReopenDone
            ) {
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

                val latestEmbeddings = embeddingRepository
                    .listByWorkspaceAndModel(workspaceId, modelVersion)
                    .filter { row ->
                        row.documentId == documentId && row.targetType.equals("SECTION", ignoreCase = true)
                    }
                if (latestEmbeddings.isNotEmpty()) {
                    val vectors = latestEmbeddings.mapNotNull { row ->
                        runCatching {
                            objectMapper.readValue(row.vectorJson, List::class.java)
                                .mapNotNull { value -> (value as? Number)?.toDouble() }
                        }.getOrNull()
                    }
                    val centroid = embeddingAggregationService.centroidForSections(vectors)
                    if (centroid.isNotEmpty()) {
                        val centroidHash = sha256(
                            latestEmbeddings
                                .sortedBy { it.targetId }
                                .joinToString("|") { "${it.targetId}:${it.inputHash}" } + "|SECTION_CENTROID"
                        )
                        embeddingRepository.upsert(
                            workspaceId = workspaceId,
                            documentId = documentId,
                            targetType = "SECTION_CENTROID",
                            targetId = documentId,
                            inputHash = centroidHash,
                            vectorJson = objectMapper.writeValueAsString(centroid),
                            modelVersion = modelVersion
                        )
                    }
                }

                logger.info(
                    "embedding_quality_summary workspace_id={} document_id={} q_body={} q_layout={} q_ocr={} payload_count={}",
                    workspaceId,
                    documentId,
                    String.format("%.3f", qualityScore.qBody),
                    String.format("%.3f", qualityScore.qLayout),
                    String.format("%.3f", qualityScore.qOcr),
                    payloads.size
                )
            }
            if (embedStatus != StageStatus.DONE) {
                return
            }
        }

        if (runIndex) {
            val inputHash = sha256(document.updatedAt.toString() + Stage.INDEX.name)
            val indexStatus = executeStage(
                workspaceId = workspaceId,
                documentId = documentId,
                stage = Stage.INDEX,
                inputHash = inputHash,
                modelVersion = "bm25-v1",
                allowReopenDone = allowReopenDone
            ) {
                tenantSearchClient.upsert(workspaceId, documentId)
            }
            if (indexStatus != StageStatus.DONE) {
                return
            }
        }

        if (runTree) {
            val inputHash = sha256(document.updatedAt.toString() + Stage.TREE.name)
            val treeStatus = executeStage(
                workspaceId = workspaceId,
                documentId = documentId,
                stage = Stage.TREE,
                inputHash = inputHash,
                modelVersion = "tree-v1",
                allowReopenDone = allowReopenDone
            ) {
                if (onlyStage == null) {
                    rebuildDebounceQueue.request(workspaceId, "PIPELINE_STAGE_TREE")
                } else {
                    treeService.rebuildWorkspace(workspaceId)
                }
            }
            if (treeStatus != StageStatus.DONE) {
                return
            }
        }

        if (onlyStage == null || (cascadeFromStage && runTree)) {
            documentRepository.updateStatus(workspaceId, documentId, "READY")
        }
    }

    private fun executeStage(
        workspaceId: String,
        documentId: String,
        stage: Stage,
        inputHash: String,
        modelVersion: String,
        allowReopenDone: Boolean = false,
        block: () -> Unit
    ): StageStatus {
        val started = stageExecutionRepository.tryStart(
            workspaceId = workspaceId,
            documentId = documentId,
            stage = stage,
            inputHash = inputHash,
            modelVersion = modelVersion,
            allowReopenDone = allowReopenDone
        )
        if (!started) {
            val existing = stageExecutionRepository.findByKey(
                workspaceId = workspaceId,
                documentId = documentId,
                stage = stage,
                inputHash = inputHash,
                modelVersion = modelVersion
            )
            val currentStatus = existing?.status ?: StageStatus.RUNNING
            when (currentStatus) {
                StageStatus.DONE -> pipelineStatusRepository.updateStage(workspaceId, documentId, stage, StageStatus.DONE, null)
                StageStatus.RUNNING -> pipelineStatusRepository.updateStage(workspaceId, documentId, stage, StageStatus.RUNNING, null)
                StageStatus.FAILED -> pipelineStatusRepository.updateStage(
                    workspaceId,
                    documentId,
                    stage,
                    StageStatus.FAILED,
                    existing?.message ?: "failed"
                )
                StageStatus.PENDING -> pipelineStatusRepository.updateStage(workspaceId, documentId, stage, StageStatus.PENDING, null)
            }
            logger.info(
                "stage_execution_reused workspace_id={} document_id={} stage={} status={}",
                workspaceId,
                documentId,
                stage.name,
                currentStatus.name
            )
            return currentStatus
        }

        val timerSample = Timer.start(meterRegistry)
        pipelineStatusRepository.updateStage(workspaceId, documentId, stage, StageStatus.RUNNING)
        try {
            block()
            pipelineStatusRepository.updateStage(workspaceId, documentId, stage, StageStatus.DONE)
            stageExecutionRepository.markDone(workspaceId, documentId, stage, inputHash, modelVersion)
            stageSuccessCounters[stage]?.increment()
            return StageStatus.DONE
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

    private fun extractTextFromObject(workspaceId: String, objectKey: String, contentType: String?): ExtractionResult {
        val bytes = s3StorageService.readObjectBytes(workspaceId, objectKey)
        return textExtractor.extract(bytes, contentType)
    }
}
