package com.transformsai.nutrifitai.ui.assistant;

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
import com.transformsai.nutrifitai.data.db.entity.ConversationEntity;
import com.transformsai.nutrifitai.databinding.ItemChatHistoryBinding;
import com.transformsai.nutrifitai.databinding.ItemSocialSectionBinding;
import com.transformsai.nutrifitai.databinding.SheetChatHistoryBinding;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Chats: start a new one, search earlier ones by their first question, grouped by Today / Yesterday /
 * Earlier, with the open chat highlighted and a delete button on each row.
 */
final class ChatHistorySheet {
    private final BaseActivity activity;
    private final Consumer<String> onOpen, onDelete;
    private final Runnable onNew;
    private final List<ConversationEntity> all;
    @Nullable private final String current;
    private final BottomSheetDialog dialog;
    private final SheetChatHistoryBinding v;
    private final Adapter adapter = new Adapter();

    private ChatHistorySheet(BaseActivity a, List<ConversationEntity> all, @Nullable String current,
                             Consumer<String> onOpen, Runnable onNew, Consumer<String> onDelete) {
        this.activity = a;
        this.all = new ArrayList<>(all);
        this.current = current;
        this.onOpen = onOpen;
        this.onNew = onNew;
        this.onDelete = onDelete;
        dialog = new BottomSheetDialog(a);
        v = SheetChatHistoryBinding.inflate(a.getLayoutInflater());
        dialog.setContentView(v.getRoot());
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        v.list.setLayoutManager(new LinearLayoutManager(a));
        v.list.setAdapter(adapter);
        v.newChat.setOnClickListener(x -> {
            dialog.dismiss();
            onNew.run();
        });
        v.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) { }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) { }

            @Override
            public void afterTextChanged(Editable e) {
                render(e.toString());
            }
        });
        render("");
    }

    static void show(BaseActivity a, List<ConversationEntity> all, @Nullable String current,
                     Consumer<String> onOpen, Runnable onNew, Consumer<String> onDelete) {
        new ChatHistorySheet(a, all, current, onOpen, onNew, onDelete).dialog.show();
    }

    @SuppressWarnings("NotifyDataSetChanged") // the grouped list is rebuilt on every search keystroke
    private void render(String query) {
        String q = query.trim().toLowerCase(Locale.getDefault());
        LocalDate today = DateUtil.todayDate();
        List<Object> rows = new ArrayList<>();
        String lastGroup = null;
        for (ConversationEntity c : all) {
            String title = title(c);
            if (!q.isEmpty() && !title.toLowerCase(Locale.getDefault()).contains(q)) continue;
            LocalDate d = Instant.ofEpochMilli(c.updatedAt).atZone(DateUtil.zone()).toLocalDate();
            String group = activity.getString(d.equals(today) ? R.string.chats_today
                    : d.equals(today.minusDays(1)) ? R.string.chats_yesterday : R.string.chats_earlier);
            if (!group.equals(lastGroup)) {
                rows.add(group);
                lastGroup = group;
            }
            rows.add(c);
        }
        adapter.rows = rows;
        adapter.notifyDataSetChanged();
        v.empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        v.searchLayout.setVisibility(all.size() > 4 ? View.VISIBLE : View.GONE);
    }

    private String title(ConversationEntity c) {
        return c.title == null || c.title.isEmpty() ? activity.getString(R.string.new_chat) : c.title;
    }

    private void confirmDelete(ConversationEntity c) {
        new MaterialAlertDialogBuilder(activity).setTitle(R.string.chats_delete_q).setMessage(title(c))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    all.remove(c);
                    onDelete.accept(c.id);
                    render(v.search.getText() == null ? "" : v.search.getText().toString());
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<Object> rows = new ArrayList<>();

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) instanceof String ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
            LayoutInflater in = LayoutInflater.from(p.getContext());
            View view = type == 0 ? ItemSocialSectionBinding.inflate(in, p, false).getRoot()
                    : ItemChatHistoryBinding.inflate(in, p, false).getRoot();
            return new RecyclerView.ViewHolder(view) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int i) {
            Object row = rows.get(i);
            if (row instanceof String) {
                ItemSocialSectionBinding.bind(h.itemView).text.setText((String) row);
                return;
            }
            ConversationEntity c = (ConversationEntity) row;
            ItemChatHistoryBinding r = ItemChatHistoryBinding.bind(h.itemView);
            boolean open = c.id.equals(current);
            r.title.setText(title(c));
            r.time.setText(open ? activity.getString(R.string.chats_open_now, Ui.ago(activity, c.updatedAt)) : Ui.ago(activity, c.updatedAt));
            r.getRoot().setSelected(open);
            r.icon.setImageTintList(activity.getColorStateList(open ? R.color.nl_lime : R.color.nl_text_2));
            r.getRoot().setOnClickListener(x -> {
                dialog.dismiss();
                if (!open) onOpen.accept(c.id);
            });
            r.delete.setContentDescription(activity.getString(R.string.chats_delete_one, title(c)));
            r.delete.setOnClickListener(x -> confirmDelete(c));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
