package com.autodoctree.api.db

import com.autodoctree.api.infra.ConflictException
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import org.mybatis.spring.SqlSessionTemplate
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class UserRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(email: String, passwordHash: String): UserRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "userInsert",
            mapOf(
                "id" to id,
                "email" to email,
                "passwordHash" to passwordHash,
                "createdAt" to now
            )
        )
        return UserRow(id, email, passwordHash, now)
    }

    fun findByEmail(email: String): UserRow? =
        sqlSession.selectOneMapped("userFindByEmail", mapOf("email" to email), ::toUserRow)

    fun findById(id: String): UserRow? =
        sqlSession.selectOneMapped("userFindById", mapOf("id" to id), ::toUserRow)
}

@Repository
class RefreshTokenRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(userId: String, tokenHash: String, expiresAt: LocalDateTime): String {
        val id = UUID.randomUUID().toString()
        sqlSession.insertStatement(
            "refreshTokenInsert",
            mapOf(
                "id" to id,
                "userId" to userId,
                "tokenHash" to tokenHash,
                "expiresAt" to expiresAt,
                "createdAt" to LocalDateTime.now()
            )
        )
        return id
    }

    fun findActiveByHash(tokenHash: String): Map<String, Any?>? =
        sqlSession.selectMap(
            "refreshTokenFindActiveByHash",
            mapOf(
                "tokenHash" to tokenHash,
                "now" to LocalDateTime.now()
            )
        )

    fun revokeByHash(tokenHash: String) {
        sqlSession.updateStatement(
            "refreshTokenRevokeByHash",
            mapOf(
                "tokenHash" to tokenHash,
                "revokedAt" to LocalDateTime.now()
            )
        )
    }
}

@Repository
class WorkspaceRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(name: String, createdBy: String): WorkspaceRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "workspaceInsert",
            mapOf(
                "id" to id,
                "name" to name,
                "createdBy" to createdBy,
                "createdAt" to now
            )
        )
        return WorkspaceRow(id, name, createdBy, now)
    }

    fun listByUser(userId: String): List<WorkspaceRow> =
        sqlSession.selectListMapped("workspaceListByUser", mapOf("userId" to userId), ::toWorkspaceRow)

    fun findByWorkspaceAndUser(workspaceId: String, userId: String): WorkspaceRow? =
        sqlSession.selectOneMapped(
            "workspaceFindByWorkspaceAndUser",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId
            ),
            ::toWorkspaceRow
        )
}

@Repository
class MembershipRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(workspaceId: String, userId: String, role: String) {
        sqlSession.insertStatement(
            "membershipInsert",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId,
                "role" to role,
                "createdAt" to LocalDateTime.now()
            )
        )
    }

    fun updateRole(workspaceId: String, userId: String, role: String) {
        sqlSession.updateStatement(
            "membershipUpdateRole",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId,
                "role" to role
            )
        )
    }

    fun delete(workspaceId: String, userId: String) {
        sqlSession.updateStatement(
            "membershipDelete",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId
            )
        )
    }

    fun findRoleByWorkspaceAndUser(workspaceId: String, userId: String): String? =
        sqlSession.selectScalar(
            "membershipFindRoleByWorkspaceAndUser",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId
            )
        )

    fun listMembers(workspaceId: String): List<MembershipRow> =
        sqlSession.selectListMapped("membershipListMembers", mapOf("workspaceId" to workspaceId), ::toMembershipRow)
}

