package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.domain.model.FptnServer
import com.example.domain.model.TokenProfile

@Entity(tableName = "token_profiles")
data class TokenProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val rawToken: String,
    val serviceName: String,
    val username: String,
    val password: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "fptn_servers",
    foreignKeys = [
        ForeignKey(
            entity = TokenProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["profileId"])],
)
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val profileId: Long,
    val name: String,
    val host: String,
    val port: Int,
    val md5Fingerprint: String,
    val isCensoredZone: Boolean = false,
    val pingMs: Int? = null,
    val lastPingAtMillis: Long = 0L,
)

data class TokenProfileWithServers(
    @Embedded val profile: TokenProfileEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "profileId",
    )
    val servers: List<ServerEntity>,
) {
    fun toDomain(): TokenProfile = TokenProfile(
        id = profile.id,
        name = profile.name,
        rawToken = TokenSecretCipher.decrypt(profile.rawToken),
        serviceName = profile.serviceName,
        username = TokenSecretCipher.decrypt(profile.username),
        password = TokenSecretCipher.decrypt(profile.password),
        version = profile.version,
        enabled = profile.enabled,
        createdAtMillis = profile.createdAtMillis,
        servers = servers.map { entity ->
            FptnServer(
                id = entity.id,
                profileId = entity.profileId,
                profileName = profile.name,
                name = entity.name,
                host = entity.host,
                port = entity.port,
                md5Fingerprint = entity.md5Fingerprint,
                isCensoredZone = entity.isCensoredZone,
                pingMs = entity.pingMs,
                lastPingAtMillis = entity.lastPingAtMillis,
            )
        },
    )
}
