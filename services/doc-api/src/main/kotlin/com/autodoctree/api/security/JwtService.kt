package com.autodoctree.api.security

import com.autodoctree.api.config.AuthProperties
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtService(
    private val authProperties: AuthProperties
) {
    private val key: SecretKey by lazy {
        Keys.hmacShaKeyFor(authProperties.jwtSecret.padEnd(32, 'x').toByteArray())
    }

    fun generateAccessToken(user: AuthUser): String {
        val now = Instant.now()
        val expiry = now.plusSeconds(authProperties.accessTokenTtlSeconds)
        return Jwts.builder()
            .subject(user.id)
            .claim("email", user.email)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .signWith(key)
            .compact()
    }

    fun parseAccessToken(token: String): AuthUser {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        return AuthUser(
            id = claims.subject,
            email = claims["email", String::class.java]
        )
    }
}