@Repository
class DocumentRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(
        workspaceId: String,
        title: String,
        bodyMarkdown: String?,
        bodyText: String?,
        blocksJson: String? = null,
        sourceType: String,
        createdBy: String,
        parentDocumentId: String? = null
    ): DocumentRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "documentInsert",
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
                "title" to title,
                "bodyMarkdown" to bodyMarkdown,
                "bodyText" to bodyText,
                "blocksJson" to blocksJson,
                "parentDocumentId" to parentDocumentId,
                "sourceType" to sourceType,
                "status" to "PROCESSING",
                "createdBy" to createdBy,
                "updatedBy" to createdBy,
                "createdAt" to now,
                "updatedAt" to now
            )
        )
        return DocumentRow(
            id = id,
            workspaceId = workspaceId,
            title = title,
            bodyMarkdown = bodyMarkdown,
            bodyText = bodyText,
            blocksJson = blocksJson,
            sourceType = sourceType,
            status = "PROCESSING",
            version = 0,
            deleted = false,
            createdBy = createdBy,
            updatedBy = createdBy,
            createdAt = now,
            updatedAt = now,
            parentDocumentId = parentDocumentId
        )
    }

    fun findByWorkspaceAndId(workspaceId: String, documentId: String): DocumentRow? =
        sqlSession.selectOneMapped(
            "documentFindByWorkspaceAndId",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId
            ),
            ::toDocumentRow
        )

    fun findDeletedByWorkspaceAndId(workspaceId: String, documentId: String): DocumentRow? =
        sqlSession.selectOneMapped(
            "documentFindDeletedByWorkspaceAndId",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId
            ),
            ::toDocumentRow
        )

    fun listByWorkspace(
        workspaceId: String,
        status: String?,
        query: String?,
        page: Int,
        size: Int
    ): List<DocumentRow> =
        sqlSession.selectListMapped(
            "documentListByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "status" to status?.takeIf { it.isNotBlank() },
                "queryPattern" to normalizedQueryPattern(query),
                "limit" to size,
                "offset" to page * size
            ),
            ::toDocumentRow
        )

    fun countByWorkspace(workspaceId: String, status: String?, query: String?): Long =
        sqlSession.selectScalar<Long>(
            "documentCountByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "status" to status?.takeIf { it.isNotBlank() },
                "queryPattern" to normalizedQueryPattern(query)
            )
        ) ?: 0L

    fun update(
        workspaceId: String,
        documentId: String,
        expectedVersion: Long,
        title: String,
        bodyMarkdown: String?,
        bodyText: String?,
        blocksJson: String?,
        updatedBy: String
    ) {
        val updated = sqlSession.updateStatement(
            "documentUpdate",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "expectedVersion" to expectedVersion,
                "title" to title,
                "bodyMarkdown" to bodyMarkdown,
                "bodyText" to bodyText,
                "blocksJson" to blocksJson,
                "updatedBy" to updatedBy,
                "updatedAt" to LocalDateTime.now()
            )
        )
        if (updated == 0) {
            throw ConflictException("Document version conflict")
        }
    }

    fun listSubtreeDocumentIds(workspaceId: String, rootDocumentId: String): List<String> {
        val documents = listWorkspaceDocuments(workspaceId)
        if (documents.none { it.id == rootDocumentId }) {
            return emptyList()
        }
        val childrenByParent = mutableMapOf<String, MutableList<String>>()
        documents.forEach { document ->
            val parentId = document.parentDocumentId ?: return@forEach
            childrenByParent.computeIfAbsent(parentId) { mutableListOf() }.add(document.id)
        }
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootDocumentId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) {
                continue
            }
            result += current
            childrenByParent[current].orEmpty().forEach(queue::add)
        }
        return result
    }

    fun softDeleteDocuments(workspaceId: String, documentIds: Collection<String>) {
        if (documentIds.isEmpty()) {
            return
        }
        sqlSession.updateStatement(
            "documentSoftDeleteDocuments",
            mapOf(
                "workspaceId" to workspaceId,
                "documentIds" to documentIds.toList(),
                "status" to "DELETED",
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun updateBodyText(workspaceId: String, documentId: String, bodyText: String, status: String) {
        sqlSession.updateStatement(
            "documentUpdateBodyText",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "bodyText" to bodyText,
                "status" to status,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun updateStatus(workspaceId: String, documentId: String, status: String) {
        sqlSession.updateStatement(
            "documentUpdateStatus",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "status" to status,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun updateTemplateSignals(
        workspaceId: String,
        documentId: String,
        templateScore: Double,
        templateBoilerplateRatio: Double,
        templateNgramRepeatRatio: Double,
        templateDetectedAt: LocalDateTime?
    ) {
        sqlSession.updateStatement(
            "documentUpdateTemplateSignals",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "templateScore" to templateScore,
                "templateBoilerplateRatio" to templateBoilerplateRatio,
                "templateNgramRepeatRatio" to templateNgramRepeatRatio,
                "templateDetectedAt" to templateDetectedAt
            )
        )
    }

    fun searchByWorkspace(workspaceId: String, query: String, size: Int, offset: Int): List<DocumentRow> =
        sqlSession.selectListMapped(
            "documentSearchByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "queryPattern" to normalizedQueryPattern(query),
                "limit" to size,
                "offset" to offset
            ),
            ::toDocumentRow
        )

    fun listWorkspaceDocuments(workspaceId: String): List<DocumentRow> =
        sqlSession.selectListMapped(
            "documentListWorkspaceDocuments",
            mapOf("workspaceId" to workspaceId),
            ::toDocumentRow
        )

    fun listDeletedByWorkspace(
        workspaceId: String,
        query: String?,
        page: Int,
        size: Int
    ): List<DocumentRow> =
        sqlSession.selectListMapped(
            "documentListDeletedByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "queryPattern" to normalizedQueryPattern(query),
                "limit" to size,
                "offset" to page * size
            ),
            ::toDocumentRow
        )

    fun countDeletedByWorkspace(workspaceId: String, query: String?): Long =
        sqlSession.selectScalar<Long>(
            "documentCountDeletedByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "queryPattern" to normalizedQueryPattern(query)
            )
        ) ?: 0L

    fun restore(workspaceId: String, documentId: String, status: String = "PROCESSING") {
        sqlSession.updateStatement(
            "documentRestore",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "status" to status,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun moveParent(workspaceId: String, documentId: String, parentDocumentId: String?) {
        sqlSession.updateStatement(
            "documentMoveParent",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "parentDocumentId" to parentDocumentId,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }
}

@Repository
class DocumentFavoriteRepository(private val sqlSession: SqlSessionTemplate) {
    fun listByWorkspaceAndUser(workspaceId: String, userId: String): List<DocumentFavoriteRow> =
        sqlSession.selectListMapped(
            "documentFavoriteListByWorkspaceAndUser",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId
            ),
            ::toDocumentFavoriteRow
        )

    fun add(workspaceId: String, userId: String, documentId: String) {
        try {
            sqlSession.insertStatement(
                "documentFavoriteInsert",
                mapOf(
                    "workspaceId" to workspaceId,
                    "userId" to userId,
                    "documentId" to documentId,
                    "createdAt" to LocalDateTime.now()
                )
            )
        } catch (_: DuplicateKeyException) {
            // idempotent add
        }
    }

    fun remove(workspaceId: String, userId: String, documentId: String) {
        sqlSession.updateStatement(
            "documentFavoriteRemove",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId,
                "documentId" to documentId
            )
        )
    }

    fun removeByDocument(workspaceId: String, documentId: String) {
        sqlSession.updateStatement(
            "documentFavoriteRemoveByDocument",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId
            )
        )
    }

    fun removeByDocuments(workspaceId: String, documentIds: Collection<String>) {
        if (documentIds.isEmpty()) {
            return
        }
        sqlSession.updateStatement(
            "documentFavoriteRemoveByDocuments",
            mapOf(
                "workspaceId" to workspaceId,
                "documentIds" to documentIds.toList()
            )
        )
    }
}

@Repository
class DocumentPersonalTopRepository(private val sqlSession: SqlSessionTemplate) {
    fun listByWorkspaceAndUser(workspaceId: String, userId: String): List<DocumentPersonalTopRow> =
        sqlSession.selectListMapped(
            "documentPersonalTopListByWorkspaceAndUser",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId
            ),
            ::toDocumentPersonalTopRow
        )

    fun replaceForWorkspaceAndUser(
        workspaceId: String,
        userId: String,
        orderedDocumentIds: List<String>
    ) {
        sqlSession.updateStatement(
            "documentPersonalTopDeleteByWorkspaceAndUser",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId
            )
        )
        if (orderedDocumentIds.isEmpty()) {
            return
        }
        val now = LocalDateTime.now()
        val records = orderedDocumentIds.mapIndexed { index, documentId ->
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId,
                "documentId" to documentId,
                "ord" to index,
                "createdAt" to now,
                "updatedAt" to now
            )
        }
        sqlSession.insertStatement(
            "documentPersonalTopInsertBatch",
            mapOf("records" to records)
        )
    }

    fun removeByDocuments(workspaceId: String, documentIds: Collection<String>) {
        if (documentIds.isEmpty()) {
            return
        }
        sqlSession.updateStatement(
            "documentPersonalTopRemoveByDocuments",
            mapOf(
                "workspaceId" to workspaceId,
                "documentIds" to documentIds.toList()
            )
        )
    }
}

