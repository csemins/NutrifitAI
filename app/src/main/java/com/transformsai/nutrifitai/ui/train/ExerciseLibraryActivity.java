package com.transformsai.nutrifitai.ui.train;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityExerciseLibraryBinding;
import com.transformsai.nutrifitai.databinding.ItemExerciseBinding;
import com.transformsai.nutrifitai.fitness.Exercise;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.google.android.material.chip.Chip;

import java.util.ArrayList;
import java.util.List;

/** Searchable exercise library (coached exercises first, then the full public-domain library). */
public class ExerciseLibraryActivity extends BaseActivity {
    private static final String[] FILTERS = {"all", "core", "legs", "upper", "cardio"};
    private static final int[] LABELS = {R.string.filter_all, R.string.focus_core, R.string.focus_legs,
            R.string.focus_upper, R.string.filter_cardio};

    private ActivityExerciseLibraryBinding b;
    private ExerciseLibrary lib;
    private String filter = "all";
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityExerciseLibraryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.bar.getRoot(), true, false);
        InsetsUtil.padding(b.list, false, true);
        b.bar.title.setText(R.string.exercise_library);
        b.bar.back.setOnClickListener(v -> finish());
        b.list.setAdapter(adapter);
        for (int i = 0; i < FILTERS.length; i++) {
            Chip c = new Chip(this);
            c.setId(View.generateViewId());
            c.setText(LABELS[i]);
            c.setCheckable(true);
            c.setCheckedIconVisible(false);
            c.setChipBackgroundColorResource(R.color.chip_bg);
            c.setChipStrokeColorResource(R.color.chip_stroke);
            c.setChipStrokeWidth(getResources().getDisplayMetrics().density);
            c.setTextColor(getColorStateList(R.color.chip_text));
            c.setChecked(i == 0);
            final String f = FILTERS[i];
            c.setOnCheckedChangeListener((v, on) -> {
                if (on) {
                    filter = f;
                    refresh();
                }
            });
            b.filters.addView(c);
        }
        b.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int d) { }

            @Override
            public void afterTextChanged(Editable e) {
                refresh();
            }
        });
        graph().runIo(() -> {
            ExerciseLibrary l = ExerciseLibrary.get(this);
            graph().main.post(() -> {
                lib = l;
                refresh();
            });
        });
    }

    private void refresh() {
        if (lib == null) return;
        List<Exercise> r = lib.search(b.search.getText().toString(), filter, 300);
        adapter.set(r);
        b.empty.setVisibility(r.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private final List<Exercise> items = new ArrayList<>();

        @SuppressWarnings("NotifyDataSetChanged")
        void set(List<Exercise> l) {
            items.clear();
            items.addAll(l);
            notifyDataSetChanged();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ItemExerciseBinding eb;

            VH(ItemExerciseBinding eb) {
                super(eb.getRoot());
                this.eb = eb;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(ItemExerciseBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Exercise x = items.get(i);
            h.eb.name.setText(x.name);
            String sub = x.muscles == null || x.muscles.isEmpty() ? "" : x.muscles;
            if (x.equipment != null) sub = sub.isEmpty() ? x.equipment : sub + " · " + x.equipment;
            h.eb.sub.setText(sub);
            h.eb.camera.setVisibility(x.camera ? View.VISIBLE : View.GONE);
            h.eb.image.setExercise(x, true);
            h.eb.getRoot().setContentDescription(x.name + ", " + sub + (x.camera ? ", " + getString(R.string.tracked) : ""));
            h.eb.getRoot().setOnClickListener(v -> startActivity(ExerciseDetailActivity.intent(ExerciseLibraryActivity.this, x.id)));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
