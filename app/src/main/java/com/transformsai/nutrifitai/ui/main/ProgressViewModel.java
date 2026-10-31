package com.transformsai.nutrifitai.ui.main;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.db.entity.GoalEntity;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.data.db.model.DayTotals;
import com.transformsai.nutrifitai.data.db.model.DayWater;
import com.transformsai.nutrifitai.domain.GoalTimeline;
import com.transformsai.nutrifitai.domain.ProgressStats;
import com.transformsai.nutrifitai.util.DateUtil;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads a week / month / year window off the main thread and computes statistics. */
public class ProgressViewModel extends ViewModel {
    public static final class Model {
        public int period;
        public String from, to;
        public ProgressStats stats;
        public List<WeightEntity> weights;
        public WeightEntity before;
        public WeightEntity latest;
        public String units = "metric";
        public GoalEntity currentGoal;
    }

    private final AppGraph g = App.graph();
    private final SavedStateHandle state;
    private final MutableLiveData<Model> model = new MutableLiveData<>();

    public ProgressViewModel(SavedStateHandle h) {
        state = h;
    }

    public LiveData<Model> model() {
        return model;
    }

    public int period() {
        Integer p = state.get("period");
        return p == null ? 0 : p;
    }

    public void setPeriod(int p) {
        state.set("period", p);
        load();
    }

    public void load() {
        int period = period();
        g.runIo(() -> {
            String uid = g.session.uid();
            if (uid == null) return;
            LocalDate today = DateUtil.todayDate();
            LocalDate from, to;
            if (period == 0) {
                from = DateUtil.weekStart(today);
                to = from.plusDays(6);
            } else if (period == 1) {
                from = today.withDayOfMonth(1);
                to = today.withDayOfMonth(today.lengthOfMonth());
            } else {
                from = today.minusMonths(11).withDayOfMonth(1);
                to = today.withDayOfMonth(today.lengthOfMonth());
            }
            Model m = new Model();
            m.period = period;
            m.from = DateUtil.iso(from);
            m.to = DateUtil.iso(to);
            List<DayTotals> totals = g.db.diary().totalsRange(uid, m.from, m.to);
            Map<String, Integer> water = new HashMap<>();
            for (DayWater w : g.db.water().range(uid, m.from, m.to)) water.put(w.date, w.ml);
            GoalTimeline timeline = new GoalTimeline(g.db.goals().all(uid));
            m.stats = ProgressStats.compute(m.from, m.to, totals, timeline, water);
            m.weights = g.db.weights().range(uid, m.from, m.to);
            m.before = g.db.weights().latestBefore(uid, m.from);
            m.latest = g.db.weights().latest(uid);
            ProfileEntity p = g.db.profiles().get(uid);
            if (p != null) m.units = p.units;
            m.currentGoal = g.db.goals().active(uid, DateUtil.today());
            model.postValue(m);
        });
    }
}