@Repository
class PipelineStatusRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(workspaceId: String, documentId: String) {
        sqlSession.insertStatement(
            "pipelineStatusInsert",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "ingestStatus" to StageStatus.PENDING.name,
                "embedStatus" to StageStatus.PENDING.name,
                "indexStatus" to StageStatus.PENDING.name,
                "treeStatus" to StageStatus.PENDING.name,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun findByWorkspaceAndDocument(workspaceId: String, documentId: String): PipelineStatusRow? =
        sqlSession.selectOneMapped(
            "pipelineStatusFindByWorkspaceAndDocument",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId
            ),
            ::toPipelineStatusRow
        )

    fun updateStage(
        workspaceId: String,
        documentId: String,
        stage: Stage,
        status: StageStatus,
        failureReason: String? = null
    ) {
        sqlSession.updateStatement(
            "pipelineStatusUpdateStage",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "stage" to stage.name,
                "status" to status.name,
                "failureReason" to failureReason,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun markRetryPendingFromStage(workspaceId: String, documentId: String, stage: Stage) {
        sqlSession.updateStatement(
            "pipelineStatusMarkRetryPendingFromStage",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "stage" to stage.name,
                "pendingStatus" to StageStatus.PENDING.name,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }
}

@Repository
class AttachmentRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(
        workspaceId: String,
        documentId: String,
        filename: String,
        contentType: String,
        size: Long,
        objectKey: String,
        checksumSha256: String?
    ): AttachmentRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "attachmentInsert",
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "filename" to filename,
                "contentType" to contentType,
                "size" to size,
                "objectKey" to objectKey,
                "checksumSha256" to checksumSha256,
                "status" to "PRESIGNED",
                "createdAt" to now
            )
        )
        return AttachmentRow(
            id = id,
            workspaceId = workspaceId,
            documentId = documentId,
            filename = filename,
            contentType = contentType,
            size = size,
            objectKey = objectKey,
            checksumSha256 = checksumSha256,
            status = "PRESIGNED",
            createdAt = now,
            completedAt = null
        )
    }

    fun findByWorkspaceAndId(workspaceId: String, attachmentId: String): AttachmentRow? =
        sqlSession.selectOneMapped(
            "attachmentFindByWorkspaceAndId",
            mapOf(
                "workspaceId" to workspaceId,
                "attachmentId" to attachmentId
            ),
            ::toAttachmentRow
        )

    fun updateCompleted(workspaceId: String, attachmentId: String) {
        sqlSession.updateStatement(
            "attachmentUpdateCompleted",
            mapOf(
                "workspaceId" to workspaceId,
                "attachmentId" to attachmentId,
                "status" to "UPLOADED",
                "completedAt" to LocalDateTime.now()
            )
        )
    }

    fun listByWorkspaceAndDocument(workspaceId: String, documentId: String): List<AttachmentRow> =
        sqlSession.selectListMapped(
            "attachmentListByWorkspaceAndDocument",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId
            ),
            ::toAttachmentRow
        )

    fun listByWorkspace(workspaceId: String): List<AttachmentRow> =
        sqlSession.selectListMapped("attachmentListByWorkspace", mapOf("workspaceId" to workspaceId), ::toAttachmentRow)
}

@Repository
class DocumentSectionRepository(private val sqlSession: SqlSessionTemplate) {
    fun replaceSections(workspaceId: String, documentId: String, sections: List<SectionRow>) {
        sqlSession.updateStatement(
            "documentSectionDeleteByWorkspaceAndDocument",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId
            )
        )
        if (sections.isEmpty()) {
            return
        }
        val rows = sections.map { section ->
            mapOf(
                "id" to section.id,
                "workspaceId" to section.workspaceId,
                "documentId" to section.documentId,
                "ord" to section.ord,
                "heading" to section.heading,
                "chunkText" to section.chunkText,
                "qualityFlags" to section.qualityFlags,
                "createdAt" to section.createdAt
            )
        }
        sqlSession.insertStatement("documentSectionInsertBatch", mapOf("sections" to rows))
    }

    fun listByWorkspaceAndDocument(workspaceId: String, documentId: String): List<SectionRow> =
        sqlSession.selectListMapped(
            "documentSectionListByWorkspaceAndDocument",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId
            ),
            ::toSectionRow
        )
}

@Repository
class EmbeddingRepository(private val sqlSession: SqlSessionTemplate) {
    fun upsert(
        workspaceId: String,
        documentId: String,
        targetType: String,
        targetId: String,
        inputHash: String,
        vectorJson: String,
        modelVersion: String
    ) {
        val now = LocalDateTime.now()
        val params = mapOf(
            "id" to UUID.randomUUID().toString(),
            "workspaceId" to workspaceId,
            "documentId" to documentId,
            "targetType" to targetType,
            "targetId" to targetId,
            "inputHash" to inputHash,
            "vectorJson" to vectorJson,
            "modelVersion" to modelVersion,
            "createdAt" to now
        )
        try {
            sqlSession.insertStatement("embeddingInsert", params)
        } catch (_: DuplicateKeyException) {
            sqlSession.updateStatement(
                "embeddingUpdateByInputHash",
                mapOf(
                    "workspaceId" to workspaceId,
                    "targetType" to targetType,
                    "targetId" to targetId,
                    "inputHash" to inputHash,
                    "vectorJson" to vectorJson,
                    "modelVersion" to modelVersion,
                    "createdAt" to now
                )
            )
        }
    }

    fun findByInputHash(
        workspaceId: String,
        targetType: String,
        targetId: String,
        modelVersion: String,
        inputHash: String
    ): EmbeddingRow? =
        sqlSession.selectOneMapped(
            "embeddingFindByInputHash",
            mapOf(
                "workspaceId" to workspaceId,
                "targetType" to targetType,
                "targetId" to targetId,
                "modelVersion" to modelVersion,
                "inputHash" to inputHash
            ),
            ::toEmbeddingRow
        )

