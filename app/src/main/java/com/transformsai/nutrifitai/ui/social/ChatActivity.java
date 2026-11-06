package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityChatBinding;
import com.transformsai.nutrifitai.databinding.ItemMessageBinding;
import com.transformsai.nutrifitai.social.SocialModels.Chat;
import com.transformsai.nutrifitai.social.SocialModels.Message;
import com.transformsai.nutrifitai.social.SocialModels.Person;
import com.transformsai.nutrifitai.ui.call.CallActivity;
import com.transformsai.nutrifitai.ui.common.Avatars;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A direct message thread or friend group: messages, challenge invitations, and voice/video calls. Messages are a
 * live Firestore query; sending is queued by Firestore when offline.
 */
public class ChatActivity extends BaseActivity {
    private static final String EXTRA_ID = "chat_id";
    private static final long GROUP_GAP_MS = 5 * 60_000L;

    private ActivityChatBinding b;
    private String id, me;
    @Nullable private Chat chat;
    private final Map<String, String> photos = new HashMap<>();
    private Adapter adapter;

    public static Intent intent(Context c, String chatId) {
        return new Intent(c, ChatActivity.class).putExtra(EXTRA_ID, chatId);
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        me = uidOrFinish();
        id = getIntent().getStringExtra(EXTRA_ID);
        if (me == null) return;
        if (id == null) {
            finish();
            return;
        }
        b = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true); // system bars and keyboard
        b.back.setOnClickListener(v -> finish());
        adapter = new Adapter();
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setReverseLayout(true); // newest at the bottom, list anchored there
        b.list.setLayoutManager(lm);
        b.list.setAdapter(adapter);
        b.send.setOnClickListener(v -> send());
        b.challenge.setOnClickListener(v -> challenge());
        b.audioCall.setOnClickListener(v -> call(false));
        b.videoCall.setOnClickListener(v -> call(true));
        b.more.setOnClickListener(v -> more());

