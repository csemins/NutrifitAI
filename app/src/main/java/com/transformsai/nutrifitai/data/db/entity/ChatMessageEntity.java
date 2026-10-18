package com.transformsai.nutrifitai.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.transformsai.nutrifitai.data.db.Syncable;

@Entity(tableName = "chat_messages", indices = {@Index({"uid", "conversationId", "createdAt"})})
public class ChatMessageEntity implements Syncable {
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String uid = "";
    @NonNull
    public String conversationId = "";
    public String role;
    public String text;
    /** Validated suggestion list JSON from the assistant (nullable). */
    public String suggestionsJson;
    /** sent | pending | error */
    public String status;
    public long createdAt;
    public long updatedAt;
    public boolean deleted;

    @Override
    public String syncId() {
        return id;
    }

    @Override
    public long syncUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean syncDeleted() {
        return deleted;
    }
}