    fun findDocEmbedding(workspaceId: String, documentId: String, modelVersion: String): EmbeddingRow? =
        sqlSession.selectOneMapped(
            "embeddingFindDocEmbedding",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "modelVersion" to modelVersion
            ),
            ::toEmbeddingRow
        )

    fun listDocEmbeddings(workspaceId: String, modelVersion: String): List<EmbeddingRow> =
        sqlSession.selectListMapped(
            "embeddingListDocEmbeddings",
            mapOf(
                "workspaceId" to workspaceId,
                "modelVersion" to modelVersion
            ),
            ::toEmbeddingRow
        )

    fun listByWorkspaceAndModel(workspaceId: String, modelVersion: String): List<EmbeddingRow> =
        sqlSession.selectListMapped(
            "embeddingListByWorkspaceAndModel",
            mapOf(
                "workspaceId" to workspaceId,
                "modelVersion" to modelVersion
            ),
            ::toEmbeddingRow
        )

    fun listByWorkspaceAndDocumentAndModel(
        workspaceId: String,
        documentId: String,
        modelVersion: String
    ): List<EmbeddingRow> =
        sqlSession.selectListMapped(
            "embeddingListByWorkspaceAndDocumentAndModel",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "modelVersion" to modelVersion
            ),
            ::toEmbeddingRow
        )
}

