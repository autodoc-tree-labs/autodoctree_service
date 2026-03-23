package com.autodoctree.api.db

import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import org.mybatis.spring.SqlSessionTemplate
import java.sql.Timestamp
import java.time.LocalDateTime

private const val REPOSITORY_SQL_NAMESPACE = "com.autodoctree.api.db.RepositorySql."

internal typealias RowMap = Map<String, Any?>

internal fun statement(id: String): String = "$REPOSITORY_SQL_NAMESPACE$id"

internal fun SqlSessionTemplate.insertStatement(id: String, params: Any): Int =
    insert(statement(id), params)

internal fun SqlSessionTemplate.updateStatement(id: String, params: Any): Int =
    update(statement(id), params)

internal fun <T : Any> SqlSessionTemplate.selectScalar(id: String, params: Any): T? =
    selectOne(statement(id), params)

internal fun SqlSessionTemplate.selectMap(id: String, params: Any): RowMap? =
    selectOne(statement(id), params)

internal fun <T> SqlSessionTemplate.selectOneMapped(
    id: String,
    params: Any,
    mapper: (RowMap) -> T
): T? = selectOne<RowMap>(statement(id), params)?.let(mapper)

internal fun <T> SqlSessionTemplate.selectListMapped(
    id: String,
    params: Any,
    mapper: (RowMap) -> T
): List<T> = selectList<RowMap>(statement(id), params).map(mapper)

internal fun normalizedQueryPattern(query: String?): String? =
    query?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.let { "%$it%" }

internal fun normalizedSearchSort(sortSql: String): String = when (sortSql.trim().lowercase()) {
    "d.updated_at asc" -> "UPDATED_AT_ASC"
    "d.created_at asc" -> "CREATED_AT_ASC"
    "d.created_at desc" -> "CREATED_AT_DESC"
    else -> "UPDATED_AT_DESC"
}

internal fun Any?.asLocalDateTime(): LocalDateTime? = when (this) {
    null -> null
    is LocalDateTime -> this
    is Timestamp -> toLocalDateTime()
    else -> error("Unsupported datetime value: $this")
}

private fun RowMap.requireString(column: String): String =
    this[column] as? String ?: error("Missing string column: $column")

private fun RowMap.optionalString(column: String): String? = this[column] as? String

private fun RowMap.requireLocalDateTime(column: String): LocalDateTime =
    this[column].asLocalDateTime() ?: error("Missing datetime column: $column")

private fun RowMap.optionalLocalDateTime(column: String): LocalDateTime? =
    this[column].asLocalDateTime()

private fun RowMap.requireLong(column: String): Long =
    (this[column] as? Number)?.toLong() ?: error("Missing long column: $column")

private fun RowMap.requireInt(column: String): Int =
    (this[column] as? Number)?.toInt() ?: error("Missing int column: $column")

private fun RowMap.requireDouble(column: String): Double =
    (this[column] as? Number)?.toDouble() ?: error("Missing double column: $column")

private fun RowMap.optionalDouble(column: String): Double? =
    (this[column] as? Number)?.toDouble()

private fun RowMap.requireBoolean(column: String): Boolean = when (val value = this[column]) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> value.equals("true", ignoreCase = true) || value == "1"
    else -> error("Missing boolean column: $column")
}

