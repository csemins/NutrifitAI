package com.transformsai.nutrifitai.ui.food;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.model.FrequentFood;
import com.transformsai.nutrifitai.data.repo.DiaryRepository;
import com.transformsai.nutrifitai.databinding.ActivityFoodSearchBinding;
import com.transformsai.nutrifitai.databinding.ItemSearchFooterBinding;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.android.material.chip.Chip;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;

/** Offline food search with English/Bangla aliases, filters, favourites, recents and custom foods. */
public class FoodSearchActivity extends BaseActivity implements FoodAdapter.Listener {
    public static final String EXTRA_DATE = "date";
    public static final String EXTRA_MEAL = "meal";
    public static final String EXTRA_PLANNED = "planned";
    public static final String EXTRA_FILTER = "filter";
    public static final String EXTRA_PICK = "pick";
    public static final String RESULT_FOOD_ID = "food_id";

    private static final String[] FILTERS = {"all", "favorites", "recent", "bd", "custom", "grains", "curries", "lentils",
            "protein", "vegetables", "fruits", "dairy", "drinks", "snacks", "sweets"};
    private static final int[] FILTER_LABELS = {R.string.filter_all, R.string.filter_fav, R.string.filter_recent, R.string.filter_bd,
            R.string.filter_custom, R.string.cat_grains, R.string.cat_curries, R.string.cat_lentils, R.string.cat_protein,
            R.string.cat_vegetables, R.string.cat_fruits, R.string.cat_dairy, R.string.cat_drinks, R.string.cat_snacks, R.string.cat_sweets};

    public static Intent forMeal(Context c, String date, String meal, boolean planned) {
        return new Intent(c, FoodSearchActivity.class).putExtra(EXTRA_DATE, date).putExtra(EXTRA_MEAL, meal).putExtra(EXTRA_PLANNED, planned);
    }

    public static Intent forFilter(Context c, String filter) {
        return new Intent(c, FoodSearchActivity.class).putExtra(EXTRA_FILTER, filter);
    }

    public static Intent pick(Context c) {
        return new Intent(c, FoodSearchActivity.class).putExtra(EXTRA_PICK, true);
    }

    private ActivityFoodSearchBinding b;
    private FoodSearchViewModel vm;
    private FoodAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable search = () -> vm.setQuery(b.query.getText().toString());
    private String date;
    private String meal;
    private boolean planned;
    private boolean pick;

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        if (uidOrFinish() == null) return;
        b = ActivityFoodSearchBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        vm = new ViewModelProvider(this).get(FoodSearchViewModel.class);
        pick = getIntent().getBooleanExtra(EXTRA_PICK, false);
        date = getIntent().getStringExtra(EXTRA_DATE);
        if (date == null) date = DateUtil.today();
        meal = getIntent().getStringExtra(EXTRA_MEAL);
        if (meal == null) meal = MealLabels.forHour(LocalTime.now().getHour());
        planned = getIntent().getBooleanExtra(EXTRA_PLANNED, false);
        b.context.setText(pick ? getString(R.string.add_ingredient)
                : (planned ? getString(R.string.add_to_plan, MealLabels.label(this, meal)) : getString(R.string.add_to_meal, MealLabels.label(this, meal)))
                + (date.equals(DateUtil.today()) ? "" : " · " + DateUtil.monthDay(date)));

        adapter = new FoodAdapter(this);
        adapter.setShowAdd(!pick);
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);
        b.back.setOnClickListener(v -> finish());
        b.clear.setOnClickListener(v -> b.query.setText(""));
        String initialFilter = getIntent().getStringExtra(EXTRA_FILTER);
        if (s == null && initialFilter != null) vm.setFilter(initialFilter);
        for (int i = 0; i < FILTERS.length; i++) {
            Chip c = new Chip(this);
            c.setText(FILTER_LABELS[i]);
            c.setCheckable(true);
            c.setId(View.generateViewId());
            c.setChecked(FILTERS[i].equals(vm.filter()));
            final String f = FILTERS[i];
            c.setOnCheckedChangeListener((x, on) -> {
                if (on) vm.setFilter(f);
            });
            b.filters.addView(c);
        }
        b.query.setText(vm.query());
        b.query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s1, int a, int c, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s1, int a, int c, int d) {
                b.clear.setVisibility(s1.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable e) {
                handler.removeCallbacks(search);
                handler.postDelayed(search, 180);
            }
        });
        vm.results().observe(this, list -> adapter.submit(label(), list, vm.favorites));
        vm.frequent().observe(this, f -> adapter.notifyItemChanged(adapter.getItemCount() - 1));
        vm.run();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (vm != null) vm.run();
    }

    private String label() {
        if (!vm.query().isEmpty()) return getString(R.string.results);
        for (int i = 0; i < FILTERS.length; i++) if (FILTERS[i].equals(vm.filter())) return getString(FILTER_LABELS[i]);
        return getString(R.string.recent_favourites);
    }

    @Override
    public void onOpen(FoodEntity f) {
        if (pick) {
            setResult(Activity.RESULT_OK, new Intent().putExtra(RESULT_FOOD_ID, f.id));
            finish();
            return;
        }
        startActivity(FoodDetailActivity.forFood(this, f.id, date, meal, planned));
    }

    @Override
    public void onQuickAdd(FoodEntity f, View v) {
        MealItemEntity item = DiaryRepository.itemFromFood(f, 1, f.servingLabel == null ? "100 g" : f.servingLabel, f.servingGrams);
        v.setEnabled(false);
        Ui.haptic(v);
        graph().diary.addItems(date, meal, planned, "manual", Collections.singletonList(item), null, null, r -> {
            v.setEnabled(true);
            Ui.snack(b.getRoot(), getString(R.string.added_to, MealLabels.label(this, meal)));
        });
    }

    @Override
    public void onFooter(ItemSearchFooterBinding fb) {
        List<FoodEntity> res = vm.results().getValue();
        boolean empty = res == null || res.isEmpty();
        String msg = "";
        if (empty && !vm.query().isEmpty()) msg = getString(R.string.search_empty, vm.query());
        else if (empty && "favorites".equals(vm.filter())) msg = getString(R.string.no_favourites);
        else if (empty && "recent".equals(vm.filter())) msg = getString(R.string.no_recent);
        fb.message.setText(msg);
        fb.message.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
        fb.frequent.removeAllViews();
        List<FrequentFood> freq = vm.frequent().getValue();
        if (vm.query().isEmpty() && freq != null && !freq.isEmpty() && !pick) {
            fb.message.setVisibility(View.VISIBLE);
            fb.message.setText(R.string.frequent);
            for (FrequentFood ff : freq) {
                Chip c = new Chip(this);
                c.setText(ff.name);
                c.setOnClickListener(x -> startActivity(FoodDetailActivity.forFood(this, ff.foodId, date, meal, planned)));
                fb.frequent.addView(c);
            }
        }
        fb.create.setOnClickListener(x -> startActivity(CustomFoodActivity.create(this, vm.query())));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(search);
        super.onDestroy();
    }
}