@Repository
class OutboxRepository(private val sqlSession: SqlSessionTemplate) {
    fun insert(workspaceId: String, documentId: String?, eventType: String, payloadJson: String): String {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "outboxInsert",
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "eventType" to eventType,
                "payloadJson" to payloadJson,
                "status" to "PENDING",
                "availableAt" to now,
                "createdAt" to now,
                "updatedAt" to now
            )
        )
        return id
    }

    fun fetchBatch(limit: Int): List<OutboxEventRow> =
        sqlSession.selectListMapped(
            "outboxFetchBatch",
            mapOf(
                "now" to LocalDateTime.now(),
                "limit" to limit
            ),
            ::toOutboxEventRow
        )

    fun markProcessing(eventId: String) {
        sqlSession.updateStatement(
            "outboxMarkProcessing",
            mapOf(
                "eventId" to eventId,
                "status" to "PROCESSING",
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun markDone(eventId: String) {
        sqlSession.updateStatement(
            "outboxMarkDone",
            mapOf(
                "eventId" to eventId,
                "status" to "DONE",
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun markRetry(eventId: String, retryCount: Int, nextAvailableAt: LocalDateTime) {
        sqlSession.updateStatement(
            "outboxMarkRetry",
            mapOf(
                "eventId" to eventId,
                "status" to "RETRY",
                "retryCount" to retryCount,
                "availableAt" to nextAvailableAt,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun markDlq(eventId: String) {
        sqlSession.updateStatement(
            "outboxMarkDlq",
            mapOf(
                "eventId" to eventId,
                "status" to "DLQ",
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun listByWorkspace(workspaceId: String, documentId: String?): List<OutboxEventRow> =
        sqlSession.selectListMapped(
            "outboxListByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId?.takeIf { it.isNotBlank() }
            ),
            ::toOutboxEventRow
        )
}

@Repository
class DlqRepository(private val sqlSession: SqlSessionTemplate) {
    fun insert(outboxEventId: String, workspaceId: String, reason: String, payloadJson: String) {
        sqlSession.insertStatement(
            "dlqInsert",
            mapOf(
                "id" to UUID.randomUUID().toString(),
                "outboxEventId" to outboxEventId,
                "workspaceId" to workspaceId,
                "reason" to reason,
                "payloadJson" to payloadJson,
                "createdAt" to LocalDateTime.now()
            )
        )
    }
}

@Repository
class StageExecutionRepository(private val sqlSession: SqlSessionTemplate) {
    fun tryStart(
        workspaceId: String,
        documentId: String,
        stage: Stage,
        inputHash: String,
        modelVersion: String,
        allowReopenDone: Boolean = false
    ): Boolean {
        val now = LocalDateTime.now()
        try {
            sqlSession.insertStatement(
                "stageExecutionTryInsert",
                mapOf(
                    "id" to UUID.randomUUID().toString(),
                    "workspaceId" to workspaceId,
                    "documentId" to documentId,
                    "stage" to stage.name,
                    "inputHash" to inputHash,
                    "modelVersion" to modelVersion,
                    "status" to StageStatus.RUNNING.name,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            )
            return true
        } catch (_: DuplicateKeyException) {
            // existing execution will be reopened or reused below
        }
        val params = mapOf(
            "workspaceId" to workspaceId,
            "documentId" to documentId,
            "stage" to stage.name,
            "inputHash" to inputHash,
            "modelVersion" to modelVersion,
            "runningStatus" to StageStatus.RUNNING.name,
            "failedStatus" to StageStatus.FAILED.name,
            "doneStatus" to StageStatus.DONE.name,
            "updatedAt" to now,
            "staleRunningCutoff" to now.minusMinutes(10)
        )
        val reopened = if (allowReopenDone) {
            sqlSession.updateStatement("stageExecutionReopenAllowDone", params)
        } else {
            sqlSession.updateStatement("stageExecutionReopenFailedOnly", params)
        }
        return reopened > 0
    }

    fun findByKey(
        workspaceId: String,
        documentId: String,
        stage: Stage,
        inputHash: String,
        modelVersion: String
    ): StageExecutionRow? =
        sqlSession.selectOneMapped(
            "stageExecutionFindByKey",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "stage" to stage.name,
                "inputHash" to inputHash,
                "modelVersion" to modelVersion
            ),
            ::toStageExecutionRow
        )

    fun markDone(workspaceId: String, documentId: String, stage: Stage, inputHash: String, modelVersion: String) {
        sqlSession.updateStatement(
            "stageExecutionMarkDone",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "stage" to stage.name,
                "inputHash" to inputHash,
                "modelVersion" to modelVersion,
                "status" to StageStatus.DONE.name,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun markFailed(
        workspaceId: String,
        documentId: String,
        stage: Stage,
        inputHash: String,
        modelVersion: String,
        message: String
    ) {
        sqlSession.updateStatement(
            "stageExecutionMarkFailed",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "stage" to stage.name,
                "inputHash" to inputHash,
                "modelVersion" to modelVersion,
                "status" to StageStatus.FAILED.name,
                "message" to message.take(500),
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun listByWorkspace(workspaceId: String, documentId: String?): List<StageExecutionRow> =
        sqlSession.selectListMapped(
            "stageExecutionListByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId?.takeIf { it.isNotBlank() }
            ),
            ::toStageExecutionRow
        )
}

@Repository
class TreeRepository(private val sqlSession: SqlSessionTemplate) {
    fun createSnapshot(
        workspaceId: String,
        viewType: String = "TOPIC",
        status: String,
        movedRatio: Double,
        churnCount: Int,
        nodeRenameCount: Int,
        labelCacheJson: String = "{}"
    ): TreeSnapshotRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "treeSnapshotInsert",
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
                "viewType" to viewType,
                "status" to status,
                "movedRatio" to movedRatio,
                "churnCount" to churnCount,
                "nodeRenameCount" to nodeRenameCount,
                "labelCacheJson" to labelCacheJson,
                "createdAt" to now
            )
        )
        return TreeSnapshotRow(
            id = id,
            workspaceId = workspaceId,
            viewType = viewType,
            status = status,
            movedRatio = movedRatio,
            churnCount = churnCount,
            nodeRenameCount = nodeRenameCount,
            labelCacheJson = labelCacheJson,
            createdAt = now,
            activatedAt = null,
            activatedBy = null
        )
    }

    fun findActiveSnapshot(workspaceId: String, viewType: String = "TOPIC"): TreeSnapshotRow? =
        sqlSession.selectOneMapped(
            "treeFindActiveSnapshot",
            mapOf(
                "workspaceId" to workspaceId,
                "viewType" to viewType
            ),
            ::toTreeSnapshotRow
        )

    fun listLockedNodesInActiveSnapshot(workspaceId: String, viewType: String = "TOPIC"): List<TreeNodeRow> {
        val active = findActiveSnapshot(workspaceId, viewType) ?: return emptyList()
        return sqlSession.selectListMapped(
            "treeListLockedNodesInSnapshot",
            mapOf(
                "workspaceId" to workspaceId,
                "snapshotId" to active.id,
                "viewType" to viewType
            ),
            ::toTreeNodeRow
        )
    }

    fun findSnapshotByWorkspace(workspaceId: String, snapshotId: String): TreeSnapshotRow? =
        sqlSession.selectOneMapped(
            "treeFindSnapshotByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId
            ),
            ::toTreeSnapshotRow
        )

    fun listSnapshots(workspaceId: String, viewType: String = "TOPIC"): List<TreeSnapshotRow> =
        sqlSession.selectListMapped(
            "treeListSnapshots",
            mapOf(
                "workspaceId" to workspaceId,
                "viewType" to viewType
            ),
            ::toTreeSnapshotRow
        )

    fun markAllSnapshotsRecommended(workspaceId: String, viewType: String = "TOPIC") {
        sqlSession.updateStatement(
            "treeMarkActiveSnapshotsRecommended",
            mapOf(
                "workspaceId" to workspaceId,
                "viewType" to viewType
            )
        )
    }

    fun activateSnapshot(workspaceId: String, snapshotId: String, actorUserId: String, viewType: String = "TOPIC") {
        sqlSession.updateStatement(
            "treeMarkActiveSnapshotsRecommended",
            mapOf(
                "workspaceId" to workspaceId,
                "viewType" to viewType
            )
        )
        sqlSession.updateStatement(
            "treeActivateSnapshot",
            mapOf(
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId,
                "viewType" to viewType,
                "activatedAt" to LocalDateTime.now(),
                "activatedBy" to actorUserId
            )
        )
    }

    fun insertNode(
        workspaceId: String,
        snapshotId: String,
        viewType: String = "TOPIC",
        parentId: String?,
        label: String,
        depth: Int,
        locked: Boolean
    ): TreeNodeRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "treeNodeInsert",
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId,
                "viewType" to viewType,
                "parentId" to parentId,
                "label" to label,
                "depth" to depth,
                "locked" to locked,
                "createdAt" to now
            )
        )
        return TreeNodeRow(id, workspaceId, snapshotId, viewType, parentId, label, depth, locked, now)
    }

    fun listNodes(workspaceId: String, snapshotId: String, viewType: String = "TOPIC"): List<TreeNodeRow> =
        sqlSession.selectListMapped(
            "treeListNodes",
            mapOf(
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId,
                "viewType" to viewType
            ),
            ::toTreeNodeRow
        )

    fun findNodeByWorkspace(workspaceId: String, nodeId: String): TreeNodeRow? =
        sqlSession.selectOneMapped(
            "treeFindNodeByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "nodeId" to nodeId
            ),
            ::toTreeNodeRow
        )

    fun updateNodeLock(workspaceId: String, nodeId: String, locked: Boolean) {
        sqlSession.updateStatement(
            "treeUpdateNodeLock",
            mapOf(
                "workspaceId" to workspaceId,
                "nodeId" to nodeId,
                "locked" to locked
            )
        )
    }

    fun renameNode(workspaceId: String, nodeId: String, label: String) {
        sqlSession.updateStatement(
            "treeRenameNode",
            mapOf(
                "workspaceId" to workspaceId,
                "nodeId" to nodeId,
                "label" to label
            )
        )
    }

    fun insertMembership(
        workspaceId: String,
        snapshotId: String,
        viewType: String = "TOPIC",
        nodeId: String,
        documentId: String,
        rationaleJson: String
    ) {
        sqlSession.insertStatement(
            "treeMembershipInsert",
            mapOf(
                "id" to UUID.randomUUID().toString(),
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId,
                "viewType" to viewType,
                "nodeId" to nodeId,
                "documentId" to documentId,
                "rationaleJson" to rationaleJson,
                "createdAt" to LocalDateTime.now()
            )
        )
    }

    fun listMemberships(workspaceId: String, snapshotId: String, viewType: String = "TOPIC"): List<TreeMembershipRow> =
        sqlSession.selectListMapped(
            "treeListMemberships",
            mapOf(
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId,
                "viewType" to viewType
            ),
            ::toTreeMembershipRow
        )

    fun findMembershipByDocInSnapshot(
        workspaceId: String,
        snapshotId: String,
        documentId: String,
        viewType: String = "TOPIC"
    ): TreeMembershipRow? =
        sqlSession.selectOneMapped(
            "treeFindMembershipByDocInSnapshot",
            mapOf(
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId,
                "documentId" to documentId,
                "viewType" to viewType
            ),
            ::toTreeMembershipRow
        )

    fun moveDocumentInActiveSnapshot(workspaceId: String, documentId: String, toNodeId: String, viewType: String = "TOPIC") {
        val active = findActiveSnapshot(workspaceId, viewType) ?: return
        sqlSession.updateStatement(
            "treeMoveDocumentInSnapshot",
            mapOf(
                "workspaceId" to workspaceId,
                "snapshotId" to active.id,
                "documentId" to documentId,
                "toNodeId" to toNodeId,
                "viewType" to viewType
            )
        )
    }

    fun findMembershipByWorkspaceAndDocument(workspaceId: String, documentId: String, viewType: String = "TOPIC"): TreeMembershipRow? {
        val active = findActiveSnapshot(workspaceId, viewType) ?: return null
        return findMembershipByDocInSnapshot(workspaceId, active.id, documentId, viewType)
    }
}

@Repository
class ConceptPrototypeRepository(private val sqlSession: SqlSessionTemplate) {
    fun listByWorkspaceAndSnapshot(workspaceId: String, snapshotId: String): List<ConceptPrototypeRow> =
        sqlSession.selectListMapped(
            "conceptPrototypeListByWorkspaceAndSnapshot",
            mapOf(
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId
            ),
            ::toConceptPrototypeRow
        )

    fun listByWorkspaceAndActiveSnapshot(workspaceId: String, viewType: String = "TOPIC"): List<ConceptPrototypeRow> =
        sqlSession.selectListMapped(
            "conceptPrototypeListByWorkspaceAndActiveSnapshot",
            mapOf(
                "workspaceId" to workspaceId,
                "viewType" to viewType
            ),
            ::toConceptPrototypeRow
        )

    fun replaceSnapshotConcepts(workspaceId: String, snapshotId: String, concepts: List<ConceptPrototypeRow>) {
        sqlSession.updateStatement(
            "conceptPrototypeDeleteByWorkspaceAndSnapshot",
            mapOf(
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId
            )
        )
        if (concepts.isEmpty()) {
            return
        }
        val rows = concepts.map { row ->
            mapOf(
                "id" to row.id,
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId,
                "conceptKey" to row.conceptKey,
                "label" to row.label,
                "prototypeVectorJson" to row.prototypeVectorJson,
                "exemplarDocIdsJson" to row.exemplarDocIdsJson,
                "docCount" to row.docCount,
                "driftScore" to row.driftScore,
                "createdAt" to row.createdAt,
                "updatedAt" to row.updatedAt
            )
        }
        sqlSession.insertStatement("conceptPrototypeInsertBatch", mapOf("concepts" to rows))
    }
}

@Repository
class FeedbackRepository(private val sqlSession: SqlSessionTemplate) {
    fun insert(workspaceId: String, userId: String, eventType: String, payloadJson: String) {
        sqlSession.insertStatement(
            "feedbackInsert",
            mapOf(
                "id" to UUID.randomUUID().toString(),
                "workspaceId" to workspaceId,
                "userId" to userId,
                "eventType" to eventType,
                "payloadJson" to payloadJson,
                "createdAt" to LocalDateTime.now()
            )
        )
    }

    fun listByWorkspace(workspaceId: String, limit: Int): List<FeedbackEventRow> =
        sqlSession.selectListMapped(
            "feedbackListByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "limit" to limit
            ),
            ::toFeedbackEventRow
        )
}

@Repository
class WorkspaceTreePolicyRepository(private val sqlSession: SqlSessionTemplate) {
    fun findByWorkspace(workspaceId: String): WorkspaceTreePolicyRow? =
        sqlSession.selectOneMapped(
            "workspaceTreePolicyFindByWorkspace",
            mapOf("workspaceId" to workspaceId),
            ::toWorkspaceTreePolicyRow
        )

    fun upsert(
        workspaceId: String,
        autoThreshold: Double,
        recommendThreshold: Double,
        quarantineEnabled: Boolean,
        rerankerEnabled: Boolean,
        updatedBy: String
    ): WorkspaceTreePolicyRow {
        val now = LocalDateTime.now()
        val params = mapOf(
            "workspaceId" to workspaceId,
            "autoThreshold" to autoThreshold,
            "recommendThreshold" to recommendThreshold,
            "quarantineEnabled" to quarantineEnabled,
            "rerankerEnabled" to rerankerEnabled,
            "updatedBy" to updatedBy,
            "updatedAt" to now
        )
        val updated = sqlSession.updateStatement("workspaceTreePolicyUpdate", params)
        if (updated == 0) {
            try {
                sqlSession.insertStatement("workspaceTreePolicyInsert", params)
            } catch (_: DuplicateKeyException) {
                sqlSession.updateStatement("workspaceTreePolicyUpdate", params)
            }
        }
        return findByWorkspace(workspaceId) ?: WorkspaceTreePolicyRow(
            workspaceId = workspaceId,
            autoThreshold = autoThreshold,
            recommendThreshold = recommendThreshold,
            quarantineEnabled = quarantineEnabled,
            rerankerEnabled = rerankerEnabled,
            updatedBy = updatedBy,
            updatedAt = now
        )
    }
}

@Repository
class WorkspaceQuestionControlRepository(private val sqlSession: SqlSessionTemplate) {
    fun findByWorkspace(workspaceId: String): WorkspaceQuestionControlRow? =
        sqlSession.selectOneMapped(
            "workspaceQuestionControlFindByWorkspace",
            mapOf("workspaceId" to workspaceId),
            ::toWorkspaceQuestionControlRow
        )

    fun upsert(workspaceId: String, enabled: Boolean, updatedBy: String): WorkspaceQuestionControlRow {
        val now = LocalDateTime.now()
        val params = mapOf(
            "workspaceId" to workspaceId,
            "enabled" to enabled,
            "updatedBy" to updatedBy,
            "updatedAt" to now
        )
        val existing = findByWorkspace(workspaceId)
        if (existing == null) {
            sqlSession.insertStatement("workspaceQuestionControlInsert", params)
        } else {
            sqlSession.updateStatement("workspaceQuestionControlUpdate", params)
        }
        return findByWorkspace(workspaceId) ?: WorkspaceQuestionControlRow(
            workspaceId = workspaceId,
            enabled = enabled,
            updatedBy = updatedBy,
            updatedAt = now
        )
    }
}

@Repository
class ActiveLearningQuestionRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(
        workspaceId: String,
        snapshotId: String?,
        questionType: String,
        documentId: String,
        payloadJson: String,
        impactScore: Double,
        expiresAt: LocalDateTime?
    ): ActiveLearningQuestionRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "activeLearningQuestionInsert",
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
                "snapshotId" to snapshotId,
                "questionType" to questionType,
                "status" to "OPEN",
                "documentId" to documentId,
                "payloadJson" to payloadJson,
                "impactScore" to impactScore,
                "expiresAt" to expiresAt,
                "createdAt" to now,
                "updatedAt" to now
            )
        )
        return findByWorkspaceAndId(workspaceId, id) ?: error("failed to create active learning question")
    }

    fun findByWorkspaceAndId(workspaceId: String, questionId: String): ActiveLearningQuestionRow? =
        sqlSession.selectOneMapped(
            "activeLearningQuestionFindByWorkspaceAndId",
            mapOf(
                "workspaceId" to workspaceId,
                "questionId" to questionId
            ),
            ::toActiveLearningQuestionRow
        )

    fun listByWorkspace(workspaceId: String, status: String?, limit: Int): List<ActiveLearningQuestionRow> =
        sqlSession.selectListMapped(
            "activeLearningQuestionListByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "status" to status?.takeIf { it.isNotBlank() },
                "limit" to limit.coerceIn(1, 200)
            ),
            ::toActiveLearningQuestionRow
        )

    fun listOpenByWorkspace(workspaceId: String, limit: Int): List<ActiveLearningQuestionRow> =
        sqlSession.selectListMapped(
            "activeLearningQuestionListOpenByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "limit" to limit.coerceIn(1, 400)
            ),
            ::toActiveLearningQuestionRow
        )

    fun countByWorkspaceAndStatus(workspaceId: String, status: String): Long =
        sqlSession.selectScalar<Long>(
            "activeLearningQuestionCountByWorkspaceAndStatus",
            mapOf(
                "workspaceId" to workspaceId,
                "status" to status
            )
        ) ?: 0L

    fun averageImpactByWorkspaceAndStatus(workspaceId: String, status: String): Double =
        sqlSession.selectScalar<Double>(
            "activeLearningQuestionAverageImpactByWorkspaceAndStatus",
            mapOf(
                "workspaceId" to workspaceId,
                "status" to status
            )
        ) ?: 0.0

    fun markAnswered(workspaceId: String, questionId: String, answerValue: String, answeredBy: String): Int {
        val now = LocalDateTime.now()
        return sqlSession.updateStatement(
            "activeLearningQuestionMarkAnswered",
            mapOf(
                "workspaceId" to workspaceId,
                "questionId" to questionId,
                "answerValue" to answerValue,
                "answeredBy" to answeredBy,
                "answeredAt" to now,
                "updatedAt" to now
            )
        )
    }

    fun expireStale(workspaceId: String, now: LocalDateTime): Int =
        sqlSession.updateStatement(
            "activeLearningQuestionExpireStale",
            mapOf(
                "workspaceId" to workspaceId,
                "updatedAt" to now,
                "now" to now
            )
        )

    fun expireAllOpen(workspaceId: String): Int {
        val now = LocalDateTime.now()
        return sqlSession.updateStatement(
            "activeLearningQuestionExpireAllOpen",
            mapOf(
                "workspaceId" to workspaceId,
                "updatedAt" to now
            )
        )
    }
}

