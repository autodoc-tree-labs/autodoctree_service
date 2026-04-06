package com.autodoctree.api.db.auth

import com.autodoctree.api.db.*
import com.autodoctree.api.infra.ConflictException
import com.autodoctree.common.Stage
import com.autodoctree.common.StageStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID

@Repository
class UserRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<UserRow> { rs: ResultSet, _: Int ->
        UserRow(
            id = rs.getString("id"),
            email = rs.getString("email"),
            passwordHash = rs.getString("password_hash"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    fun create(email: String, passwordHash: String): UserRow {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO users(id, email, password_hash, created_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            id,
            email,
            passwordHash,
            now
        )
        return UserRow(id, email, passwordHash, now)
    }

    fun findByEmail(email: String): UserRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM users WHERE email = ?",
        mapper,
        email
    )

    fun findById(id: String): UserRow? = jdbcTemplate.queryOneOrNull(
        "SELECT * FROM users WHERE id = ?",
        mapper,
        id
    )
}

@Repository
class RefreshTokenRepository(private val jdbcTemplate: JdbcTemplate) {

    fun create(userId: String, tokenHash: String, expiresAt: LocalDateTime): String {
        val id = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO refresh_tokens(id, user_id, token_hash, expires_at, revoked_at, created_at)
            VALUES (?, ?, ?, ?, NULL, ?)
            """.trimIndent(),
            id,
            userId,
            tokenHash,
            expiresAt,
            LocalDateTime.now()
        )
        return id
    }

    fun findActiveByHash(tokenHash: String): Map<String, Any?>? {
        val rows = jdbcTemplate.queryForList(
            """
            SELECT id, user_id, expires_at, revoked_at
            FROM refresh_tokens
            WHERE token_hash = ?
            """.trimIndent(),
            tokenHash
        )
        return rows.firstOrNull()?.takeIf {
            val revokedAt = it["revoked_at"]
            val expiresAt = (it["expires_at"] as java.sql.Timestamp).toLocalDateTime()
            revokedAt == null && expiresAt.isAfter(LocalDateTime.now())
        }
    }

    fun revokeByHash(tokenHash: String) {
        jdbcTemplate.update(
            "UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            LocalDateTime.now(),
            tokenHash
        )
    }
}

@Repository
class RegistrationVerificationCodeRepository(private val jdbcTemplate: JdbcTemplate) {
    private val mapper = RowMapper<RegistrationVerificationCodeRow> { rs: ResultSet, _: Int ->
        RegistrationVerificationCodeRow(
            id = rs.getString("id"),
            email = rs.getString("email"),
            passwordHash = rs.getString("password_hash"),
            codeHash = rs.getString("code_hash"),
            expiresAt = rs.getTimestamp("expires_at").toLocalDateTime(),
            attemptCount = rs.getInt("attempt_count"),
            consumedAt = rs.getTimestamp("consumed_at")?.toLocalDateTime(),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun createOrReplace(email: String, passwordHash: String, codeHash: String, expiresAt: LocalDateTime): String {
        jdbcTemplate.update(
            """
            DELETE FROM registration_verification_codes
            WHERE email = ?
              AND consumed_at IS NULL
            """.trimIndent(),
            email
        )

        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            INSERT INTO registration_verification_codes(
              id, email, password_hash, code_hash, expires_at, attempt_count, consumed_at, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, 0, NULL, ?, ?)
            """.trimIndent(),
            id,
            email,
            passwordHash,
            codeHash,
            expiresAt,
            now,
            now
        )
        return id
    }

    fun findActiveByEmail(email: String): RegistrationVerificationCodeRow? = jdbcTemplate.queryOneOrNull(
        """
        SELECT *
        FROM registration_verification_codes
        WHERE email = ?
          AND consumed_at IS NULL
          AND expires_at > ?
        ORDER BY created_at DESC
        LIMIT 1
        """.trimIndent(),
        mapper,
        email,
        LocalDateTime.now()
    )

    fun incrementAttempt(id: String) {
        jdbcTemplate.update(
            """
            UPDATE registration_verification_codes
            SET attempt_count = attempt_count + 1,
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            LocalDateTime.now(),
            id
        )
    }

    fun markConsumed(id: String) {
        val now = LocalDateTime.now()
        jdbcTemplate.update(
            """
            UPDATE registration_verification_codes
            SET consumed_at = ?,
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
            now,
            now,
            id
        )
    }
}