internal fun toUserRow(row: RowMap): UserRow = UserRow(
    id = row.requireString("id"),
    email = row.requireString("email"),
    passwordHash = row.requireString("password_hash"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toWorkspaceRow(row: RowMap): WorkspaceRow = WorkspaceRow(
    id = row.requireString("id"),
    name = row.requireString("name"),
    createdBy = row.requireString("created_by"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toMembershipRow(row: RowMap): MembershipRow = MembershipRow(
    workspaceId = row.requireString("workspace_id"),
    userId = row.requireString("user_id"),
    role = row.requireString("role"),
    createdAt = row.requireLocalDateTime("created_at"),
    email = row.optionalString("email")
)

internal fun toDocumentRow(row: RowMap): DocumentRow = DocumentRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    title = row.requireString("title"),
    bodyMarkdown = row.optionalString("body_markdown"),
    bodyText = row.optionalString("body_text"),
    blocksJson = row.optionalString("blocks_json"),
    sourceType = row.requireString("source_type"),
    status = row.requireString("status"),
    version = row.requireLong("version"),
    deleted = row.requireBoolean("deleted"),
    createdBy = row.requireString("created_by"),
    updatedBy = row.requireString("updated_by"),
    createdAt = row.requireLocalDateTime("created_at"),
    updatedAt = row.requireLocalDateTime("updated_at"),
    templateScore = row.optionalDouble("template_score"),
    templateBoilerplateRatio = row.optionalDouble("template_boilerplate_ratio"),
    templateNgramRepeatRatio = row.optionalDouble("template_ngram_repeat_ratio"),
    templateDetectedAt = row.optionalLocalDateTime("template_detected_at"),
    parentDocumentId = row.optionalString("parent_document_id")
)

internal fun toDocumentFavoriteRow(row: RowMap): DocumentFavoriteRow = DocumentFavoriteRow(
    workspaceId = row.requireString("workspace_id"),
    userId = row.requireString("user_id"),
    documentId = row.requireString("document_id"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toDocumentPersonalTopRow(row: RowMap): DocumentPersonalTopRow = DocumentPersonalTopRow(
    workspaceId = row.requireString("workspace_id"),
    userId = row.requireString("user_id"),
    documentId = row.requireString("document_id"),
    ord = row.requireInt("ord"),
    createdAt = row.requireLocalDateTime("created_at"),
    updatedAt = row.requireLocalDateTime("updated_at")
)

internal fun toPipelineStatusRow(row: RowMap): PipelineStatusRow = PipelineStatusRow(
    workspaceId = row.requireString("workspace_id"),
    documentId = row.requireString("document_id"),
    ingestStatus = StageStatus.valueOf(row.requireString("ingest_status")),
    embedStatus = StageStatus.valueOf(row.requireString("embed_status")),
    indexStatus = StageStatus.valueOf(row.requireString("index_status")),
    treeStatus = StageStatus.valueOf(row.requireString("tree_status")),
    failureReason = row.optionalString("failure_reason"),
    updatedAt = row.requireLocalDateTime("updated_at")
)

internal fun toAttachmentRow(row: RowMap): AttachmentRow = AttachmentRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    documentId = row.requireString("document_id"),
    filename = row.requireString("filename"),
    contentType = row.requireString("content_type"),
    size = row.requireLong("size"),
    objectKey = row.requireString("object_key"),
    checksumSha256 = row.optionalString("checksum_sha256"),
    status = row.requireString("status"),
    createdAt = row.requireLocalDateTime("created_at"),
    completedAt = row.optionalLocalDateTime("completed_at")
)

internal fun toSectionRow(row: RowMap): SectionRow = SectionRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    documentId = row.requireString("document_id"),
    ord = row.requireInt("ord"),
    heading = row.optionalString("heading"),
    chunkText = row.requireString("chunk_text"),
    qualityFlags = row.optionalString("quality_flags"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toEmbeddingRow(row: RowMap): EmbeddingRow = EmbeddingRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    documentId = row.requireString("document_id"),
    targetType = row.requireString("target_type"),
    targetId = row.requireString("target_id"),
    inputHash = row.requireString("input_hash"),
    vectorJson = row.requireString("vector_json"),
    modelVersion = row.requireString("model_version"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toOutboxEventRow(row: RowMap): OutboxEventRow = OutboxEventRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    documentId = row.optionalString("document_id"),
    eventType = row.requireString("event_type"),
    payloadJson = row.requireString("payload_json"),
    status = row.requireString("status"),
    retryCount = row.requireInt("retry_count"),
    availableAt = row.requireLocalDateTime("available_at"),
    createdAt = row.requireLocalDateTime("created_at"),
    updatedAt = row.requireLocalDateTime("updated_at")
)

internal fun toStageExecutionRow(row: RowMap): StageExecutionRow = StageExecutionRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    documentId = row.requireString("document_id"),
    stage = Stage.valueOf(row.requireString("stage")),
    inputHash = row.requireString("input_hash"),
    modelVersion = row.requireString("model_version"),
    status = StageStatus.valueOf(row.requireString("status")),
    message = row.optionalString("message"),
    retries = row.requireInt("retries"),
    createdAt = row.requireLocalDateTime("created_at"),
    updatedAt = row.requireLocalDateTime("updated_at")
)

internal fun toTreeSnapshotRow(row: RowMap): TreeSnapshotRow = TreeSnapshotRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    viewType = row.requireString("view_type"),
    status = row.requireString("status"),
    movedRatio = row.requireDouble("moved_ratio"),
    churnCount = row.requireInt("churn_count"),
    nodeRenameCount = row.requireInt("node_rename_count"),
    labelCacheJson = row.requireString("label_cache_json"),
    createdAt = row.requireLocalDateTime("created_at"),
    activatedAt = row.optionalLocalDateTime("activated_at"),
    activatedBy = row.optionalString("activated_by")
)

internal fun toTreeNodeRow(row: RowMap): TreeNodeRow = TreeNodeRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    snapshotId = row.requireString("snapshot_id"),
    viewType = row.requireString("view_type"),
    parentId = row.optionalString("parent_id"),
    label = row.requireString("label"),
    depth = row.requireInt("depth"),
    locked = row.requireBoolean("locked"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toTreeMembershipRow(row: RowMap): TreeMembershipRow = TreeMembershipRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    snapshotId = row.requireString("snapshot_id"),
    viewType = row.requireString("view_type"),
    nodeId = row.requireString("node_id"),
    documentId = row.requireString("document_id"),
    rationaleJson = row.requireString("rationale_json"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toFeedbackEventRow(row: RowMap): FeedbackEventRow = FeedbackEventRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    userId = row.requireString("user_id"),
    eventType = row.requireString("event_type"),
    payloadJson = row.requireString("payload_json"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toUserRuleRow(row: RowMap): UserRuleRow = UserRuleRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    ruleType = row.requireString("rule_type"),
    ruleValue = row.requireString("rule_value"),
    ruleEffect = row.requireString("rule_effect"),
    nodeId = row.requireString("node_id"),
    enabled = row.requireBoolean("enabled"),
    createdBy = row.requireString("created_by"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toWorkspaceTreePolicyRow(row: RowMap): WorkspaceTreePolicyRow = WorkspaceTreePolicyRow(
    workspaceId = row.requireString("workspace_id"),
    autoThreshold = row.requireDouble("auto_threshold"),
    recommendThreshold = row.requireDouble("recommend_threshold"),
    quarantineEnabled = row.requireBoolean("quarantine_enabled"),
    rerankerEnabled = row.requireBoolean("reranker_enabled"),
    updatedBy = row.requireString("updated_by"),
    updatedAt = row.requireLocalDateTime("updated_at")
)

internal fun toWorkspaceQuestionControlRow(row: RowMap): WorkspaceQuestionControlRow = WorkspaceQuestionControlRow(
    workspaceId = row.requireString("workspace_id"),
    enabled = row.requireBoolean("enabled"),
    updatedBy = row.requireString("updated_by"),
    updatedAt = row.requireLocalDateTime("updated_at")
)

internal fun toActiveLearningQuestionRow(row: RowMap): ActiveLearningQuestionRow = ActiveLearningQuestionRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    snapshotId = row.optionalString("snapshot_id"),
    questionType = row.requireString("question_type"),
    status = row.requireString("status"),
    documentId = row.requireString("document_id"),
    payloadJson = row.requireString("payload_json"),
    impactScore = row.requireDouble("impact_score"),
    answerValue = row.optionalString("answer_value"),
    answeredBy = row.optionalString("answered_by"),
    answeredAt = row.optionalLocalDateTime("answered_at"),
    expiresAt = row.optionalLocalDateTime("expires_at"),
    createdAt = row.requireLocalDateTime("created_at"),
    updatedAt = row.requireLocalDateTime("updated_at")
)

internal fun toConceptPrototypeRow(row: RowMap): ConceptPrototypeRow = ConceptPrototypeRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    snapshotId = row.requireString("snapshot_id"),
    conceptKey = row.requireString("concept_key"),
    label = row.requireString("label"),
    prototypeVectorJson = row.requireString("prototype_vector_json"),
    exemplarDocIdsJson = row.requireString("exemplar_doc_ids_json"),
    docCount = row.requireInt("doc_count"),
    driftScore = row.requireDouble("drift_score"),
    createdAt = row.requireLocalDateTime("created_at"),
    updatedAt = row.requireLocalDateTime("updated_at")
)

internal fun toAuditLogRow(row: RowMap): AuditLogRow = AuditLogRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    actorUserId = row.requireString("actor_user_id"),
    action = row.requireString("action"),
    payloadJson = row.requireString("payload_json"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toPaletteHistoryRow(row: RowMap): PaletteHistoryRow = PaletteHistoryRow(
    id = row.requireString("id"),
    workspaceId = row.requireString("workspace_id"),
    userId = row.requireString("user_id"),
    eventType = row.requireString("event_type"),
    queryText = row.optionalString("query_text"),
    documentId = row.optionalString("document_id"),
    commandKey = row.optionalString("command_key"),
    createdAt = row.requireLocalDateTime("created_at")
)

internal fun toRegistrationVerificationCodeRow(row: RowMap): RegistrationVerificationCodeRow =
    RegistrationVerificationCodeRow(
        id = row.requireString("id"),
        email = row.requireString("email"),
        passwordHash = row.requireString("password_hash"),
        codeHash = row.requireString("code_hash"),
        expiresAt = row.requireLocalDateTime("expires_at"),
        attemptCount = row.requireInt("attempt_count"),
        consumedAt = row.optionalLocalDateTime("consumed_at"),
        createdAt = row.requireLocalDateTime("created_at"),
        updatedAt = row.requireLocalDateTime("updated_at")
    )

internal fun toSearchDocumentRow(row: RowMap): SearchDocumentRow = SearchDocumentRow(
    id = row.requireString("id"),
    title = row.requireString("title"),
    updatedAt = row.requireLocalDateTime("updated_at"),
    createdAt = row.requireLocalDateTime("created_at"),
    createdBy = row.requireString("created_by"),
    updatedBy = row.requireString("updated_by"),
    parentDocumentId = row.optionalString("parent_document_id")
)
