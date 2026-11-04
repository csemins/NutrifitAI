package com.transformsai.nutrifitai.ui.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.ConversationEntity;
import com.transformsai.nutrifitai.ai.AiItem;
import com.transformsai.nutrifitai.ai.AssistantReply;
import com.transformsai.nutrifitai.ai.AssistantReplyParser;
import com.transformsai.nutrifitai.data.db.entity.ChatMessageEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.repo.DiaryRepository;
import com.transformsai.nutrifitai.databinding.ActivityAssistantBinding;
import com.transformsai.nutrifitai.databinding.ItemChatBotBinding;
import com.transformsai.nutrifitai.databinding.ItemChatUserBinding;
import com.transformsai.nutrifitai.databinding.ItemSuggestionBinding;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.common.VoiceInput;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.ui.common.AiAccessSheet;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Nutrition chat. Assistant suggestions are validated structured data and are only written to the
 * diary after an explicit confirmation dialog (planned or eaten, today or tomorrow).
 */
public class AssistantActivity extends BaseActivity {
    private static final String EXTRA_PROMPT = "prompt";
    private static final String EXTRA_SCREEN = "screen";

    public static Intent withPrompt(Context c, String prompt) {
        return new Intent(c, AssistantActivity.class).putExtra(EXTRA_PROMPT, prompt);
    }

    /** Opened from a tab's AI button: the coach knows where the user is and suggests matching questions. */
    public static Intent forScreen(Context c, String screen) {
        return new Intent(c, AssistantActivity.class).putExtra(EXTRA_SCREEN, screen);
    }

    private String screen = "home";
    private VoiceInput voice;
    private final com.transformsai.nutrifitai.ui.common.Permissions.Asker micAsker = new com.transformsai.nutrifitai.ui.common.Permissions.Asker(this, () -> this);

