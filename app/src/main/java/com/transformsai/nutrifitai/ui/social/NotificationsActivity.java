package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityNotificationsBinding;
import com.transformsai.nutrifitai.databinding.ItemNotificationBinding;
import com.transformsai.nutrifitai.social.SocialModels.InboxItem;
import com.transformsai.nutrifitai.ui.common.Avatars;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.main.MainActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything friends sent me: requests, challenges, comments, reactions, messages and calls. Tapping one marks it
 * read and opens what it is about. Phone notifications open this screen with the item, which routes the same way.
 */
public class NotificationsActivity extends BaseActivity {
    private static final String EXTRA_ID = "id", EXTRA_TYPE = "type", EXTRA_REF = "ref";
    private ActivityNotificationsBinding b;
    private List<InboxItem> items = new ArrayList<>();
    private final com.transformsai.nutrifitai.ui.common.Permissions.Asker asker =
            new com.transformsai.nutrifitai.ui.common.Permissions.Asker(this, () -> this);

    /** Opens this screen and, from there, whatever {@code n} is about. */
    public static Intent open(Context c, InboxItem n) {
        return new Intent(c, NotificationsActivity.class).putExtra(EXTRA_ID, n.id).putExtra(EXTRA_TYPE, n.type)
                .putExtra(EXTRA_REF, n.ref);
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityNotificationsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.bar.getRoot(), true, false);
        InsetsUtil.padding(b.list, false, true);
        b.bar.title.setText(R.string.notifications);
        b.bar.back.setOnClickListener(v -> finish());
        b.bar.action.setImageResource(R.drawable.ic_check);
        b.bar.action.setContentDescription(getString(R.string.mark_all_read));
        b.bar.action.setOnClickListener(v -> graph().inbox.markAllRead(items));
        b.turnOn.setOnClickListener(v -> {
            String perm = com.transformsai.nutrifitai.ui.common.Permissions.NOTIFICATIONS;
            if (perm.isEmpty() || com.transformsai.nutrifitai.ui.common.Permissions.granted(this, perm)) {
                // permission is there but notifications are switched off in Android settings
                com.transformsai.nutrifitai.ui.common.Permissions.openSettings(this, "");
            } else {
                asker.run(perm, R.string.perm_why_notifications, this::renderBanner, this::renderBanner);
            }
        });
        b.empty.art.setImageResource(R.drawable.ill_chat);
        b.empty.title.setText(R.string.notif_empty_title);
        b.empty.body.setText(R.string.notif_empty_body);
        b.empty.action.setText(R.string.find_friends);
        b.empty.action.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SOCIAL)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });

        Adapter adapter = new Adapter();
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START | ItemTouchHelper.END) {
            @Override
            public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder t) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder h, int dir) {
                int pos = h.getBindingAdapterPosition();
                if (pos >= 0 && pos < items.size()) graph().inbox.delete(items.get(pos));
            }
        }).attachToRecyclerView(b.list);

        graph().inbox.items().observe(this, l -> {
            items = l == null ? new ArrayList<>() : l;
            adapter.submitList(new ArrayList<>(items));
            b.empty.getRoot().setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            boolean unread = false;
            for (InboxItem i : items) unread |= !i.read;
            b.bar.action.setVisibility(unread ? View.VISIBLE : View.GONE);
        });

        if (s == null && getIntent().hasExtra(EXTRA_TYPE)) {
            InboxItem n = new InboxItem();
            n.id = getIntent().getStringExtra(EXTRA_ID);
            n.type = getIntent().getStringExtra(EXTRA_TYPE);
            n.ref = getIntent().getStringExtra(EXTRA_REF);
            if (n.id != null) graph().inbox.markRead(n);
            route(n);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderBanner();
    }

    private void renderBanner() {
        if (b != null) b.offBanner.setVisibility(com.transformsai.nutrifitai.ui.common.Permissions.notificationsOn(this) ? View.GONE : View.VISIBLE);
    }

    /** Opens the screen a notification is about. */
    private void route(InboxItem n) {
        String t = n.type == null ? "" : n.type;
        if ((InboxItem.CHALLENGE.equals(t)) && n.ref != null) {
            startActivity(ChallengeLobbyActivity.intent(this, n.ref));
        } else if ((InboxItem.COMMENT.equals(t) || InboxItem.REACTION.equals(t)) && n.ref != null) {
            startActivity(CommentsActivity.intent(this, n.ref));
        } else if ((InboxItem.MESSAGE.equals(t) || InboxItem.GROUP.equals(t) || InboxItem.CALL.equals(t)) && n.ref != null) {
            startActivity(ChatActivity.intent(this, n.ref));
        } else if (InboxItem.FRIEND_REQUEST.equals(t) || InboxItem.FRIEND_ACCEPTED.equals(t)) {
            SocialFragment.showSection(SocialFragment.SECTION_FRIENDS);
            startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SOCIAL)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
    }

    static int icon(@Nullable String type) {
        if (type == null) return R.drawable.ic_bell;
        switch (type) {
            case InboxItem.FRIEND_REQUEST:
            case InboxItem.FRIEND_ACCEPTED:
                return R.drawable.ic_people;
            case InboxItem.CHALLENGE:
                return R.drawable.ic_trophy;
            case InboxItem.COMMENT:
                return R.drawable.ic_comment;
            case InboxItem.REACTION:
                return R.drawable.ic_heart;
            case InboxItem.MESSAGE:
                return R.drawable.ic_chat;
            case InboxItem.GROUP:
                return R.drawable.ic_group;
            case InboxItem.CALL:
                return R.drawable.ic_call;
            default:
                return R.drawable.ic_bell;
        }
    }

    private class Adapter extends ListAdapter<InboxItem, Holder> {
        Adapter() {
            super(new DiffUtil.ItemCallback<InboxItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull InboxItem a, @NonNull InboxItem c) {
                    return a.id.equals(c.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull InboxItem a, @NonNull InboxItem c) {
                    return a.read == c.read && a.createdAt == c.createdAt;
                }
            });
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new Holder(ItemNotificationBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            InboxItem n = getItem(pos);
            Avatars.of(h.v.avatar, n.from, null, n.fromName);
            h.v.kind.setImageResource(icon(n.type));
            h.v.title.setText(n.title);
            h.v.body.setText(n.body);
            h.v.body.setVisibility(n.body == null || n.body.isEmpty() ? View.GONE : View.VISIBLE);
            h.v.time.setText(Ui.ago(h.itemView.getContext(), n.createdAt));
            h.v.dot.setVisibility(n.read ? View.INVISIBLE : View.VISIBLE);
            h.itemView.setAlpha(n.read ? 0.78f : 1f);
            h.itemView.setContentDescription((n.read ? "" : getString(R.string.unread) + ". ") + n.title + ". " + n.body);
            h.itemView.setOnClickListener(v -> {
                graph().inbox.markRead(n);
                route(n);
            });
        }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ItemNotificationBinding v;

        Holder(ItemNotificationBinding v) {
            super(v.getRoot());
            this.v = v;
        }
    }
}
