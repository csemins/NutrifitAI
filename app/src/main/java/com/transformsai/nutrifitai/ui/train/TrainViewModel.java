package com.transformsai.nutrifitai.ui.train;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutPlanEntity;
import com.transformsai.nutrifitai.data.db.entity.WorkoutSessionEntity;
import com.transformsai.nutrifitai.data.db.model.DayWorkout;
import com.transformsai.nutrifitai.fitness.ExerciseLibrary;
import com.transformsai.nutrifitai.util.DateUtil;

import java.util.List;

/** Train tab state: everything derives from Room (sessions, plans) and the bundled exercise library. */
public class TrainViewModel extends ViewModel {
    /** Daily goal in minutes (about 150 min of activity a week, the WHO adult guideline). */
    public static final int DAILY_GOAL_MIN = 20;

    private final AppGraph g = App.graph();
    private final String uid = g.session.requireUid();
    public final LiveData<String> today = g.today.observe();
    public final LiveData<ProfileEntity> profile = g.profile.observe(uid);
    public final LiveData<List<DayWorkout>> week = Transformations.switchMap(today,
            d -> g.workouts.observeDaily(uid, DateUtil.shift(d, -6), d));
    public final LiveData<List<WorkoutSessionEntity>> recent = g.workouts.observeRecent(uid, 6);
    /** Sessions of the last 7 days, for the weekly muscle map. */
    public final LiveData<List<WorkoutSessionEntity>> weekSessions = Transformations.switchMap(today,
            d -> g.db.workouts().observeRange(uid, DateUtil.shift(d, -6), d));
    public final LiveData<List<WorkoutPlanEntity>> plans = g.workouts.observePlans(uid);
    public final MutableLiveData<ExerciseLibrary> library = new MutableLiveData<>();

    public TrainViewModel() {
        g.runIo(() -> library.postValue(ExerciseLibrary.get(g.context)));
    }

    public double bodyKg() {
        ProfileEntity p = profile.getValue();
        return p != null && p.weightKg > 0 ? p.weightKg : 65;
    }
}
