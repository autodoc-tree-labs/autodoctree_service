package com.autodoctree.api.db

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberFunctions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TenantRepositoryGuardrailTest {

    @Test
    fun `tenant repositories require workspaceId parameter on public methods`() {
        val repositories: List<KClass<*>> = listOf(
            DocumentRepository::class,
            PipelineStatusRepository::class,
            AttachmentRepository::class,
            DocumentSectionRepository::class,
            EmbeddingRepository::class,
            TreeRepository::class,
            FeedbackRepository::class,
            AuditLogRepository::class
        )

        val violations = repositories.flatMap { repository ->
            repository.memberFunctions
                .filter { it.visibility == KVisibility.PUBLIC }
                .filterNot { it.name in setOf("equals", "hashCode", "toString") }
                .filter { function ->
                    function.parameters
                        .filter { parameter -> parameter.kind == KParameter.Kind.VALUE }
                        .none { parameter -> parameter.name == "workspaceId" }
                }
                .map { function -> "${repository.simpleName}.${function.name}" }
        }

        assertTrue(
            violations.isEmpty(),
            "Found tenant repository methods without workspaceId: ${violations.joinToString(", ")}"
        )
    }
}