@Repository
class UserRuleRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(
        workspaceId: String,
        ruleType: String,
        ruleValue: String,
        ruleEffect: String,
        nodeId: String,
        createdBy: String
    ): UserRuleRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "userRuleInsert",
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
                "ruleType" to ruleType,
                "ruleValue" to ruleValue,
                "ruleEffect" to ruleEffect,
                "nodeId" to nodeId,
                "enabled" to true,
                "createdBy" to createdBy,
                "createdAt" to now
            )
        )
        return UserRuleRow(id, workspaceId, ruleType, ruleValue, ruleEffect, nodeId, true, createdBy, now)
    }

    fun listByWorkspace(workspaceId: String): List<UserRuleRow> =
        sqlSession.selectListMapped("userRuleListByWorkspace", mapOf("workspaceId" to workspaceId), ::toUserRuleRow)

    fun findByWorkspaceAndId(workspaceId: String, ruleId: String): UserRuleRow? =
        sqlSession.selectOneMapped(
            "userRuleFindByWorkspaceAndId",
            mapOf(
                "workspaceId" to workspaceId,
                "ruleId" to ruleId
            ),
            ::toUserRuleRow
        )

    fun update(
        workspaceId: String,
        ruleId: String,
        ruleType: String,
        ruleValue: String,
        ruleEffect: String,
        nodeId: String
    ): UserRuleRow? {
        sqlSession.updateStatement(
            "userRuleUpdate",
            mapOf(
                "workspaceId" to workspaceId,
                "ruleId" to ruleId,
                "ruleType" to ruleType,
                "ruleValue" to ruleValue,
                "ruleEffect" to ruleEffect,
                "nodeId" to nodeId
            )
        )
        return findByWorkspaceAndId(workspaceId, ruleId)
    }

    fun delete(workspaceId: String, ruleId: String) {
        sqlSession.updateStatement(
            "userRuleDelete",
            mapOf(
                "workspaceId" to workspaceId,
                "ruleId" to ruleId
            )
        )
    }
}

