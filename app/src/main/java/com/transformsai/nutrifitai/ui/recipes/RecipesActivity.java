package com.transformsai.nutrifitai.ui.recipes;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.entity.RecipeEntity;
import com.transformsai.nutrifitai.data.repo.RecipeRepository;
import com.transformsai.nutrifitai.databinding.ActivityRecipesBinding;
import com.transformsai.nutrifitai.databinding.ItemRecipeBinding;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecipesActivity extends BaseActivity {
    private ActivityRecipesBinding b;
    private final List<RecipeEntity> recipes = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        String uid = uidOrFinish();
        if (uid == null) return;
        b = ActivityRecipesBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        b.bar.title.setText(R.string.recipes_title);
        b.bar.back.setOnClickListener(v -> finish());
        b.add.setOnClickListener(v -> startActivity(new Intent(this, RecipeEditActivity.class)));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                return new RecyclerView.ViewHolder(ItemRecipeBinding.inflate(LayoutInflater.from(p.getContext()), p, false).getRoot()) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                RecipeEntity r = recipes.get(pos);
                ItemRecipeBinding rb = ItemRecipeBinding.bind(h.itemView);
                rb.name.setText(r.name);
                rb.thumb.setEmoji("🍲", "curries");
                rb.sub.setText("…");
                graph().recipes.load(r.id, full -> {
                    if (full.value == null || full.value.recipe == null) return;
                    double kcal = RecipeRepository.total(full.value.ingredients).kcal / Math.max(r.servings, 0.01);
                    double g = RecipeRepository.totalGrams(full.value.ingredients) / Math.max(r.servings, 0.01);
                    rb.sub.setText(getString(R.string.recipe_total, Math.round(kcal), Math.round(g)));
                });
                rb.getRoot().setOnClickListener(v -> startActivity(RecipeEditActivity.edit(RecipesActivity.this, r.id)));
                Ui.onClick(rb.log, v -> log(r));
            }

            @Override
            public int getItemCount() {
                return recipes.size();
            }
        };
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);
        graph().recipes.observeAll(uid).observe(this, list -> {
            recipes.clear();
            if (list != null) recipes.addAll(list);
            b.empty.setVisibility(recipes.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
        });
    }

    private void log(RecipeEntity r) {
        String meal = MealLabels.forHour(LocalTime.now().getHour());
        graph().recipes.load(r.id, full -> {
            if (full.value == null || full.value.ingredients.isEmpty()) return;
            new MaterialAlertDialogBuilder(this).setTitle(r.name)
                    .setItems(getResources().getStringArray(R.array.meal_types), (d, w) -> {
                        MealItemEntity item = RecipeRepository.toMealItem(full.value.recipe, full.value.ingredients, 1);
                        String m = MealLabels.TYPES[w];
                        graph().diary.addItems(DateUtil.today(), m, false, "recipe", Collections.singletonList(item), null, null,
                                x -> Ui.snack(b.getRoot(), getString(R.string.added_to, MealLabels.label(this, m))));
                    }).show();
        });
        if (meal == null) return;
    }
}
