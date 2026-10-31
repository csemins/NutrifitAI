package com.transformsai.nutrifitai.ui.main;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.MealEntryEntity;
import com.transformsai.nutrifitai.data.db.model.ItemRow;
import com.transformsai.nutrifitai.domain.NutritionMath;
import com.transformsai.nutrifitai.reminders.MealLabels;
import com.transformsai.nutrifitai.util.DateUtil;

import java.util.ArrayList;
import java.util.List;

/** Selected diary date (saved across process death) and the flattened rows for that date. */
public class MealsViewModel extends ViewModel {
    private final AppGraph g = App.graph();
    private final String uid = g.session.requireUid();
    private final SavedStateHandle state;
    public final LiveData<String> date;
    public final LiveData<List<ItemRow>> items;
    public final MediatorLiveData<List<DiaryRow>> rows = new MediatorLiveData<>();
    public double consumedKcal;
    public double plannedKcal;

    public MealsViewModel(SavedStateHandle h) {
        state = h;
        if (!h.contains("date")) h.set("date", DateUtil.today());
        date = h.getLiveData("date");
        items = Transformations.switchMap(date, d -> g.diary.observeDay(uid, d));
        rows.addSource(items, this::build);
    }

    public void setDate(String d) {
        state.set("date", d);
    }

    public String getDate() {
        String d = state.get("date");
        return d == null ? DateUtil.today() : d;
    }

    private void build(List<ItemRow> list) {
        List<DiaryRow> out = new ArrayList<>();
        consumedKcal = 0;
        plannedKcal = 0;
        for (String type : MealLabels.TYPES) {
            double kc = 0, pk = 0;
            List<DiaryRow> items = new ArrayList<>();
            List<DiaryRow> plannedItems = new ArrayList<>();
            for (ItemRow r : list) {
                if (!type.equals(r.mealType)) continue;
                double k = NutritionMath.forItem(r.item).kcal;
                if (MealEntryEntity.STATUS_PLANNED.equals(r.status)) {
                    pk += k;
                    plannedItems.add(DiaryRow.item(r));
                } else {
                    kc += k;
                    items.add(DiaryRow.item(r));
                }
            }
            consumedKcal += kc;
            plannedKcal += pk;
            out.add(DiaryRow.header(type, kc, pk));
            if (items.isEmpty() && plannedItems.isEmpty()) out.add(DiaryRow.empty(type));
            out.addAll(items);
            out.addAll(plannedItems);
        }
        rows.setValue(out);
    }
}
