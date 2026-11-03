package com.transformsai.nutrifitai.ui.hydration;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.entity.ReminderSettingsEntity;
import com.transformsai.nutrifitai.data.db.entity.WaterEntity;
import com.transformsai.nutrifitai.data.db.model.DayWater;
import com.transformsai.nutrifitai.util.DateUtil;

import java.util.List;

public class HydrationViewModel extends ViewModel {
    private final AppGraph g = App.graph();
    public final String uid = g.session.requireUid();
    public final LiveData<String> today = g.today.observe();
    public final LiveData<List<WaterEntity>> drinks = Transformations.switchMap(today, d -> g.water.observeDay(uid, d));
    public final LiveData<Integer> total = Transformations.switchMap(today, d -> g.water.observeTotal(uid, d));
    public final LiveData<GoalEntity> goal = Transformations.switchMap(today, d -> g.profile.observeGoal(uid, d));
    public final LiveData<ProfileEntity> profile = g.profile.observe(uid);
    public final LiveData<ReminderSettingsEntity> reminders = g.reminders.observe(uid);
    public final MutableLiveData<List<DayWater>> week = new MutableLiveData<>();

    public void loadWeek() {
        String from = DateUtil.iso(DateUtil.weekStart(DateUtil.todayDate()));
        g.water.range(from, DateUtil.shift(from, 6), r -> week.setValue(r.value));
    }
}
