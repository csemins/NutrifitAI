package com.transformsai.nutrifitai.ui.main;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.entity.MealItemEntity;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.databinding.FragmentMealsBinding;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.ui.assistant.AssistantActivity;
import com.transformsai.nutrifitai.ui.common.DiaryActions;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.food.FoodDetailActivity;
import com.transformsai.nutrifitai.ui.food.FoodSearchActivity;
import com.transformsai.nutrifitai.util.DateUtil;

import java.time.LocalTime;

/** Meal diary for a selected date: breakfast, lunch, dinner, snacks; planned vs consumed. */
public class MealsFragment extends Fragment implements MainActivity.ScrollToTop, DiaryAdapter.Listener {
    private FragmentMealsBinding b;
    private MealsViewModel vm;
    private DiaryAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentMealsBinding.inflate(inf, c, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        InsetsUtil.padding(b.header, true, false);
        vm = new ViewModelProvider(this).get(MealsViewModel.class);
        adapter = new DiaryAdapter(this);
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);
        b.list.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        b.days.setOnSelectListener(i -> vm.setDate(DateUtil.shift(DateUtil.today(), i - 1)));
        b.top.overline.setText(R.string.nav_meals);
        b.top.action1.setVisibility(View.VISIBLE);
        b.top.action1.setImageResource(R.drawable.ic_calendar);
        b.top.action1.setContentDescription(getString(R.string.pick_date));
        b.top.action1.setOnClickListener(x -> DiaryActions.pickDate(this, vm.getDate(), vm::setDate));
        b.top.action2.setVisibility(View.VISIBLE);
        b.top.action2.setImageResource(R.drawable.ic_add);
        b.top.action2.setContentDescription(getString(R.string.add_food));
        Ui.onClick(b.top.action2, x -> onAdd(MealLabels.forHour(LocalTime.now().getHour())));
        b.top.avatar.bind(getViewLifecycleOwner());
        b.top.avatar.setOnClickListener(x -> ((MainActivity) requireActivity()).select(MainActivity.TAB_PROFILE));
        vm.date.observe(getViewLifecycleOwner(), this::renderDate);
        vm.rows.observe(getViewLifecycleOwner(), rows -> {
            adapter.submitList(rows);
            renderSummary();
        });
        graph().today.observe().observe(getViewLifecycleOwner(), t -> renderDate(vm.getDate()));
    }

    private com.transformsai.nutrifitai.data.AppGraph graph() {
        return com.transformsai.nutrifitai.App.graph();
    }

    private void renderDate(String d) {
        long diff = DateUtil.daysBetween(DateUtil.today(), d);
        if (diff >= -1 && diff <= 1) b.days.select((int) diff + 1, false);
        else b.days.select(-1, false);
        if (diff == 0) b.top.title.setText(R.string.todays_meals);
        else if (diff == -1) b.top.title.setText(R.string.yesterdays_meals);
        else if (diff == 1) b.top.title.setText(R.string.tomorrows_plan);
        else b.top.title.setText(DateUtil.dayShort(d) + ", " + DateUtil.monthDay(d));
        adapter.setFutureDay(diff > 0);
        adapter.notifyDataSetChanged();
        renderSummary();
    }

    private void renderSummary() {
        b.summary.setText(vm.plannedKcal > 0
                ? getString(R.string.eaten_planned, Math.round(vm.consumedKcal), Math.round(vm.plannedKcal))
                : getString(R.string.eaten_only, Math.round(vm.consumedKcal)));
    }

    @Override
    public void onItem(ItemRow r) {
        startActivity(FoodDetailActivity.forItem(requireContext(), r.item.id));
    }

    @Override
    public void onItemMore(ItemRow r) {
        DiaryActions.showItemSheet(this, r, b.getRoot());
    }

    @Override
    public void onStep(ItemRow r, int dir) {
        MealItemEntity i = r.item;
        double step = "g".equals(i.servingLabel) ? 10 : (i.servings <= 1 && dir < 0) || (i.servings < 1 && dir > 0) ? 0.25 : 0.5;
        double min = "g".equals(i.servingLabel) ? 1 : 0.25;
        double next = Math.round((i.servings + dir * step) * 100) / 100.0;
        if (next < min) {
            DiaryActions.confirmDelete(this, r, b.getRoot());
            return;
        }
        if (next > ("g".equals(i.servingLabel) ? 3000 : 50)) return;
        Ui.haptic(b.list);
        graph().diary.updatePortion(i.id, next, i.servingLabel, i.servingGrams);
    }

    @Override
    public void onEat(ItemRow r) {
        graph().diary.markEaten(r.item.id, res -> {
            if (!res.ok()) Ui.snack(b.getRoot(), getString(R.string.cant_eat_future));
        });
    }

    @Override
    public void onAdd(String mealType) {
        String d = vm.getDate();
        startActivity(FoodSearchActivity.forMeal(requireContext(), d, mealType, DateUtil.isFuture(d)));
    }

    @Override
    public void onCoach(String mealType) {
        startActivity(AssistantActivity.withPrompt(requireContext(),
                "Suggest a " + MealLabels.label(requireContext(), mealType).toLowerCase() + " idea that fits my remaining targets."));
    }

    @Override
    public void onMealMore(String mealType) {
        DiaryActions.copyMeal(this, vm.getDate(), mealType, b.getRoot());
    }

    @Override
    public void scrollToTop() {
        if (b != null) b.list.smoothScrollToPosition(0);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }

    @SuppressWarnings("unused")
    private static boolean planned(ItemRow r) {
        return MealEntryEntity.STATUS_PLANNED.equals(r.status);
    }
}