        graph().friends.friends().observe(this, l -> {
            for (Person p : l) photos.put(p.uid, p.photo);
            renderHeader();
            adapter.notifyDataSetChanged();
        });
        graph().chats.chatLive(id).observe(this, c -> {
            if (c == null) { // deleted, or I left
                if (chat != null) finish();
                return;
            }
            chat = c;
            renderHeader();
        });
        graph().chats.messages(id).observe(this, l -> {
            List<Message> list = l == null ? new ArrayList<>() : l;
            adapter.submitList(list, () -> b.list.scrollToPosition(0));
            b.empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            graph().prefs.setChatReadAt(id, System.currentTimeMillis());
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (id != null) graph().prefs.setChatReadAt(id, System.currentTimeMillis());
    }

    private boolean group() {
        return chat != null && Chat.GROUP.equals(chat.type);
    }

    private void renderHeader() {
        if (chat == null) return;
        String title = chat.displayName(me);
        b.title.setText(title);
        if (group()) {
            Avatars.initial(b.avatar, "👥");
            b.subtitle.setText(getResources().getQuantityString(R.plurals.members, chat.members.size(), chat.members.size()));
        } else {
            String other = chat.otherMember(me);
            Avatars.of(b.avatar, other, other == null ? null : photos.get(other), title);
            b.subtitle.setText(R.string.chat_friend);
        }
        b.emptyTitle.setText(getString(R.string.chat_say_hi_to, title));
    }

    private void send() {
        String text = b.input.getText() == null ? "" : b.input.getText().toString().trim();
        if (text.isEmpty() || chat == null) return;
        graph().chats.send(chat, text, Message.TEXT, null);
        b.input.setText("");
    }

    private void challenge() {
        if (chat == null) return;
        startActivity(group() ? NewChallengeActivity.intent(this, null, chat.id)
                : NewChallengeActivity.intent(this, chat.otherMember(me), null));
    }

    /** Starts a call in this chat's room and invites everyone with a call message. */
    private void call(boolean video) {
        if (chat == null) return;
        graph().chats.send(chat, getString(video ? R.string.call_started_video : R.string.call_started_audio), Message.CALL, video ? "video" : "audio");
        startActivity(CallActivity.intent(this, chat.id, chat.displayName(me), video));
    }

    private void more() {
        if (chat == null) return;
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (group()) {
            items.add(getString(R.string.group_members));
            actions.add(this::showMembers);
            if (me.equals(chat.createdBy)) {
                items.add(getString(R.string.group_edit));
                actions.add(() -> startActivity(NewGroupActivity.edit(this, chat.id)));
            }
            items.add(getString(me.equals(chat.createdBy) ? R.string.group_delete : R.string.group_leave));
            actions.add(this::leave);
        } else {
            items.add(getString(R.string.challenge_short));
            actions.add(this::challenge);
        }
        new MaterialAlertDialogBuilder(this).setItems(items.toArray(new String[0]), (d, w) -> actions.get(w).run()).show();
    }

    private void showMembers() {
        if (chat == null) return;
        List<String> names = new ArrayList<>();
        for (String m : chat.members) {
            String n = chat.names.containsKey(m) ? chat.names.get(m) : getString(R.string.friend);
            names.add(m.equals(me) ? n + " (" + getString(R.string.you) + ")" : m.equals(chat.createdBy) ? n + " · " + getString(R.string.group_admin) : n);
        }
        new MaterialAlertDialogBuilder(this).setTitle(chat.displayName(me))
                .setItems(names.toArray(new String[0]), null).setPositiveButton(R.string.close, null).show();
    }

    private void leave() {
        if (chat == null) return;
        boolean owner = me.equals(chat.createdBy);
        new MaterialAlertDialogBuilder(this).setTitle(owner ? R.string.group_delete_q : R.string.group_leave_q)
                .setPositiveButton(owner ? R.string.delete : R.string.group_leave, (d, w) -> {
                    graph().chats.leave(chat);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    // ------------------------------------------------------------------ list
    private class Adapter extends ListAdapter<Message, Holder> {
        Adapter() {
            super(new DiffUtil.ItemCallback<Message>() {
                @Override
                public boolean areItemsTheSame(@NonNull Message a, @NonNull Message c) {
                    return a.id.equals(c.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull Message a, @NonNull Message c) {
                    return a.createdAt == c.createdAt;
                }
            });
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new Holder(ItemMessageBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            Message m = getItem(pos);
            // reverse layout: pos + 1 is the older message above, pos - 1 the newer one below
            Message older = pos + 1 < getItemCount() ? getItem(pos + 1) : null;
            Message newer = pos > 0 ? getItem(pos - 1) : null;
            boolean mine = me.equals(m.from);
            boolean firstOfRun = older == null || !older.from.equals(m.from) || m.createdAt - older.createdAt > GROUP_GAP_MS;
            boolean lastOfRun = newer == null || !newer.from.equals(m.from) || newer.createdAt - m.createdAt > GROUP_GAP_MS;
            ItemMessageBinding v = h.v;
            Context c = v.getRoot().getContext();

            v.row.setGravity(mine ? Gravity.END | Gravity.BOTTOM : Gravity.START | Gravity.BOTTOM);
            ((LinearLayout.LayoutParams) v.column.getLayoutParams()).weight = 0;
            v.column.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
            v.column.setGravity(mine ? Gravity.END : Gravity.START);
            v.avatar.setVisibility(mine ? View.GONE : lastOfRun ? View.VISIBLE : View.INVISIBLE);
            if (!mine && lastOfRun) Avatars.of(v.avatar, m.from, photos.get(m.from), m.fromName);
            v.name.setVisibility(!mine && group() && firstOfRun ? View.VISIBLE : View.GONE);
            v.name.setText(m.fromName);
            v.time.setVisibility(lastOfRun ? View.VISIBLE : View.GONE);
            v.time.setText(DateUtils.formatSameDayTime(m.createdAt, System.currentTimeMillis(),
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT));
            v.getRoot().setPadding(0, firstOfRun ? (int) (8 * c.getResources().getDisplayMetrics().density) : 0, 0, 0);

            boolean card = Message.CHALLENGE.equals(m.kind) || Message.CALL.equals(m.kind);
            v.text.setVisibility(card ? View.GONE : View.VISIBLE);
            v.card.setVisibility(card ? View.VISIBLE : View.GONE);
            if (!card) {
                v.text.setText(m.text);
                v.text.setBackgroundResource(mine ? R.drawable.bg_bubble_user : R.drawable.bg_bubble_bot);
                v.text.setTextColor(ContextCompat.getColor(c, mine ? R.color.nl_on_lime : R.color.nl_text));
                return;
            }
            if (Message.CHALLENGE.equals(m.kind)) {
                v.cardTitle.setText(m.text);
                v.cardTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_trophy, 0, 0, 0);
                v.cardBody.setText(mine ? getString(R.string.chat_you_challenged) : getString(R.string.chat_challenged_you, m.fromName));
                v.cardAction.setText(R.string.challenge_open);
                v.cardAction.setOnClickListener(x -> {
                    if (m.ref != null) startActivity(ChallengeLobbyActivity.intent(ChatActivity.this, m.ref));
                });
            } else {
                boolean video = "video".equals(m.ref);
                v.cardTitle.setText(m.text);
                v.cardTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(video ? R.drawable.ic_video : R.drawable.ic_call, 0, 0, 0);
                boolean recent = System.currentTimeMillis() - m.createdAt < 2 * 3600_000L;
                v.cardBody.setText(recent ? getString(R.string.call_tap_join) : getString(R.string.call_earlier));
                v.cardAction.setText(recent ? R.string.call_join : R.string.call_again);
                v.cardAction.setOnClickListener(x -> {
                    if (chat == null) return;
                    if (recent) startActivity(CallActivity.intent(ChatActivity.this, chat.id, chat.displayName(me), video));
                    else call(video);
                });
            }
        }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ItemMessageBinding v;

        Holder(ItemMessageBinding v) {
            super(v.getRoot());
            this.v = v;
        }
    }
}
