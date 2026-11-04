package com.transformsai.nutrifitai.ui.assistant;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.ChatMessageEntity;

import java.util.List;

public class AssistantViewModel extends ViewModel {
    private final AppGraph g = App.graph();
    private final String uid = g.session.requireUid();
    private final MutableLiveData<String> conversation = new MutableLiveData<>();
    public final LiveData<List<ChatMessageEntity>> messages =
            Transformations.switchMap(conversation, c -> g.assistant.observe(uid, c));
    public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    public final MutableLiveData<String> error = new MutableLiveData<>();

    public AssistantViewModel() {
        g.assistant.conversationId(r -> conversation.setValue(r.value));
    }

    public void send(String text) {
        String c = conversation.getValue();
        if (c == null || Boolean.TRUE.equals(busy.getValue())) return;
        busy.setValue(true);
        g.assistant.send(c, text, r -> {
            busy.setValue(false);
            if (!r.ok()) error.setValue(r.error);
        });
    }

    public void retry(String messageId) {
        busy.setValue(true);
        g.assistant.retry(messageId, r -> {
            busy.setValue(false);
            if (!r.ok()) error.setValue(r.error);
        });
    }

    public void open(String conversationId) {
        g.assistant.openConversation(conversationId);
        conversation.setValue(conversationId);
    }

    public String current() {
        return conversation.getValue();
    }

    public void newChat() {
        g.assistant.newConversation(r -> conversation.setValue(r.value));
    }
}
