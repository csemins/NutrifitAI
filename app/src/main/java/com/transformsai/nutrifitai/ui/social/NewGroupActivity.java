package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityNewGroupBinding;
import com.transformsai.nutrifitai.databinding.ItemPickPersonBinding;
import com.transformsai.nutrifitai.social.ChatRepository;
import com.transformsai.nutrifitai.social.SocialModels.Chat;
import com.transformsai.nutrifitai.social.SocialModels.Person;
import com.transformsai.nutrifitai.ui.common.Avatars;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Create a friend group (name + friends), or, as its creator, rename it and change who is in it. */
public class NewGroupActivity extends BaseActivity {
    private static final String EXTRA_EDIT = "edit";

    private ActivityNewGroupBinding b;
    private final List<Person> friends = new ArrayList<>();
    private final Set<String> picked = new LinkedHashSet<>();
    @Nullable private String editId;
    @Nullable private Chat editing;
    private boolean saving;
    private final Adapter adapter = new Adapter();

    public static Intent edit(Context c, String chatId) {
        return new Intent(c, NewGroupActivity.class).putExtra(EXTRA_EDIT, chatId);
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityNewGroupBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, false);
        InsetsUtil.padding(b.bottom, false, true);
        editId = getIntent().getStringExtra(EXTRA_EDIT);
        b.bar.title.setText(editId == null ? R.string.group_new : R.string.group_edit);
        b.bar.back.setOnClickListener(v -> finish());
        b.save.setText(editId == null ? R.string.group_create : R.string.save);
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);
        b.empty.art.setImageResource(R.drawable.ill_chat);
        b.empty.title.setText(R.string.friends_empty_title);
        b.empty.body.setText(R.string.group_need_friends);
        b.empty.action.setVisibility(View.GONE);
        b.name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int n, int x) { }
            @Override public void onTextChanged(CharSequence c, int a, int n, int x) { }

            @Override
            public void afterTextChanged(Editable e) {
                b.nameLayout.setError(null);
                update();
            }
        });
        if (s != null && s.getStringArrayList("picked") != null) picked.addAll(s.getStringArrayList("picked"));
        Ui.onClick(b.save, v -> save());

        graph().friends.friends().observe(this, l -> {
            friends.clear();
            friends.addAll(l);
            if (editing != null) addMissingMembers();
            adapter.notifyDataSetChanged();
            b.empty.getRoot().setVisibility(friends.isEmpty() ? View.VISIBLE : View.GONE);
            update();
        });
        if (editId != null) {
            graph().chats.chatLive(editId).observe(this, c -> {
                if (c == null || editing != null) return;
                editing = c;
                if (s == null) {
                    b.name.setText(c.title);
                    String me = graph().session.uid();
                    for (String m : c.members) if (!m.equals(me)) picked.add(m);
                }
                addMissingMembers();
                adapter.notifyDataSetChanged();
                update();
            });
        }
    }

    /** Members who aren't (or are no longer) my friends still show, so they can be removed. */
    private void addMissingMembers() {
        if (editing == null) return;
        Set<String> known = new LinkedHashSet<>();
        for (Person p : friends) known.add(p.uid);
        String me = graph().session.uid();
        for (String m : editing.members) {
            if (m.equals(me) || known.contains(m)) continue;
            friends.add(new Person(m, editing.names.containsKey(m) ? editing.names.get(m) : getString(R.string.friend), null, null));
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList("picked", new ArrayList<>(picked));
    }

    private String name() {
        return b.name.getText() == null ? "" : b.name.getText().toString().trim();
    }

    private void update() {
        int n = picked.size();
        b.count.setText(getString(R.string.group_pick_count, n, ChatRepository.MAX_GROUP - 1));
        b.save.setEnabled(!saving && n >= 1 && n < ChatRepository.MAX_GROUP && !name().isEmpty());
    }

    private void save() {
        String title = name();
        if (title.isEmpty()) {
            b.nameLayout.setError(getString(R.string.group_name_needed));
            return;
        }
        if (saving || picked.isEmpty()) return;
        saving = true;
        update();
        List<Person> who = new ArrayList<>();
        for (Person p : friends) if (picked.contains(p.uid)) who.add(p);
        if (editing != null) {
            String me = graph().session.uid();
            List<String> members = new ArrayList<>();
            Map<String, String> names = new HashMap<>();
            members.add(me);
            names.put(me, editing.names.containsKey(me) ? editing.names.get(me) : getString(R.string.you));
            for (Person p : who) {
                members.add(p.uid);
                names.put(p.uid, p.name == null ? getString(R.string.friend) : p.name);
            }
            graph().chats.updateGroup(editing, title, members, names);
            finish();
            return;
        }
        graph().chats.createGroup(title, who, r -> {
            saving = false;
            if (!r.ok()) {
                update();
                Ui.snack(b.getRoot(), r.error);
                return;
            }
            startActivity(ChatActivity.intent(this, r.value));
            finish();
        });
    }

    private class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new Holder(ItemPickPersonBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            Person p = friends.get(pos);
            Avatars.of(h.v.avatar, p.uid, p.photo, p.name);
            h.v.title.setText(p.name);
            h.v.subtitle.setText(p.handle == null ? "" : "@" + p.handle);
            h.v.subtitle.setVisibility(p.handle == null ? View.GONE : View.VISIBLE);
            boolean on = picked.contains(p.uid);
            h.v.check.setChecked(on);
            h.itemView.setContentDescription(p.name + (on ? ", " + getString(R.string.selected) : ""));
            h.itemView.setOnClickListener(v -> {
                if (!picked.remove(p.uid)) picked.add(p.uid);
                notifyItemChanged(h.getBindingAdapterPosition());
                update();
            });
        }

        @Override
        public int getItemCount() {
            return friends.size();
        }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ItemPickPersonBinding v;

        Holder(ItemPickPersonBinding v) {
            super(v.getRoot());
            this.v = v;
        }
    }
}
