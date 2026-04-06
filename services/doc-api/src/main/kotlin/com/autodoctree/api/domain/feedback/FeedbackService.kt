package com.autodoctree.api.domain.feedback

import com.autodoctree.api.config.FeatureFlags
import com.autodoctree.api.config.TreeProperties
import com.autodoctree.api.db.document.AttachmentRepository
import com.autodoctree.api.db.admin.AuditLogRepository
import com.autodoctree.api.db.tree.ConceptPrototypeRepository
import com.autodoctree.api.db.ConceptPrototypeRow
import com.autodoctree.api.db.DocumentRow
import com.autodoctree.api.db.document.DocumentRepository
import com.autodoctree.api.db.document.DocumentSectionRepository
import com.autodoctree.api.db.EmbeddingRow
import com.autodoctree.api.db.pipeline.EmbeddingRepository
import com.autodoctree.api.db.FeedbackEventRow
import com.autodoctree.api.db.tree.FeedbackRepository
import com.autodoctree.api.db.pipeline.OutboxRepository
import com.autodoctree.api.db.pipeline.StageExecutionRepository
import com.autodoctree.api.db.TreeMembershipRow
import com.autodoctree.api.db.TreeNodeRow
import com.autodoctree.api.db.tree.WorkspaceTreePolicyRepository
import com.autodoctree.api.db.tree.TreeRepository
import com.autodoctree.api.db.TreeSnapshotRow
import com.autodoctree.api.db.tree.UserRuleRepository
import com.autodoctree.api.infra.BadRequestException
import com.autodoctree.api.infra.ForbiddenException
import com.autodoctree.api.infra.NotFoundException
import com.autodoctree.api.infra.requireEditor
import com.autodoctree.api.infra.requireOwner
import com.autodoctree.api.llm.LlmTextGenerator
import com.autodoctree.api.structure.StructureWorkerClient
import com.autodoctree.api.tenant.WorkspaceContext
import com.autodoctree.api.worker.EmbeddingProvider
import com.autodoctree.api.worker.EmbeddingQualityScorer
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Statistic
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.sqrt
import com.autodoctree.api.domain.document.AuditService
import com.autodoctree.api.domain.document.OutboxService

@Service
class FeedbackService(
    private val documentRepository: DocumentRepository,
    private val treeRepository: TreeRepository,
    private val feedbackRepository: FeedbackRepository,
    private val outboxService: OutboxService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {
    private val moveCounter = meterRegistry.counter("feedback_move_total")

    private val moveSourceAllowList = setOf("DRAG", "MANUAL", "QUICK_CONFIRM", "QUESTION", "UNKNOWN")

    @Transactional
    fun move(context: WorkspaceContext, documentId: String, fromNodeId: String?, toNodeId: String, source: String?) {
        requireEditor(context)
        documentRepository.findByWorkspaceAndId(context.workspaceId, documentId) ?: throw NotFoundException()
        val toNode = treeRepository.findNodeByWorkspace(context.workspaceId, toNodeId) ?: throw NotFoundException()
        val currentMembership = treeRepository.findMembershipByWorkspaceAndDocument(context.workspaceId, documentId)
        if (currentMembership != null) {
            val currentNode = treeRepository.findNodeByWorkspace(context.workspaceId, currentMembership.nodeId)
            if (currentNode?.locked == true && currentMembership.nodeId != toNode.id) {
                throw BadRequestException("locked node membership is protected")
            }
        }
        treeRepository.moveDocumentInActiveSnapshot(context.workspaceId, documentId, toNode.id)
        val sourceLabel = source
            ?.trim()
            ?.uppercase()
            ?.takeIf { it in moveSourceAllowList }
            ?: "UNKNOWN"

        val payload = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "document_id" to documentId,
            "from_node_id" to fromNodeId,
            "to_node_id" to toNode.id,
            "source" to sourceLabel
        )
        val payloadJson = objectMapper.writeValueAsString(payload)
        feedbackRepository.insert(context.workspaceId, context.userId, "MOVE", payloadJson)
        moveCounter.increment()
        meterRegistry.counter("feedback_move_source_total", "source", sourceLabel).increment()
        outboxService.enqueue(context.workspaceId, documentId, "FeedbackRecorded", payload)
        auditService.write(context.workspaceId, context.userId, "feedback.move", payload)
    }

    @Transactional
    fun rename(context: WorkspaceContext, nodeId: String, oldLabel: String?, newLabel: String) {
        requireEditor(context)
        if (newLabel.isBlank()) {
            throw BadRequestException("new_label must not be blank")
        }
        val node = treeRepository.findNodeByWorkspace(context.workspaceId, nodeId) ?: throw NotFoundException()
        if (node.locked) {
            throw BadRequestException("locked node label cannot be renamed")
        }
        treeRepository.renameNode(context.workspaceId, node.id, newLabel.take(80))

        val payload = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "node_id" to nodeId,
            "old_label" to (oldLabel ?: node.label),
            "new_label" to newLabel.take(80)
        )
        feedbackRepository.insert(context.workspaceId, context.userId, "RENAME", objectMapper.writeValueAsString(payload))
        outboxService.enqueue(context.workspaceId, null, "FeedbackRecorded", payload)
        auditService.write(context.workspaceId, context.userId, "feedback.rename", payload)
    }
}
