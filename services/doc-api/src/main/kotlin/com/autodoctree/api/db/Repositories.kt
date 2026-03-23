package com.autodoctree.api.db

import java.time.LocalDateTime

data class UserRow(
    val id: String,
    val email: String,
    val passwordHash: String,
    val createdAt: LocalDateTime
)

data class WorkspaceRow(
    val id: String,
    val name: String,
    val createdBy: String,
    val createdAt: LocalDateTime
)

data class MembershipRow(
    val workspaceId: String,
    val userId: String,
    val role: String,
    val createdAt: LocalDateTime,
    val email: String? = null
)

data class DocumentRow(
    val id: String,
    val workspaceId: String,
    val title: String,
    val bodyMarkdown: String?,
    val bodyText: String?,
    val blocksJson: String? = null,
    val sourceType: String,
    val status: String,
    val version: Long,
    val deleted: Boolean,
    val createdBy: String,
    val updatedBy: String = createdBy,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val templateScore: Double? = null,
    val templateBoilerplateRatio: Double? = null,
    val templateNgramRepeatRatio: Double? = null,
    val templateDetectedAt: LocalDateTime? = null,
    val parentDocumentId: String? = null
)

data class DocumentFavoriteRow(
    val workspaceId: String,
    val userId: String,
    val documentId: String,
    val createdAt: LocalDateTime
)

data class DocumentPersonalTopRow(
    val workspaceId: String,
    val userId: String,
    val documentId: String,
    val ord: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class PipelineStatusRow(
    val workspaceId: String,
    val documentId: String,
    val ingestStatus: com.autodoctree.common.StageStatus,
    val embedStatus: com.autodoctree.common.StageStatus,
    val indexStatus: com.autodoctree.common.StageStatus,
    val treeStatus: com.autodoctree.common.StageStatus,
    val failureReason: String?,
    val updatedAt: LocalDateTime
)

data class AttachmentRow(
    val id: String,
    val workspaceId: String,
    val documentId: String,
    val filename: String,
    val contentType: String,
    val size: Long,
    val objectKey: String,
    val checksumSha256: String?,
    val status: String,
    val createdAt: LocalDateTime,
    val completedAt: LocalDateTime?
)

data class SectionRow(
    val id: String,
    val workspaceId: String,
    val documentId: String,
    val ord: Int,
    val heading: String?,
    val chunkText: String,
    val qualityFlags: String?,
    val createdAt: LocalDateTime
)

data class EmbeddingRow(
    val id: String,
    val workspaceId: String,
    val documentId: String,
    val targetType: String,
    val targetId: String,
    val inputHash: String,
    val vectorJson: String,
    val modelVersion: String,
    val createdAt: LocalDateTime
)

data class OutboxEventRow(
    val id: String,
    val workspaceId: String,
    val documentId: String?,
    val eventType: String,
    val payloadJson: String,
    val status: String,
    val retryCount: Int,
    val availableAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class StageExecutionRow(
    val id: String,
    val workspaceId: String,
    val documentId: String,
    val stage: com.autodoctree.common.Stage,
    val inputHash: String,
    val modelVersion: String,
    val status: com.autodoctree.common.StageStatus,
    val message: String?,
    val retries: Int,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class TreeSnapshotRow(
    val id: String,
    val workspaceId: String,
    val viewType: String,
    val status: String,
    val movedRatio: Double,
    val churnCount: Int,
    val nodeRenameCount: Int,
    val labelCacheJson: String,
    val createdAt: LocalDateTime,
    val activatedAt: LocalDateTime?,
    val activatedBy: String?
)

data class TreeNodeRow(
    val id: String,
    val workspaceId: String,
    val snapshotId: String,
    val viewType: String,
    val parentId: String?,
    val label: String,
    val depth: Int,
    val locked: Boolean,
    val createdAt: LocalDateTime
)

data class TreeMembershipRow(
    val id: String,
    val workspaceId: String,
    val snapshotId: String,
    val viewType: String,
    val nodeId: String,
    val documentId: String,
    val rationaleJson: String,
    val createdAt: LocalDateTime
)

data class FeedbackEventRow(
    val id: String,
    val workspaceId: String,
    val userId: String,
    val eventType: String,
    val payloadJson: String,
    val createdAt: LocalDateTime
)

data class UserRuleRow(
    val id: String,
    val workspaceId: String,
    val ruleType: String,
    val ruleValue: String,
    val ruleEffect: String,
    val nodeId: String,
    val enabled: Boolean,
    val createdBy: String,
    val createdAt: LocalDateTime
)

data class WorkspaceTreePolicyRow(
    val workspaceId: String,
    val autoThreshold: Double,
    val recommendThreshold: Double,
    val quarantineEnabled: Boolean,
    val rerankerEnabled: Boolean,
    val updatedBy: String,
    val updatedAt: LocalDateTime
)

data class WorkspaceQuestionControlRow(
    val workspaceId: String,
    val enabled: Boolean,
    val updatedBy: String,
    val updatedAt: LocalDateTime
)

data class ActiveLearningQuestionRow(
    val id: String,
    val workspaceId: String,
    val snapshotId: String?,
    val questionType: String,
    val status: String,
    val documentId: String,
    val payloadJson: String,
    val impactScore: Double,
    val answerValue: String?,
    val answeredBy: String?,
    val answeredAt: LocalDateTime?,
    val expiresAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class ConceptPrototypeRow(
    val id: String,
    val workspaceId: String,
    val snapshotId: String,
    val conceptKey: String,
    val label: String,
    val prototypeVectorJson: String,
    val exemplarDocIdsJson: String,
    val docCount: Int,
    val driftScore: Double,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class AuditLogRow(
    val id: String,
    val workspaceId: String,
    val actorUserId: String,
    val action: String,
    val payloadJson: String,
    val createdAt: LocalDateTime
)

data class PaletteHistoryRow(
    val id: String,
    val workspaceId: String,
    val userId: String,
    val eventType: String,
    val queryText: String?,
    val documentId: String?,
    val commandKey: String?,
    val createdAt: LocalDateTime
)

data class RegistrationVerificationCodeRow(
    val id: String,
    val email: String,
    val passwordHash: String,
    val codeHash: String,
    val expiresAt: LocalDateTime,
    val attemptCount: Int,
    val consumedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class SearchDocumentRow(
    val id: String,
    val title: String,
    val updatedAt: LocalDateTime,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val updatedBy: String,
    val parentDocumentId: String?
)
