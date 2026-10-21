package com.transformsai.nutrifitai.data.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Upsert;

import com.transformsai.nutrifitai.data.db.entity.ChatMessageEntity;
import com.transformsai.nutrifitai.data.db.entity.ConversationEntity;

import java.util.List;

@Dao
public interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE uid = :uid AND conversationId = :cid AND deleted = 0 ORDER BY createdAt")
    LiveData<List<ChatMessageEntity>> observe(String uid, String cid);

    @Query("SELECT * FROM chat_messages WHERE uid = :uid AND conversationId = :cid AND deleted = 0 "
            + "AND status = 'sent' ORDER BY createdAt DESC LIMIT :limit")
    List<ChatMessageEntity> recent(String uid, String cid, int limit);

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    ChatMessageEntity get(String id);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsert(ChatMessageEntity m);

    @Query("SELECT * FROM conversations WHERE uid = :uid AND deleted = 0 ORDER BY updatedAt DESC LIMIT 1")
    ConversationEntity latestConversation(String uid);

    /** Conversations that have at least one message, newest first (chat history). */
    @Query("SELECT c.* FROM conversations c WHERE c.uid = :uid AND c.deleted = 0 AND EXISTS (SELECT 1 FROM chat_messages m "
            + "WHERE m.conversationId = c.id AND m.deleted = 0) ORDER BY c.updatedAt DESC LIMIT 60")
    java.util.List<ConversationEntity> history(String uid);

    @Query("SELECT * FROM conversations WHERE id = :id")
    ConversationEntity conversation(String id);

    @Upsert // UPDATE in place: REPLACE would delete the row and cascade-delete its children
    void upsertConversation(ConversationEntity c);

    @Query("DELETE FROM chat_messages WHERE uid = :uid")
    void wipeMessages(String uid);

    @Query("DELETE FROM conversations WHERE uid = :uid")
    void wipeConversations(String uid);
}