    private ActivityAssistantBinding b;
    private AssistantViewModel vm;
    private final List<ChatMessageEntity> messages = new ArrayList<>();
    private ChatAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityAssistantBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        AiAccessSheet.attach(this);
        InsetsUtil.padding(b.root, true, true);
        b.bar.title.setText(R.string.assistant_title);
        b.bar.back.setOnClickListener(v -> finish());
        b.bar.action.setVisibility(View.VISIBLE);
        b.bar.action.setImageResource(R.drawable.ic_edit);
        b.bar.action.setContentDescription(getString(R.string.new_chat));
        vm = new ViewModelProvider(this).get(AssistantViewModel.class);
        b.bar.action.setOnClickListener(v -> vm.newChat());
        screen = getIntent().getStringExtra(EXTRA_SCREEN) == null ? "home" : getIntent().getStringExtra(EXTRA_SCREEN);
        graph().assistant.setScreen(screen);
        addHistoryButton();
        b.mic.setOnClickListener(v -> toggleVoice());
        if (!VoiceInput.available(this)) b.mic.setVisibility(View.GONE);
        adapter = new ChatAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        b.list.setLayoutManager(lm);
        b.list.setAdapter(adapter);
        for (int q : starters(screen)) {
            Chip c = new Chip(this);
            c.setText(q);
            c.setOnClickListener(v -> send(getString(q)));
            b.starters.addView(c);
        }
        Ui.onClick(b.send, v -> send(b.input.getText().toString()));
        vm.messages.observe(this, list -> {
            messages.clear();
            if (list != null) messages.addAll(list);
            adapter.notifyDataSetChanged();
            b.empty.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
            if (!messages.isEmpty()) b.list.scrollToPosition(messages.size() - 1);
        });
        vm.busy.observe(this, busy -> {
            b.thinking.setVisibility(Boolean.TRUE.equals(busy) ? View.VISIBLE : View.GONE);
            b.send.setEnabled(!Boolean.TRUE.equals(busy));
        });
        vm.error.observe(this, e -> {
            if (e != null) {
                Ui.snack(b.getRoot(), e);
                vm.error.setValue(null);
            }
        });
        String prompt = getIntent().getStringExtra(EXTRA_PROMPT);
        if (s == null && prompt != null) b.input.setText(prompt);
    }

    private static int[] starters(String screen) {
        switch (screen) {
            case "meals": return new int[]{R.string.assistant_q1, R.string.q_meals_2, R.string.assistant_q3, R.string.q_meals_4};
            case "train": return new int[]{R.string.assistant_q4, R.string.q_train_2, R.string.q_train_3, R.string.q_train_4};
            case "progress": return new int[]{R.string.assistant_q2, R.string.q_progress_2, R.string.q_progress_3, R.string.assistant_q5};
            case "profile": return new int[]{R.string.q_profile_1, R.string.q_profile_2, R.string.assistant_q6, R.string.q_profile_4};
            default: return new int[]{R.string.assistant_q1, R.string.q_home_2, R.string.assistant_q3, R.string.assistant_q4,
                    R.string.assistant_q5};
        }
    }

    // ---------------------------------------------------------------- history
    /** Top bar: [back] title [chats] [new chat]. */
    private void addHistoryButton() {
        android.widget.ImageButton h = new android.widget.ImageButton(this, null, 0, R.style.NL_IconButton);
        h.setImageResource(R.drawable.ic_history);
        h.setContentDescription(getString(R.string.chats_title));
        int size = (int) (48 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd((int) (8 * getResources().getDisplayMetrics().density));
        android.view.ViewGroup bar = (android.view.ViewGroup) b.bar.getRoot();
        bar.addView(h, bar.indexOfChild(b.bar.action), lp);
        h.setOnClickListener(v -> showHistory());
    }

    private void showHistory() {
        graph().runIo(() -> {
            List<ConversationEntity> list = graph().assistant.historyBlocking(graph().session.requireUid());
            graph().main.post(() -> {
                if (isFinishing()) return;
                ChatHistorySheet.show(this, list, vm.current(), vm::open, vm::newChat,
                        id -> graph().assistant.deleteConversation(id, r -> {
                            if (id.equals(vm.current())) vm.newChat();
                        }));
            });
        });
    }

    // ---------------------------------------------------------------- voice
    private void toggleVoice() {
        if (voice != null && voice.listening()) {
            voice.stop();
            return;
        }
        micAsker.run(com.transformsai.nutrifitai.ui.common.Permissions.MIC, R.string.perm_why_mic, this::startVoice); // asks again if declined before
    }

    private void startVoice() {
        if (voice == null) voice = new VoiceInput(this, new VoiceInput.Callback() {
            private String before = "";

            @Override
            public void onListening(boolean on) {
                if (b == null) return;
                b.mic.setActivated(on);
                b.mic.setImageTintList(androidx.core.content.ContextCompat.getColorStateList(AssistantActivity.this,
                        on ? R.color.nl_lime : R.color.nl_text));
                b.mic.setContentDescription(getString(on ? R.string.cd_mic_stop : R.string.cd_mic));
                if (on) {
                    before = b.input.getText() == null ? "" : b.input.getText().toString().trim();
                    b.input.setHint(R.string.voice_listening);
                } else b.input.setHint(R.string.assistant_hint);
            }

            @Override
            public void onPartial(String text) {
                if (b != null) b.input.setText(before.isEmpty() ? text : before + " " + text);
            }

            @Override
            public void onResult(String text) {
                if (b == null) return;
                b.input.setText(before.isEmpty() ? text : before + " " + text);
                b.input.setSelection(b.input.getText().length());
            }

            @Override
            public void onError(int message) {
                if (b != null) Ui.snack(b.getRoot(), getString(message));
            }
        });
        voice.start(graph().prefs.speechLanguage());
    }

    @Override
    protected void onStop() {
        if (voice != null) voice.release(); // never keep the microphone in the background
        super.onStop();
    }

    // ---------------------------------------------------------------- agent actions
    private String actionLabel(AssistantReply.Action a) {
        switch (a.type) {
            case "log_water": return getString(R.string.act_water, a.ml);
            case "log_weight": return getString(R.string.act_weight, String.format(java.util.Locale.getDefault(), "%.1f", a.kg));
            case "start_fast": return getString(R.string.act_fast, a.hours);
            case "set_step_goal": return getString(R.string.act_steps, a.steps);
            case "log_food": return getString(R.string.act_food, a.text.length() > 40 ? a.text.substring(0, 39) + "…" : a.text);
            default: return getString(R.string.act_open, screenLabel(a.screen));
        }
    }

    private String screenLabel(String s) {
        switch (s == null ? "" : s) {
            case "hydration": return getString(R.string.hydration);
            case "steps": return getString(R.string.walk_tracker);
            case "fasting": return getString(R.string.fasting_title);
            case "progress": return getString(R.string.nav_progress);
            case "train": return getString(R.string.nav_train);
            case "meals": return getString(R.string.nav_meals);
            case "weight": return getString(R.string.weight);
            case "recipes": return getString(R.string.recipes);
            case "ai_workout": return getString(R.string.ai_workout_title);
            case "achievements": return getString(R.string.achievements);
            default: return getString(R.string.scan_food);
        }
    }

    private void addActions(ItemChatBotBinding bb, List<AssistantReply.Action> actions) {
        if (actions.isEmpty()) return;
        com.google.android.material.chip.ChipGroup group = new com.google.android.material.chip.ChipGroup(this);
        group.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        for (AssistantReply.Action a : actions) {
            Chip c = new Chip(this);
            c.setText(actionLabel(a));
            c.setChipIconResource(R.drawable.ic_sparkle);
            c.setChipIconTintResource(R.color.nl_on_lime);
            c.setChipBackgroundColorResource(R.color.nl_lime);
            c.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.nl_on_lime));
            c.setOnClickListener(v -> confirmAction(a));
            group.addView(c);
        }
        bb.suggestions.addView(group, 0);
    }

    /** Every action is confirmed by the user before it changes anything. */
    private void confirmAction(AssistantReply.Action a) {
        if ("open".equals(a.type) || "log_food".equals(a.type)) {
            runAction(a);
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle(actionLabel(a)).setMessage(R.string.act_confirm)
                .setPositiveButton(R.string.confirm, (d, w) -> runAction(a))
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void runAction(AssistantReply.Action a) {
        switch (a.type) {
            case "log_water":
                graph().water.add(a.ml, r -> Ui.snack(b.getRoot(), getString(R.string.act_done_water, a.ml)));
                break;
            case "log_weight":
                graph().weights.save(null, DateUtil.today(), a.kg, null);
                Ui.snack(b.getRoot(), getString(R.string.act_done));
                break;
            case "start_fast":
                graph().fasting.start(a.hours, System.currentTimeMillis(), r -> {
                    if (r.ok()) com.transformsai.nutrifitai.ui.fasting.FastDoneWorker.schedule(this, r.value);
                    Ui.snack(b.getRoot(), getString(R.string.act_done));
                });
                break;
            case "set_step_goal":
                graph().prefs.setStepGoal(a.steps);
                Ui.snack(b.getRoot(), getString(R.string.act_done));
                break;
            case "log_food":
                // the same AI food analysis as voice logging: the user reviews every item before saving
                graph().analysis.createText(a.text, a.mealType, DateUtil.today(),
                        r -> startActivity(com.transformsai.nutrifitai.ui.analysis.AnalysisActivity.intent(this, r.value)));
                break;
            default:
                openScreen(a.screen);
                break;
        }
    }

    private void openScreen(String s) {
        Class<?> target;
        switch (s == null ? "" : s) {
            case "hydration": target = com.transformsai.nutrifitai.ui.hydration.HydrationActivity.class; break;
            case "steps": target = com.transformsai.nutrifitai.ui.steps.StepsActivity.class; break;
            case "fasting": target = com.transformsai.nutrifitai.ui.fasting.FastingActivity.class; break;
            case "weight": target = com.transformsai.nutrifitai.ui.weight.WeightActivity.class; break;
            case "recipes": target = com.transformsai.nutrifitai.ui.recipes.RecipesActivity.class; break;
            case "ai_workout": target = com.transformsai.nutrifitai.ui.train.AiWorkoutActivity.class; break;
            case "achievements": target = com.transformsai.nutrifitai.ui.achievements.AchievementsActivity.class; break;
            case "scan": target = com.transformsai.nutrifitai.ui.camera.CameraActivity.class; break;
            default: {
                int tab = "train".equals(s) ? com.transformsai.nutrifitai.ui.main.MainActivity.TAB_TRAIN
                        : "meals".equals(s) ? com.transformsai.nutrifitai.ui.main.MainActivity.TAB_MEALS
                        : com.transformsai.nutrifitai.ui.main.MainActivity.TAB_PROGRESS;
                startActivity(new Intent(this, com.transformsai.nutrifitai.ui.main.MainActivity.class)
                        .putExtra(com.transformsai.nutrifitai.ui.main.MainActivity.EXTRA_TAB, tab)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                return;
            }
        }
        startActivity(new Intent(this, target));
    }

    private void send(String text) {
        String t = text.trim();
        if (t.isEmpty()) return;
        b.input.setText("");
        vm.send(t);
    }

    private void confirm(AssistantReply.Suggestion s) {
        String meal = MealLabels.label(this, s.mealType);
        String[] days = {getString(R.string.today), getString(R.string.tomorrow)};
        new MaterialAlertDialogBuilder(this).setTitle(s.title)
                .setItems(days, (d, which) -> {
                    String date = which == 0 ? DateUtil.today() : DateUtil.shift(DateUtil.today(), 1);
                    String msg = getString(R.string.confirm_suggestion, s.title, Math.round(s.kcal()), meal, days[which]);
                    MaterialAlertDialogBuilder c = new MaterialAlertDialogBuilder(this).setMessage(msg)
                            .setPositiveButton(R.string.as_planned, (x, y) -> add(s, date, true))
                            .setNegativeButton(R.string.cancel, null);
                    if (which == 0) c.setNeutralButton(R.string.as_eaten, (x, y) -> add(s, date, false));
                    c.show();
                }).show();
    }

    private void add(AssistantReply.Suggestion s, String date, boolean planned) {
        com.transformsai.nutrifitai.ai.AiResult r = new com.transformsai.nutrifitai.ai.AiResult();
        r.items = s.items;
        List<MealItemEntity> items = DiaryRepository.itemsFromAi(r);
        graph().diary.addItems(date, s.mealType, planned, "assistant", items, null, null,
                x -> Ui.snack(b.getRoot(), getString(R.string.added_to, MealLabels.label(this, s.mealType))));
    }

    /** A workout the coach suggested: shown with its exercises, saved as a plan only when the user taps. */
    private void addWorkoutCard(ItemChatBotBinding bb, String json) {
        com.transformsai.nutrifitai.fitness.ExerciseLibrary lib = com.transformsai.nutrifitai.fitness.ExerciseLibrary.get(this);
        com.transformsai.nutrifitai.fitness.WorkoutPlan p;
        try {
            p = com.transformsai.nutrifitai.data.repo.WorkoutRepository.parseAiPlan(json, lib);
        } catch (Exception e) {
            return;
        }
        ItemSuggestionBinding sb = ItemSuggestionBinding.inflate(getLayoutInflater(), bb.suggestions, false);
        sb.title.setText(getString(R.string.coach_workout_title, p.title));
        sb.kcal.setText(getString(R.string.chip_minutes, Math.max(1, Math.round(p.estimatedSeconds(lib) / 60f))));
        List<String> names = new ArrayList<>();
        for (com.transformsai.nutrifitai.fitness.WorkoutPlan.Step st : p.steps) {
            com.transformsai.nutrifitai.fitness.Exercise x = lib.byId(st.exerciseId);
            if (x != null) names.add(x.name + " " + (x.isHold() ? st.target + " s" : "×" + st.target));
        }
        sb.items.setText(String.join(", ", names));
        sb.why.setText(com.transformsai.nutrifitai.fitness.Muscles.summary(com.transformsai.nutrifitai.fitness.Muscles.planned(p, lib), 3).isEmpty()
                ? p.summary : getString(R.string.targets_muscles, com.transformsai.nutrifitai.fitness.Muscles.summary(
                com.transformsai.nutrifitai.fitness.Muscles.planned(p, lib), 3)));
        sb.add.setText(R.string.coach_save_workout);
        sb.add.setIconResource(R.drawable.ic_play);
        sb.add.setOnClickListener(v -> new MaterialAlertDialogBuilder(this).setTitle(p.title)
                .setMessage(R.string.coach_save_workout_q)
                .setPositiveButton(R.string.save, (d, w) -> graph().workouts.saveSuggestedPlan(json, res -> {
                    if (res.ok()) startActivity(com.transformsai.nutrifitai.ui.train.WorkoutDetailActivity.intent(this, res.value.id));
                    else Ui.snack(b.getRoot(), res.error);
                }))
                .setNegativeButton(R.string.cancel, null).show());
        bb.suggestions.addView(sb.getRoot());
    }

    final class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int p) {
            return ChatMessageEntity.ROLE_USER.equals(messages.get(p).role) ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int t) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            View v = t == 0 ? ItemChatUserBinding.inflate(inf, parent, false).getRoot() : ItemChatBotBinding.inflate(inf, parent, false).getRoot();
            return new RecyclerView.ViewHolder(v) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int p) {
            ChatMessageEntity m = messages.get(p);
            if (getItemViewType(p) == 0) {
                ItemChatUserBinding ub = ItemChatUserBinding.bind(h.itemView);
                ub.text.setText(m.text);
                boolean failed = "error".equals(m.status);
                ub.status.setVisibility(failed ? View.VISIBLE : View.GONE);
                ub.status.setText(R.string.assistant_failed);
                View.OnClickListener retry = failed ? v -> vm.retry(m.id) : null;
                ub.getRoot().setOnClickListener(retry);
                ub.status.setOnClickListener(retry); // the bubble text is selectable and keeps its own taps
                ub.status.setMinHeight(failed ? (int) (40 * getResources().getDisplayMetrics().density) : 0);
                return;
            }
            ItemChatBotBinding bb = ItemChatBotBinding.bind(h.itemView);
            bb.text.setText(m.text);
            bb.suggestions.removeAllViews();
            bb.sources.setVisibility(View.GONE);
            if (m.suggestionsJson == null) return;
            try {
                AssistantReply r = AssistantReplyParser.parse(m.suggestionsJson);
                if (r.workoutJson != null) addWorkoutCard(bb, r.workoutJson);
                addActions(bb, r.actions);
                for (AssistantReply.Suggestion s : r.suggestions) {
                    ItemSuggestionBinding sb = ItemSuggestionBinding.inflate(getLayoutInflater(), bb.suggestions, false);
                    sb.title.setText(s.title);
                    sb.kcal.setText("~" + Math.round(s.kcal()) + " kcal");
                    List<String> names = new ArrayList<>();
                    for (AiItem i : s.items) names.add(i.name + " (" + Math.round(i.weightG) + " g)");
                    sb.items.setText(MealLabels.label(AssistantActivity.this, s.mealType) + " · " + String.join(", ", names));
                    sb.why.setText(s.rationale);
                    sb.why.setVisibility(s.rationale == null || s.rationale.isEmpty() ? View.GONE : View.VISIBLE);
                    sb.add.setOnClickListener(v -> confirm(s));
                    bb.suggestions.addView(sb.getRoot());
                }
            } catch (Exception ignored) {
                // stored suggestions were validated when received; ignore if format changed
            }
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }
    }
}