@Repository
class AuditLogRepository(private val sqlSession: SqlSessionTemplate) {
    fun insert(workspaceId: String, actorUserId: String, action: String, payloadJson: String) {
        sqlSession.insertStatement(
            "auditLogInsert",
            mapOf(
                "id" to UUID.randomUUID().toString(),
                "workspaceId" to workspaceId,
                "actorUserId" to actorUserId,
                "action" to action,
                "payloadJson" to payloadJson,
                "createdAt" to LocalDateTime.now()
            )
        )
    }

    fun listByWorkspace(workspaceId: String, type: String?): List<AuditLogRow> =
        listByWorkspace(
            workspaceId = workspaceId,
            type = type,
            actorUserId = null,
            query = null,
            sort = "desc",
            limit = 300
        )

    fun listByWorkspace(
        workspaceId: String,
        type: String?,
        actorUserId: String?,
        query: String?,
        sort: String?,
        limit: Int
    ): List<AuditLogRow> =
        sqlSession.selectListMapped(
            "auditLogListByWorkspace",
            mapOf(
                "workspaceId" to workspaceId,
                "type" to type?.trim()?.takeIf { it.isNotEmpty() },
                "actorUserId" to actorUserId?.trim()?.takeIf { it.isNotEmpty() },
                "queryPattern" to normalizedQueryPattern(query),
                "sortDirection" to if (sort.equals("asc", ignoreCase = true)) "ASC" else "DESC",
                "limit" to limit.coerceIn(1, 500)
            ),
            ::toAuditLogRow
        )
}

