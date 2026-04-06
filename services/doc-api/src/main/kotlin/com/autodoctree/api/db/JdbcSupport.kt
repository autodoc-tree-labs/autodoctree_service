package com.autodoctree.api.db

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

internal fun <T> JdbcTemplate.queryOneOrNull(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): T? {
    val list = query(sql, rowMapper, *args)
    return list.firstOrNull()
}

internal fun ResultSet.getNullableDouble(column: String): Double? {
    val raw = getObject(column) as? Number ?: return null
    return raw.toDouble()
}
