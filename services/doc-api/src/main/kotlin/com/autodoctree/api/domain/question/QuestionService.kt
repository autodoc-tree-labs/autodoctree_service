package com.autodoctree.api.domain.question

import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.tree.ActiveLearningQuestionRepository
import com.autodoctree.api.db.ActiveLearningQuestionRow
import com.autodoctree.api.db.document.DocumentRepository
import com.autodoctree.api.db.tree.FeedbackRepository
import com.autodoctree.api.db.TreeMembershipRow
import com.autodoctree.api.db.TreeNodeRow
import com.autodoctree.api.db.tree.TreeRepository
import com.autodoctree.api.db.tree.WorkspaceQuestionControlRepository
import com.autodoctree.api.infra.BadRequestException
import com.autodoctree.api.infra.NotFoundException
import com.autodoctree.api.infra.requireEditor
import com.autodoctree.api.infra.requireOwner
import com.autodoctree.api.tenant.WorkspaceContext
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import kotlin.math.abs
import com.autodoctree.api.domain.document.AuditService
import com.autodoctree.api.domain.feedback.FeedbackService

@Service
class QuestionService(
    private val documentRepository: DocumentRepository,
    private val treeRepository: TreeRepository,
    private val feedbackRepository: FeedbackRepository,
    private val feedbackService: FeedbackService,
    private val activeLearningQuestionRepository: ActiveLearningQuestionRepository,
    private val workspaceQuestionControlRepository: WorkspaceQuestionControlRepository,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val treeProperties: TreeProperties,
    meterRegistry: MeterRegistry
) {
    private val generatedCounter = meterRegistry.counter("question_generated_total")
    private val answeredCounter = meterRegistry.counter("question_answered_total")
    private val expiredCounter = meterRegistry.counter("question_expired_total")
    private val impactScoreSummary = meterRegistry.summary("question_impact_score")
    private val openQuestionsSummary = meterRegistry.summary("questions_open")

    fun listQuestions(context: WorkspaceContext, status: String?, limit: Int): Map<String, Any?> {
        requireEditor(context)
        expireStale(context.workspaceId)
        val normalizedStatus = normalizeStatus(status)
        if (normalizedStatus == null || normalizedStatus == "OPEN") {
            generateIfNeeded(context.workspaceId)
        }
        val items = activeLearningQuestionRepository.listByWorkspace(
            workspaceId = context.workspaceId,
            status = normalizedStatus,
            limit = limit.coerceIn(1, 50)
        )
        val openCount = activeLearningQuestionRepository.countByWorkspaceAndStatus(context.workspaceId, "OPEN")
        openQuestionsSummary.record(openCount.toDouble())
        return mapOf(
            "items" to items.map(::toApiItem),
            "open_count" to openCount
        )
    }

    @Transactional
    fun answerQuestion(context: WorkspaceContext, questionId: String, answer: String): Map<String, Any?> {
        requireEditor(context)
        val row = activeLearningQuestionRepository.findByWorkspaceAndId(context.workspaceId, questionId) ?: throw NotFoundException()
        if (row.status != "OPEN") {
            throw BadRequestException("question is not open")
        }
        val normalizedAnswer = answer.trim().uppercase()
        val payload = parsePayload(row.payloadJson)
        when (row.questionType) {
            "DOC_CLUSTER_CHOICE" -> {
                if (normalizedAnswer !in setOf("A", "B")) {
                    throw BadRequestException("answer must be A or B for DOC_CLUSTER_CHOICE")
                }
                val optionKey = if (normalizedAnswer == "A") "option_a" else "option_b"
                val option = payload[optionKey] as? Map<*, *> ?: throw BadRequestException("question payload is invalid")
                val targetNodeId = option["node_id"]?.toString() ?: throw BadRequestException("question payload is invalid")
                val fromNodeId = treeRepository.findMembershipByWorkspaceAndDocument(context.workspaceId, row.documentId)?.nodeId
                feedbackService.move(
                    context = context,
                    documentId = row.documentId,
                    fromNodeId = fromNodeId,
                    toNodeId = targetNodeId,
                    source = "QUESTION"
                )
            }
            "DOC_PAIR_RELATION" -> {
                if (normalizedAnswer !in setOf("SAME", "DIFF")) {
                    throw BadRequestException("answer must be SAME or DIFF for DOC_PAIR_RELATION")
                }
                val docAId = payload["doc_a_id"]?.toString() ?: row.documentId
                val docBId = payload["doc_b_id"]?.toString() ?: throw BadRequestException("question payload is invalid")
                val eventType = if (normalizedAnswer == "SAME") "CONSTRAINT_MUST_LINK" else "CONSTRAINT_CANNOT_LINK"
                feedbackRepository.insert(
                    context.workspaceId,
                    context.userId,
                    eventType,
                    objectMapper.writeValueAsString(
                        mapOf(
                            "question_id" to row.id,
                            "doc_a_id" to docAId,
                            "doc_b_id" to docBId
                        )
                    )
                )
                auditService.write(
                    context.workspaceId,
                    context.userId,
                    "question.constraint.created",
                    mapOf(
                        "question_id" to row.id,
                        "event_type" to eventType,
                        "doc_a_id" to docAId,
                        "doc_b_id" to docBId
                    )
                )
            }
            else -> throw BadRequestException("unsupported question_type")
        }
        val updated = activeLearningQuestionRepository.markAnswered(
            workspaceId = context.workspaceId,
            questionId = row.id,
            answerValue = normalizedAnswer,
            answeredBy = context.userId
        )
        if (updated == 0) {
            throw BadRequestException("question answer update failed")
        }
        answeredCounter.increment()
        auditService.write(
            context.workspaceId,
            context.userId,
            "question.answered",
            mapOf("question_id" to row.id, "answer" to normalizedAnswer)
        )
        return mapOf(
            "question_id" to row.id,
            "status" to "ANSWERED",
            "answer" to normalizedAnswer
        )
    }

    fun analytics(context: WorkspaceContext): Map<String, Any?> {
        requireOwner(context)
        expireStale(context.workspaceId)
        val open = activeLearningQuestionRepository.countByWorkspaceAndStatus(context.workspaceId, "OPEN")
        val answered = activeLearningQuestionRepository.countByWorkspaceAndStatus(context.workspaceId, "ANSWERED")
        val expired = activeLearningQuestionRepository.countByWorkspaceAndStatus(context.workspaceId, "EXPIRED")
        val answerRate = if (answered + expired == 0L) 0.0 else answered.toDouble() / (answered + expired).toDouble()

        val control = workspaceQuestionControlRepository.findByWorkspace(context.workspaceId)
        val active = treeRepository.findActiveSnapshot(context.workspaceId)
        val unsortedRatio = if (active == null) {
            0.0
        } else {
            val nodes = treeRepository.listNodes(context.workspaceId, active.id).associateBy { it.id }
            val memberships = treeRepository.listMemberships(context.workspaceId, active.id)
            val unsortedCount = memberships.count { membership ->
                val label = nodes[membership.nodeId]?.label.orEmpty()
                isUnsortedLabel(label)
            }
            if (memberships.isEmpty()) 0.0 else unsortedCount.toDouble() / memberships.size.toDouble()
        }

        return mapOf(
            "control" to mapOf(
                "enabled" to (control?.enabled ?: true),
                "updated_by" to control?.updatedBy,
                "updated_at" to control?.updatedAt?.toString()
            ),
            "open_count" to open,
            "answered_count" to answered,
            "expired_count" to expired,
            "answer_rate" to answerRate,
            "avg_impact_open" to activeLearningQuestionRepository.averageImpactByWorkspaceAndStatus(context.workspaceId, "OPEN"),
            "avg_impact_answered" to activeLearningQuestionRepository.averageImpactByWorkspaceAndStatus(context.workspaceId, "ANSWERED"),
            "unsorted_ratio" to unsortedRatio,
            "items" to activeLearningQuestionRepository.listByWorkspace(context.workspaceId, null, 30).map(::toApiItem)
        )
    }

    @Transactional
    fun updateControl(context: WorkspaceContext, enabled: Boolean): Map<String, Any?> {
        requireOwner(context)
        val row = workspaceQuestionControlRepository.upsert(context.workspaceId, enabled, context.userId)
        auditService.write(
            context.workspaceId,
            context.userId,
            "admin.question.control.updated",
            mapOf("enabled" to row.enabled)
        )
        return mapOf(
            "workspace_id" to row.workspaceId,
            "enabled" to row.enabled,
            "updated_by" to row.updatedBy,
            "updated_at" to row.updatedAt.toString()
        )
    }

    @Transactional
    fun expireOpen(context: WorkspaceContext): Map<String, Any?> {
        requireOwner(context)
        val expired = activeLearningQuestionRepository.expireAllOpen(context.workspaceId)
        if (expired > 0) {
            expiredCounter.increment(expired.toDouble())
        }
        auditService.write(
            context.workspaceId,
            context.userId,
            "admin.question.expired",
            mapOf("expired_count" to expired)
        )
        return mapOf("expired_count" to expired)
    }

    @Transactional
    fun generateNow(context: WorkspaceContext): Map<String, Any?> {
        requireOwner(context)
        val generated = generateIfNeeded(context.workspaceId, forceBatch = true)
        return mapOf("generated_count" to generated)
    }

    private fun normalizeStatus(status: String?): String? {
        if (status.isNullOrBlank()) {
            return null
        }
        val normalized = status.trim().uppercase()
        if (normalized !in setOf("OPEN", "ANSWERED", "EXPIRED")) {
            throw BadRequestException("unsupported status")
        }
        return normalized
    }

    private fun expireStale(workspaceId: String) {
        val expired = activeLearningQuestionRepository.expireStale(workspaceId, LocalDateTime.now())
        if (expired > 0) {
            expiredCounter.increment(expired.toDouble())
        }
    }

    @Transactional
    private fun generateIfNeeded(workspaceId: String, forceBatch: Boolean = false): Int {
        val control = workspaceQuestionControlRepository.findByWorkspace(workspaceId)
        if (control != null && !control.enabled) {
            return 0
        }
        val openCount = activeLearningQuestionRepository.countByWorkspaceAndStatus(workspaceId, "OPEN").toInt()
        val maxOpen = treeProperties.questionMaxOpen.coerceAtLeast(1)
        if (!forceBatch && openCount >= maxOpen) {
            return 0
        }
        val budget = if (forceBatch) {
            treeProperties.questionGenerateBatchSize.coerceAtLeast(1)
        } else {
            minOf(
                treeProperties.questionGenerateBatchSize.coerceAtLeast(1),
                (maxOpen - openCount).coerceAtLeast(0)
            )
        }
        if (budget <= 0) {
            return 0
        }
        val active = treeRepository.findActiveSnapshot(workspaceId) ?: return 0
        val documentsById = documentRepository.listWorkspaceDocuments(workspaceId).associateBy { it.id }
        val memberships = treeRepository.listMemberships(workspaceId, active.id)
        val membershipByDocument = memberships.associateBy { it.documentId }
        val nodeById = treeRepository.listNodes(workspaceId, active.id).associateBy { it.id }
        val openQuestions = activeLearningQuestionRepository.listOpenByWorkspace(workspaceId, maxOpen * 2)
        val queuedDocIds = openQuestions.map { it.documentId }.toMutableSet()
        var generated = 0
        val ttlHours = treeProperties.questionTtlHours.coerceAtLeast(1)
        val expiresAt = LocalDateTime.now().plusHours(ttlHours)

        val unsortedMemberships = memberships.filter { membership ->
            val nodeLabel = nodeById[membership.nodeId]?.label.orEmpty()
            isUnsortedLabel(nodeLabel) && !queuedDocIds.contains(membership.documentId)
        }

        unsortedMemberships.forEach { membership ->
            if (generated >= budget) {
                return@forEach
            }
            val document = documentsById[membership.documentId] ?: return@forEach
            val rationale = parsePayload(membership.rationaleJson)
            val clusterCandidates = clusterCandidates(rationale, membershipByDocument, nodeById)
            if (clusterCandidates.size >= 2) {
                val first = clusterCandidates[0]
                val second = clusterCandidates[1]
                val margin = abs(first.score - second.score).coerceIn(0.0, 1.0)
                val impact = (1.0 - margin).coerceIn(0.0, 1.0)
                val payload = mapOf(
                    "document_id" to document.id,
                    "document_title" to document.title,
                    "option_a" to mapOf(
                        "node_id" to first.nodeId,
                        "label" to first.label,
                        "score" to first.score
                    ),
                    "option_b" to mapOf(
                        "node_id" to second.nodeId,
                        "label" to second.label,
                        "score" to second.score
                    )
                )
                activeLearningQuestionRepository.create(
                    workspaceId = workspaceId,
                    snapshotId = active.id,
                    questionType = "DOC_CLUSTER_CHOICE",
                    documentId = document.id,
                    payloadJson = objectMapper.writeValueAsString(payload),
                    impactScore = impact,
                    expiresAt = expiresAt
                )
                generated += 1
                queuedDocIds += document.id
                generatedCounter.increment()
                impactScoreSummary.record(impact)
                return@forEach
            }
        }

        if (generated < budget) {
            val queuedPairs = mutableSetOf<String>()
            unsortedMemberships.forEach { membership ->
                if (generated >= budget) {
                    return@forEach
                }
                if (queuedDocIds.contains(membership.documentId)) {
                    return@forEach
                }
                val document = documentsById[membership.documentId] ?: return@forEach
                val rationale = parsePayload(membership.rationaleJson)
                val neighbors = ((rationale["evidence"] as? Map<*, *>)?.get("neighbors") as? List<*>).orEmpty()
                val pairNeighbor = neighbors.firstNotNullOfOrNull { item ->
                    val map = item as? Map<*, *> ?: return@firstNotNullOfOrNull null
                    val neighborDocId = map["document_id"]?.toString() ?: return@firstNotNullOfOrNull null
                    if (neighborDocId == document.id) {
                        return@firstNotNullOfOrNull null
                    }
                    val target = documentsById[neighborDocId] ?: return@firstNotNullOfOrNull null
                    val pairKey = listOf(document.id, target.id).sorted().joinToString("::")
                    if (pairKey in queuedPairs) {
                        return@firstNotNullOfOrNull null
                    }
                    val channel = map["channel_scores"] as? Map<*, *>
                    val score = ((channel?.get("final") as? Number)?.toDouble()
                        ?: (channel?.get("semantic") as? Number)?.toDouble()
                        ?: 0.0).coerceIn(0.0, 1.0)
                    queuedPairs += pairKey
                    target to score
                } ?: return@forEach
                val payload = mapOf(
                    "doc_a_id" to document.id,
                    "doc_a_title" to document.title,
                    "doc_b_id" to pairNeighbor.first.id,
                    "doc_b_title" to pairNeighbor.first.title
                )
                activeLearningQuestionRepository.create(
                    workspaceId = workspaceId,
                    snapshotId = active.id,
                    questionType = "DOC_PAIR_RELATION",
                    documentId = document.id,
                    payloadJson = objectMapper.writeValueAsString(payload),
                    impactScore = pairNeighbor.second,
                    expiresAt = expiresAt
                )
                generated += 1
                queuedDocIds += document.id
                generatedCounter.increment()
                impactScoreSummary.record(pairNeighbor.second)
            }
        }
        return generated
    }

    private data class ClusterCandidate(val nodeId: String, val label: String, val score: Double)

    private fun clusterCandidates(
        rationale: Map<String, Any?>,
        membershipByDocument: Map<String, TreeMembershipRow>,
        nodeById: Map<String, TreeNodeRow>
    ): List<ClusterCandidate> {
        val scoreByNode = mutableMapOf<String, Double>()
        val labelByNode = mutableMapOf<String, String>()
        fun add(nodeId: String, score: Double, weight: Double) {
            val node = nodeById[nodeId] ?: return
            if (isUnsortedLabel(node.label) || node.label.equals("AutoDoc", ignoreCase = true)) {
                return
            }
            val weighted = (score.coerceIn(0.0, 1.0) * weight).coerceIn(0.0, 1.0)
            if (weighted <= 0.0) {
                return
            }
            scoreByNode[node.id] = (scoreByNode[node.id] ?: 0.0) + weighted
            labelByNode[node.id] = node.label
        }

        val neighbors = ((rationale["evidence"] as? Map<*, *>)?.get("neighbors") as? List<*>).orEmpty()
        neighbors.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            val neighborDocId = map["document_id"]?.toString() ?: return@forEach
            val membership = membershipByDocument[neighborDocId] ?: return@forEach
            val channel = map["channel_scores"] as? Map<*, *>
            val score = (channel?.get("final") as? Number)?.toDouble()
                ?: (channel?.get("semantic") as? Number)?.toDouble()
                ?: (channel?.get("lexical") as? Number)?.toDouble()
                ?: 0.0
            add(membership.nodeId, score, 1.0)
        }

        val similarDocs = (rationale["similar_docs"] as? List<*>).orEmpty()
        similarDocs.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            val neighborDocId = map["document_id"]?.toString() ?: return@forEach
            val membership = membershipByDocument[neighborDocId] ?: return@forEach
            val score = (map["similarity"] as? Number)?.toDouble() ?: 0.0
            add(membership.nodeId, score, 0.85)
        }

        return scoreByNode.entries
            .sortedByDescending { it.value }
            .map { entry ->
                ClusterCandidate(
                    nodeId = entry.key,
                    label = labelByNode[entry.key] ?: entry.key,
                    score = entry.value.coerceIn(0.0, 1.0)
                )
            }
    }

    private fun parsePayload(payloadJson: String): Map<String, Any?> {
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any?>
        }.getOrElse { emptyMap() }
    }

    private fun toApiItem(row: ActiveLearningQuestionRow): Map<String, Any?> {
        return mapOf(
            "id" to row.id,
            "question_type" to row.questionType,
            "status" to row.status,
            "document_id" to row.documentId,
            "impact_score" to row.impactScore,
            "payload" to parsePayload(row.payloadJson),
            "answer_value" to row.answerValue,
            "answered_at" to row.answeredAt?.toString(),
            "expires_at" to row.expiresAt?.toString(),
            "created_at" to row.createdAt.toString()
        )
    }

    private fun isUnsortedLabel(label: String): Boolean {
        return label.equals("general", ignoreCase = true) || label.equals("unsorted", ignoreCase = true)
    }
}