@Repository
class PaletteHistoryRepository(private val sqlSession: SqlSessionTemplate) {
    fun insert(
        workspaceId: String,
        userId: String,
        eventType: String,
        queryText: String?,
        documentId: String?,
        commandKey: String?
    ) {
        sqlSession.insertStatement(
            "paletteHistoryInsert",
            mapOf(
                "id" to UUID.randomUUID().toString(),
                "workspaceId" to workspaceId,
                "userId" to userId,
                "eventType" to eventType,
                "queryText" to queryText,
                "documentId" to documentId,
                "commandKey" to commandKey,
                "createdAt" to LocalDateTime.now()
            )
        )
    }

    fun list(workspaceId: String, userId: String, limit: Int): List<PaletteHistoryRow> =
        sqlSession.selectListMapped(
            "paletteHistoryList",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId,
                "limit" to limit.coerceIn(1, 200)
            ),
            ::toPaletteHistoryRow
        )
}

@Repository
class DocumentAclRepository(private val sqlSession: SqlSessionTemplate) {
    fun upsert(workspaceId: String, documentId: String, principalUserId: String, permission: String, grantedBy: String) {
        val params = mapOf(
            "id" to UUID.randomUUID().toString(),
            "workspaceId" to workspaceId,
            "documentId" to documentId,
            "principalUserId" to principalUserId,
            "permission" to permission,
            "grantedBy" to grantedBy,
            "createdAt" to LocalDateTime.now()
        )
        val updated = sqlSession.updateStatement("documentAclUpdate", params)
        if (updated == 0) {
            try {
                sqlSession.insertStatement("documentAclInsert", params)
            } catch (_: DuplicateKeyException) {
                sqlSession.updateStatement("documentAclUpdate", params)
            }
        }
    }

    fun canAccess(workspaceId: String, documentId: String, userId: String): Boolean =
        (sqlSession.selectScalar<Long>(
            "documentAclCountAccess",
            mapOf(
                "workspaceId" to workspaceId,
                "documentId" to documentId,
                "userId" to userId
            )
        ) ?: 0L) > 0
}

@Repository
class WorkspaceInviteRepository(private val sqlSession: SqlSessionTemplate) {
    fun create(workspaceId: String, email: String, role: String, tokenHash: String, invitedBy: String, expiresAt: LocalDateTime): String {
        val id = UUID.randomUUID().toString()
        sqlSession.insertStatement(
            "workspaceInviteInsert",
            mapOf(
                "id" to id,
                "workspaceId" to workspaceId,
                "email" to email,
                "role" to role,
                "tokenHash" to tokenHash,
                "invitedBy" to invitedBy,
                "expiresAt" to expiresAt,
                "createdAt" to LocalDateTime.now()
            )
        )
        return id
    }

    fun findActiveByTokenHash(tokenHash: String): Map<String, Any?>? =
        sqlSession.selectMap(
            "workspaceInviteFindActiveByTokenHash",
            mapOf(
                "tokenHash" to tokenHash,
                "now" to LocalDateTime.now()
            )
        )

    fun markAccepted(id: String, userId: String) {
        sqlSession.updateStatement(
            "workspaceInviteMarkAccepted",
            mapOf(
                "id" to id,
                "userId" to userId,
                "acceptedAt" to LocalDateTime.now()
            )
        )
    }
}

@Repository
class RegistrationVerificationCodeRepository(private val sqlSession: SqlSessionTemplate) {
    fun createOrReplace(email: String, passwordHash: String, codeHash: String, expiresAt: LocalDateTime): String {
        sqlSession.updateStatement(
            "registrationVerificationCodeDeleteActiveByEmail",
            mapOf("email" to email)
        )
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        sqlSession.insertStatement(
            "registrationVerificationCodeInsert",
            mapOf(
                "id" to id,
                "email" to email,
                "passwordHash" to passwordHash,
                "codeHash" to codeHash,
                "expiresAt" to expiresAt,
                "createdAt" to now,
                "updatedAt" to now
            )
        )
        return id
    }

    fun findActiveByEmail(email: String): RegistrationVerificationCodeRow? =
        sqlSession.selectOneMapped(
            "registrationVerificationCodeFindActiveByEmail",
            mapOf(
                "email" to email,
                "now" to LocalDateTime.now()
            ),
            ::toRegistrationVerificationCodeRow
        )

    fun incrementAttempt(id: String) {
        sqlSession.updateStatement(
            "registrationVerificationCodeIncrementAttempt",
            mapOf(
                "id" to id,
                "updatedAt" to LocalDateTime.now()
            )
        )
    }

    fun markConsumed(id: String) {
        val now = LocalDateTime.now()
        sqlSession.updateStatement(
            "registrationVerificationCodeMarkConsumed",
            mapOf(
                "id" to id,
                "consumedAt" to now,
                "updatedAt" to now
            )
        )
    }
}

@Repository
class SearchDocumentRepository(private val sqlSession: SqlSessionTemplate) {
    fun search(
        workspaceId: String,
        userId: String,
        query: String,
        titleOnly: Boolean,
        createdBy: String?,
        updatedBy: String?,
        fromDate: LocalDateTime?,
        toDate: LocalDateTime?,
        scopePageId: String?,
        sortSql: String,
        size: Int,
        offset: Int
    ): List<SearchDocumentRow> =
        sqlSession.selectListMapped(
            "searchDocumentSearch",
            mapOf(
                "workspaceId" to workspaceId,
                "userId" to userId,
                "queryPattern" to normalizedQueryPattern(query)!!,
                "titleOnly" to titleOnly,
                "createdBy" to createdBy,
                "updatedBy" to updatedBy,
                "fromDate" to fromDate,
                "toDate" to toDate,
                "scopePageId" to scopePageId?.takeIf { it.isNotBlank() },
                "sortMode" to normalizedSearchSort(sortSql),
                "limit" to size,
                "offset" to offset
            ),
            ::toSearchDocumentRow
        )
}
