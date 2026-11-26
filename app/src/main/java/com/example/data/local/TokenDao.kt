package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TokenDao {

    @Transaction
    @Query("SELECT * FROM token_profiles ORDER BY createdAtMillis DESC")
    fun observeAllProfilesWithServers(): Flow<List<TokenProfileWithServers>>

    @Transaction
    @Query("SELECT * FROM token_profiles ORDER BY createdAtMillis DESC")
    suspend fun getAllProfilesWithServers(): List<TokenProfileWithServers>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: TokenProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServers(servers: List<ServerEntity>)

    @Transaction
    suspend fun insertProfileWithServers(
        profile: TokenProfileEntity,
        serversFactory: (Long) -> List<ServerEntity>,
    ): Long {
        val profileId = insertProfile(profile)
        val servers = serversFactory(profileId)
        if (servers.isNotEmpty()) {
            insertServers(servers)
        }
        return profileId
    }

    @Query("UPDATE token_profiles SET name = :newName WHERE id = :profileId")
    suspend fun renameProfile(profileId: Long, newName: String)

    @Query("UPDATE token_profiles SET enabled = :enabled WHERE id = :profileId")
    suspend fun setProfileEnabled(profileId: Long, enabled: Boolean)

    @Query("DELETE FROM token_profiles WHERE id = :profileId")
    suspend fun deleteProfile(profileId: Long)

    @Query("DELETE FROM token_profiles")
    suspend fun deleteAllProfiles()

    @Query("UPDATE fptn_servers SET pingMs = :pingMs, lastPingAtMillis = :timestamp WHERE id = :serverId")
    suspend fun updateServerPing(serverId: Long, pingMs: Int?, timestamp: Long)
}
