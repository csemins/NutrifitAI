package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityNewChallengeBinding;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.fitness.Programs;
import com.transformsai.nutrifitai.fitness.WorkoutPlan;
import com.transformsai.nutrifitai.social.SocialModels.Person;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Challenge friends (1-7, or a whole friend group) to a camera-tracked workout: a quick single-move duel or a full program. */
public class NewChallengeActivity extends BaseActivity {
    private static final String EXTRA_FRIEND = "friend", EXTRA_GROUP = "group";
    /** Quick duels: exercise id and target (reps, or seconds for holds). All are camera-tracked. */
    private static final Object[][] QUICK = {{"squat", 20}, {"pushup", 15}, {"jumping_jack", 30}, {"lunge", 16},
            {"burpee", 10}, {"mountain_climber", 30}, {"plank", 60}, {"wall_sit", 45}};
    private static final int MAX_FRIENDS = 7;

    /** {@code groupId}: a friend group whose members are preselected; the challenge is posted in its chat. */
    public static Intent intent(Context c, @Nullable String friendUid, @Nullable String groupId) {
        return new Intent(c, NewChallengeActivity.class).putExtra(EXTRA_FRIEND, friendUid).putExtra(EXTRA_GROUP, groupId);
    }

    private ActivityNewChallengeBinding b;
    private final List<Person> friends = new ArrayList<>();
    /** Chip view id -> routine. */
    private final Map<Integer, WorkoutPlan> choices = new HashMap<>();
    private ExerciseLibrary lib;
    private boolean creating;
    @Nullable private String groupId;
    @Nullable private com.transformsai.nutrifitai.social.SocialModels.Chat group;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityNewChallengeBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, false);
        InsetsUtil.padding(b.bottom, false, true);
        b.bar.title.setText(R.string.challenge_new);
        b.bar.back.setOnClickListener(v -> finish());
        String preselect = getIntent().getStringExtra(EXTRA_FRIEND);
        groupId = getIntent().getStringExtra(EXTRA_GROUP);
        if (groupId != null) {
            graph().chats.chatLive(groupId).observe(this, c -> {
                group = c;
                if (c == null) return;
                b.bar.title.setText(getString(R.string.challenge_group_title, c.displayName(graph().session.uid())));
                for (int i = 0; i < b.friends.getChildCount() && i < friends.size(); i++) {
                    ((Chip) b.friends.getChildAt(i)).setChecked(c.members.contains(friends.get(i).uid));
                }
            });
        }
        graph().runIo(() -> {
            lib = ExerciseLibrary.get(this);
            graph().main.post(this::buildRoutines);
        });
        graph().friends.friends().observe(this, l -> {
            friends.clear();
            friends.addAll(l);
            b.friends.removeAllViews();
            for (int i = 0; i < l.size(); i++) {
                Chip c = chip(b.friends, l.get(i).name, View.generateViewId());
                c.setChecked(l.get(i).uid.equals(preselect) || (group != null && group.members.contains(l.get(i).uid)));
                c.setOnCheckedChangeListener((x, on) -> update());
            }
            b.noFriends.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
            update();
        });
        b.quick.setOnCheckedStateChangeListener((g, ids) -> {
            if (!ids.isEmpty()) {
                b.programs.clearCheck();
                setCustomActive(false);
            }
            update();
        });
        b.programs.setOnCheckedStateChangeListener((g, ids) -> {
            if (!ids.isEmpty()) {
                b.quick.clearCheck();
                setCustomActive(false);
            }
            update();
        });
        b.addExercise.setOnClickListener(v -> addCustomStep());
        b.customCard.setOnClickListener(v -> {
            if (!custom.isEmpty()) setCustomActive(true);
        });
        renderCustom();
        Ui.onClick(b.create, v -> create());
    }

    private Chip chip(ChipGroup group, String text, int id) {
        Chip c = (Chip) getLayoutInflater().inflate(R.layout.view_choice_chip, group, false);
        c.setId(id);
        c.setText(text);
        group.addView(c);
        return c;
    }

    /** Quick duels, then programs whose every step the camera can score. */
    private void buildRoutines() {
        choices.clear();
        int first = View.NO_ID;
        for (Object[] q : QUICK) {
            Exercise x = lib.byId((String) q[0]);
            if (x == null) continue;
            WorkoutPlan p = new WorkoutPlan();
            int target = (Integer) q[1];
            p.title = getString(x.isHold() ? R.string.challenge_hold_title : R.string.challenge_reps_title, x.name, target);
            p.add(x.id, target, 0);
            int id = View.generateViewId();
            choices.put(id, p);
            chip(b.quick, p.title, id);
            if (first == View.NO_ID) first = id;
        }
        for (WorkoutPlan p : Programs.all()) {
            if (p.cameraSteps(lib) != p.steps.size()) continue; // every step must be scorable by the camera
            int id = View.generateViewId();
            choices.put(id, p);
            chip(b.programs, getString(R.string.challenge_program_chip, p.title, p.steps.size()), id);
        }
        // my own saved plans (AI builder or programs I saved) whose every move the camera can score
        String uid = graph().session.uid();
        if (uid != null) {
            graph().workouts.observePlans(uid).observe(this, plans -> {
                if (plans == null) return;
                for (com.transformsai.nutrifitai.data.db.entity.WorkoutPlanEntity e : plans) {
                    if (savedPlanIds.contains(e.id)) continue;
                    WorkoutPlan wp = new WorkoutPlan();
                    WorkoutPlan.parseSteps(e.stepsJson, wp, lib);
                    if (wp.steps.isEmpty() || wp.cameraSteps(lib) != wp.steps.size()) continue;
                    wp.title = e.title;
                    savedPlanIds.add(e.id);
                    int id = View.generateViewId();
                    choices.put(id, wp);
                    chip(b.programs, getString(R.string.challenge_my_plan, getString(R.string.challenge_program_chip, e.title, wp.steps.size())), id);
                }
            });
        }
        if (first != View.NO_ID) b.quick.check(first);
    }

    // ---------------------------------------------------------------- custom workout
    /** One custom line: a camera-tracked move, reps (or seconds for holds), and how many sets. */
    private static final class CustomStep {
        final Exercise x;
        final int target, sets;

        CustomStep(Exercise x, int target, int sets) {
            this.x = x;
            this.target = target;
            this.sets = sets;
        }
    }

    private static final int MAX_SETS = 20, REST_BETWEEN = 20;
    private final List<CustomStep> custom = new ArrayList<>();
    private final java.util.Set<String> savedPlanIds = new java.util.HashSet<>();
    private boolean customActive;

    private int customSets() {
        int n = 0;
        for (CustomStep c : custom) n += c.sets;
        return n;
    }

    private void setCustomActive(boolean on) {
        customActive = on && !custom.isEmpty();
        if (customActive) {
            b.quick.clearCheck();
            b.programs.clearCheck();
        }
        b.customCard.setBackgroundResource(customActive ? R.drawable.bg_card_neon : R.drawable.bg_card);
        update();
    }

    private void renderCustom() {
        b.customSteps.removeAllViews();
        b.customEmpty.setVisibility(custom.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < custom.size(); i++) {
            CustomStep c = custom.get(i);
            com.transformsai.nutrifitai.databinding.ItemSocialRowBinding row =
                    com.transformsai.nutrifitai.databinding.ItemSocialRowBinding.inflate(getLayoutInflater(), b.customSteps, false);
            row.getRoot().setBackground(null);
            row.getRoot().setPadding(0, 0, 0, 0);
            row.avatar.setEmoji(String.valueOf(i + 1), "vegetables");
            row.title.setText(c.x.name);
            String amount = getString(c.x.isHold() ? R.string.challenge_custom_secs : R.string.challenge_custom_reps, c.target);
            row.subtitle.setText(c.sets > 1 ? getString(R.string.challenge_custom_row, amount, c.sets) : amount);
            row.secondary.setVisibility(View.VISIBLE);
            row.secondary.setImageResource(R.drawable.ic_close);
            row.secondary.setContentDescription(getString(R.string.remove) + " " + c.x.name);
            final int idx = i;
            row.secondary.setOnClickListener(v -> {
                custom.remove(idx);
                renderCustom();
                setCustomActive(!custom.isEmpty() && customActive);
            });
            b.customSteps.addView(row.getRoot());
        }
    }

    private void addCustomStep() {
        if (lib == null) return;
        if (customSets() >= MAX_SETS) {
            Ui.snack(b.getRoot(), getString(R.string.challenge_custom_max, MAX_SETS));
            return;
        }
        List<Exercise> moves = new ArrayList<>(lib.cameraExercises());
        moves.sort((a, c) -> a.name.compareToIgnoreCase(c.name));
        String[] names = new String[moves.size()];
        for (int i = 0; i < names.length; i++) names[i] = moves.get(i).name;
        com.transformsai.nutrifitai.databinding.DialogCustomStepBinding d =
                com.transformsai.nutrifitai.databinding.DialogCustomStepBinding.inflate(getLayoutInflater());
        d.exercise.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        Exercise[] picked = {null};
        d.exercise.setOnItemClickListener((parent, view, pos, id) -> {
            String name = (String) parent.getItemAtPosition(pos);
            for (Exercise x : moves) if (x.name.equals(name)) picked[0] = x;
            if (picked[0] == null) return;
            boolean hold = picked[0].isHold();
            d.targetLayout.setHint(hold ? R.string.challenge_seconds : R.string.challenge_reps);
            d.target.setText(String.valueOf(hold ? 30 : 15));
            d.exerciseLayout.setError(null);
        });
        androidx.appcompat.app.AlertDialog dlg = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.challenge_add_exercise).setView(d.getRoot())
                .setPositiveButton(R.string.add, null).setNegativeButton(R.string.cancel, null).show();
        dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (picked[0] == null) {
                d.exerciseLayout.setError(getString(R.string.challenge_pick_exercise));
                return;
            }
            boolean hold = picked[0].isHold();
            int min = hold ? 10 : 1, max = hold ? 300 : 200;
            double t = Ui.parse(d.target.getText()), n = Ui.parse(d.sets.getText());
            boolean ok = true;
            if (Double.isNaN(t) || t < min || t > max) {
                d.targetLayout.setError(getString(R.string.challenge_target_range, min, max));
                ok = false;
            } else d.targetLayout.setError(null);
            int room = MAX_SETS - customSets();
            if (Double.isNaN(n) || n < 1 || n > Math.min(5, room)) {
                d.setsLayout.setError(getString(R.string.challenge_target_range, 1, Math.min(5, room)));
                ok = false;
            } else d.setsLayout.setError(null);
            if (!ok) return;
            custom.add(new CustomStep(picked[0], (int) t, (int) n));
            renderCustom();
            setCustomActive(true);
            dlg.dismiss();
        });
    }

    /** The custom list as a challenge routine: each set is a step, with a short rest between. */
    private WorkoutPlan customPlan() {
        WorkoutPlan p = new WorkoutPlan();
        List<String> parts = new ArrayList<>();
        for (CustomStep c : custom) {
            for (int i = 0; i < c.sets; i++) p.add(c.x.id, c.target, REST_BETWEEN);
            parts.add(c.x.name + (c.sets > 1 ? " ×" + c.sets : ""));
        }
        p.last();
        String title = getString(R.string.challenge_custom_title, String.join(", ", parts));
        p.title = title.length() > 60 ? title.substring(0, 59) + "…" : title;
        return p;
    }

    @Nullable
    private WorkoutPlan selected() {
        if (customActive && !custom.isEmpty()) return customPlan();
        int id = b.quick.getCheckedChipId() != View.NO_ID ? b.quick.getCheckedChipId() : b.programs.getCheckedChipId();
        return choices.get(id);
    }

    private List<Person> selectedFriends() {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < b.friends.getChildCount() && i < friends.size(); i++) {
            if (((Chip) b.friends.getChildAt(i)).isChecked()) out.add(friends.get(i));
        }
        return out;
    }

    private void update() {
        int n = selectedFriends().size();
        b.create.setEnabled(!creating && n >= 1 && n <= MAX_FRIENDS && selected() != null);
        b.create.setText(n > MAX_FRIENDS ? getString(R.string.challenge_too_many, MAX_FRIENDS) : getString(R.string.challenge_send));
    }

    private void create() {
        WorkoutPlan p = selected();
        List<Person> who = selectedFriends();
        if (p == null || who.isEmpty() || creating) return;
        creating = true;
        update();
        graph().challenges.create(p.title, p.stepsJson(), who, groupId, r -> {
            creating = false;
            if (!r.ok()) {
                update();
                Ui.snack(b.getRoot(), r.error);
                return;
            }
            if (group != null) graph().chats.send(group, r.value.title, com.transformsai.nutrifitai.social.SocialModels.Message.CHALLENGE, r.value.id);
            startActivity(ChallengeLobbyActivity.intent(this, r.value.id));
            finish();
        });
    }
}
