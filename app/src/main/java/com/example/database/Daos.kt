package com.example.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.core.model.DeliveryStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun getMessagesForConversation(convId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE state IN ('QUEUED', 'DISCOVERING', 'ROUTING') ORDER BY createdAt ASC")
    suspend fun getPendingOutboxMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET state = :newState WHERE messageId = :messageId")
    suspend fun updateMessageState(messageId: String, newState: DeliveryStatus)

    @Query("UPDATE messages SET state = :newState, relayHops = :hops WHERE messageId = :messageId")
    suspend fun updateMessageRelay(messageId: String, newState: DeliveryStatus, hops: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)
}

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getPeerById(deviceId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("UPDATE peers SET connectionState = :state, lastSeen = :lastSeen WHERE deviceId = :deviceId")
    suspend fun updatePeerState(deviceId: String, state: String, lastSeen: Long = System.currentTimeMillis())

    @Query("UPDATE peers SET isVerified = :isVerified WHERE deviceId = :deviceId")
    suspend fun setPeerVerified(deviceId: String, isVerified: Boolean)

    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun deletePeer(deviceId: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE conversationId = :convId LIMIT 1")
    suspend fun getConversationById(convId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE peerDeviceId = :peerId LIMIT 1")
    suspend fun getConversationByPeerId(peerId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET lastMessage = :lastMessage, updatedAt = :time WHERE conversationId = :convId")
    suspend fun updateLastMessage(convId: String, lastMessage: String, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM conversations WHERE conversationId = :convId")
    suspend fun deleteConversation(convId: String)
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM crypto_identities LIMIT 1")
    suspend fun getIdentity(): CryptoIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setIdentity(identity: CryptoIdentityEntity)
}

@Dao
interface SeenDao {
    @Query("SELECT COUNT(*) FROM message_seen WHERE messageId = :messageId")
    suspend fun hasSeen(messageId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(seen: MessageSeenEntity)
}

@Dao
interface RoutingDao {
    @Query("SELECT * FROM routing_table WHERE destinationDeviceId = :destId LIMIT 1")
    suspend fun getRoute(destId: String): RoutingEntity?

    @Query("SELECT * FROM routing_table")
    suspend fun getAllRoutes(): List<RoutingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(route: RoutingEntity)

    @Query("DELETE FROM routing_table WHERE destinationDeviceId = :destId")
    suspend fun deleteRoute(destId: String)
}
