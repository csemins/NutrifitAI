package com.transformsai.nutrifitai.ui.main;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.data.db.model.ItemRow;

import java.util.List;

/** Home dashboard state. Everything is derived live from Room for the current local date. */
public class HomeViewModel extends ViewModel {
    private final AppGraph g = App.graph();
    private final String uid = g.session.requireUid();
    public final LiveData<String> today = g.today.observe();
    public final LiveData<ProfileEntity> profile = g.profile.observe(uid);
    public final LiveData<GoalEntity> goal = Transformations.switchMap(today, d -> g.profile.observeGoal(uid, d));
    public final LiveData<DayTotals> totals = Transformations.switchMap(today, d -> g.diary.observeTotals(uid, d));
    public final LiveData<Integer> water = Transformations.switchMap(today, d -> g.water.observeTotal(uid, d));
    public final LiveData<List<com.transformsai.nutrifitai.data.db.model.DayWorkout>> training =
            Transformations.switchMap(today, d -> g.workouts.observeDaily(uid, d, d));
    public final androidx.lifecycle.MutableLiveData<com.transformsai.nutrifitai.fitness.ExerciseLibrary> library = new androidx.lifecycle.MutableLiveData<>();

    public HomeViewModel() {
        g.runIo(() -> library.postValue(com.transformsai.nutrifitai.fitness.ExerciseLibrary.get(g.context)));
    }

    public final LiveData<List<ItemRow>> items = Transformations.switchMap(today, d -> g.diary.observeDay(uid, d));
    public final LiveData<com.transformsai.nutrifitai.data.db.entity.StepDayEntity> steps =
            Transformations.switchMap(today, d -> g.steps.observeDay(uid, d));
    public final LiveData<com.transformsai.nutrifitai.data.db.entity.FastEntity> fast = g.fasting.observeRunning(uid);
    public final androidx.lifecycle.MutableLiveData<Integer> streak = new androidx.lifecycle.MutableLiveData<>();

    public void refreshStreak() {
        g.runIo(() -> streak.postValue(com.transformsai.nutrifitai.domain.Achievements.compute(g.db, uid).currentStreak));
    }

    public void addWater(int ml) {
        g.water.add(ml, null);
    }
}
