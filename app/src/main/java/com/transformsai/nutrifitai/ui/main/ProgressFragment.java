package com.transformsai.nutrifitai.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.db.entity.WeightEntity;
import com.transformsai.nutrifitai.databinding.FragmentProgressBinding;
import com.transformsai.nutrifitai.databinding.ViewGoalRowBinding;
import com.transformsai.nutrifitai.domain.ProgressStats;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.weight.WeightActivity;
import com.transformsai.nutrifitai.ui.widget.BarChartView;
import com.transformsai.nutrifitai.util.DateUtil;
import com.transformsai.nutrifitai.util.Units;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Trends with explicit handling of missing days and historical goal snapshots. */
public class ProgressFragment extends Fragment implements MainActivity.ScrollToTop {
    private FragmentProgressBinding b;
    private ProgressViewModel vm;
    private ProgressViewModel.Model last;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentProgressBinding.inflate(inf, c, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        InsetsUtil.padding(b.content, true, false);
        vm = new ViewModelProvider(this).get(ProgressViewModel.class);
        b.period.select(vm.period(), false);
        b.period.setOnSelectListener(vm::setPeriod);
        b.addWeight.setOnClickListener(x -> startActivity(WeightActivity.add(requireContext())));
        b.weightHistory.setOnClickListener(x -> startActivity(new Intent(requireContext(), WeightActivity.class)));
        b.top.overline.setText(R.string.nav_progress);
        b.top.title.setText(R.string.progress_title);
        b.top.action2.setVisibility(View.VISIBLE);
        b.top.action2.setImageResource(R.drawable.ic_share);
        b.top.action2.setContentDescription(getString(R.string.share_summary));
        b.top.action2.setOnClickListener(x -> share());
        b.top.avatar.bind(getViewLifecycleOwner());
        b.top.avatar.setOnClickListener(x -> ((MainActivity) requireActivity()).select(MainActivity.TAB_PROFILE));
        b.legend.setText(legend());
        vm.model().observe(getViewLifecycleOwner(), this::render);
        // Recompute whenever anything in the diary changes.
        String uid = App.graph().session.requireUid();
        App.graph().diary.observeTotals(uid, DateUtil.today()).observe(getViewLifecycleOwner(), t -> vm.load());
        App.graph().water.observeTotal(uid, DateUtil.today()).observe(getViewLifecycleOwner(), t -> vm.load());
        App.graph().weights.observeAll(uid).observe(getViewLifecycleOwner(), t -> vm.load());
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && vm != null) vm.load();
    }

    private CharSequence legend() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        add(sb, "● ", R.color.nl_lime);
        sb.append("in range ±10%   ");
        add(sb, "● ", R.color.nl_carbs);
        sb.append("over   ");
        add(sb, "● ", R.color.nl_fat);
        sb.append("under   ");
        add(sb, "▬ ", R.color.nl_surface_3);
        sb.append("no data");
        return sb;
    }

    private void add(SpannableStringBuilder sb, String s, int color) {
        int st = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(ContextCompat.getColor(requireContext(), color)), st, sb.length(), 0);
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private void render(ProgressViewModel.Model m) {
        last = m;
        ProgressStats s = m.stats;
        b.range.setText(DateUtil.monthDay(m.from) + " – " + DateUtil.monthDay(m.to));
        boolean nothing = s.loggedDays == 0 && s.waterLoggedDays == 0 && m.weights.isEmpty();
        b.empty.setVisibility(nothing ? View.VISIBLE : View.GONE);

        // ---- Calories chart ----
        List<BarChartView.Bar> bars = new ArrayList<>();
        StringBuilder cd = new StringBuilder();
        String today = DateUtil.today();
        if (m.period == 2) {
            LocalDate start = DateUtil.parse(m.from);
            for (int i = 0; i < 12; i++) {
                LocalDate month = start.plusMonths(i);
                double sum = 0, tgt = 0;
                int n = 0, nt = 0;
                for (ProgressStats.Day d : s.days) {
                    LocalDate dd = DateUtil.parse(d.date);
                    if (dd.getYear() != month.getYear() || dd.getMonth() != month.getMonth() || !d.logged()) continue;
                    sum += d.totals.kcal;
                    n++;
                    if (d.goal != null) {
                        tgt += d.goal.kcal;
                        nt++;
                    }
                }
                BarChartView.Bar bar = new BarChartView.Bar();
                bar.label = month.getMonth().getDisplayName(TextStyle.NARROW, Locale.getDefault());
                if (n > 0) {
                    double avg = sum / n;
                    bar.value = (float) avg;
                    bar.valueLabel = String.valueOf(Math.round(avg));
                    bar.color = bandColor(nt > 0 ? avg / (tgt / nt) : 1);
                    cd.append(month.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault())).append(' ').append(Math.round(avg)).append(" kcal average. ");
                }
                bar.highlight = month.getMonth() == DateUtil.todayDate().getMonth() && month.getYear() == DateUtil.todayDate().getYear();
                bars.add(bar);
            }
        } else {
            for (ProgressStats.Day d : s.days) {
                BarChartView.Bar bar = new BarChartView.Bar();
                bar.label = m.period == 0 ? DateUtil.dayLetter(d.date) : String.valueOf(DateUtil.parse(d.date).getDayOfMonth());
                if (d.logged()) {
                    bar.value = (float) d.totals.kcal;
                    bar.valueLabel = String.valueOf(Math.round(d.totals.kcal));
                    Integer band = d.band();
                    bar.color = band == null ? color(R.color.nl_lime_deep) : band == 0 ? color(R.color.nl_lime_deep)
                            : band > 0 ? color(R.color.nl_carbs) : color(R.color.nl_fat);
                    cd.append(DateUtil.dayShort(d.date)).append(' ').append(Math.round(d.totals.kcal)).append(" kcal. ");
                }
                bar.highlight = d.date.equals(today);
                if (bar.highlight && bar.color == color(R.color.nl_lime_deep)) bar.color = color(R.color.nl_lime);
                bars.add(bar);
            }
        }
        Float target = m.currentGoal == null ? null : (float) m.currentGoal.kcal;
        b.calChart.setLine(m.period != 0, color(R.color.nl_lime)); // week: bars; month and year: trend line
        b.calChart.setData(bars, target, m.period != 1);
        b.calChart.setContentDescription(getString(R.string.cd_bar_chart, cd.length() == 0 ? getString(R.string.progress_empty_title) : cd.toString()));
        b.avgKcal.setText(s.loggedDays > 0 ? getString(R.string.avg_kcal, Math.round(s.avgKcal)) : "");
        b.inRange.setText(getString(R.string.in_range_value, s.inRangeDays, s.loggedDays));
        b.avgProtein.setText(s.loggedDays > 0 ? Math.round(s.avgProtein) + " g" : "—");
        b.loggedDays.setText(String.valueOf(s.loggedDays));

        // ---- Weight ----
        renderWeight(m);

        // ---- Score + goals ----
        if (s.consistencyScore == null) {
            b.score.setText("—");
            b.scoreRing.setProgress(0, false);
        } else {
            b.score.setText(String.valueOf(s.consistencyScore));
            b.scoreRing.setProgress(s.consistencyScore / 100f, true);
        }
        b.scoreCard.setContentDescription(getString(R.string.consistency) + " " + (s.consistencyScore == null ? "no data" : s.consistencyScore + " of 100"));
        goal(b.goalCal, R.string.calories, s.inRangeDays, s.loggedDays, R.color.nl_lime);
        goal(b.goalProtein, R.string.protein, s.proteinMetDays, s.loggedDays, R.color.nl_protein);
        goal(b.goalWater, R.string.water, s.waterMetDays, s.waterDaysWithGoal, R.color.nl_water);

        // ---- Water ----
        List<BarChartView.Bar> wb = new ArrayList<>();
        if (m.period == 2) {
            LocalDate start = DateUtil.parse(m.from);
            for (int i = 0; i < 12; i++) {
                LocalDate month = start.plusMonths(i);
                int sum = 0, n = 0;
                for (ProgressStats.Day d : s.days) {
                    LocalDate dd = DateUtil.parse(d.date);
                    if (dd.getYear() == month.getYear() && dd.getMonth() == month.getMonth() && d.waterMl > 0) {
                        sum += d.waterMl;
                        n++;
                    }
                }
                BarChartView.Bar bar = new BarChartView.Bar();
                bar.label = month.getMonth().getDisplayName(TextStyle.NARROW, Locale.getDefault());
                if (n > 0) {
                    bar.value = (float) sum / n;
                    bar.valueLabel = Units.volumeNumber(sum / n, m.units);
                }
                bar.color = color(R.color.nl_water_deep);
                wb.add(bar);
            }
        } else {
            for (ProgressStats.Day d : s.days) {
                BarChartView.Bar bar = new BarChartView.Bar();
                bar.label = m.period == 0 ? DateUtil.dayLetter(d.date) : String.valueOf(DateUtil.parse(d.date).getDayOfMonth());
                if (d.waterMl > 0) {
                    bar.value = (float) d.waterMl;
                    bar.valueLabel = Units.volumeNumber(d.waterMl, m.units);
                }
                bar.highlight = d.date.equals(today);
                bar.color = color(bar.highlight ? R.color.nl_water : R.color.nl_water_deep);
                wb.add(bar);
            }
        }
        b.waterChart.setLine(m.period != 0, color(R.color.nl_water));
        b.waterChart.setData(wb, m.currentGoal == null ? null : (float) m.currentGoal.waterMl, m.period == 0);
        b.avgWater.setText(s.waterLoggedDays > 0 ? "avg " + Units.volumeNumber((int) s.avgWaterMl, m.units) + " " + Units.volumeUnit(m.units) : "");

        // ---- Macros ----
        int pT = m.currentGoal == null ? 0 : m.currentGoal.proteinG;
        int cT = m.currentGoal == null ? 0 : m.currentGoal.carbsG;
        int fT = m.currentGoal == null ? 0 : m.currentGoal.fatG;
        avg(b.avgP, R.string.protein, s.avgProtein, pT, R.color.nl_protein, s.loggedDays);
        avg(b.avgC, R.string.carbs, s.avgCarbs, cT, R.color.nl_carbs, s.loggedDays);
        avg(b.avgF, R.string.fat, s.avgFat, fT, R.color.nl_fat, s.loggedDays);
    }

    private int bandColor(double ratio) {
        if (ratio > 1 + ProgressStats.RANGE_TOLERANCE) return color(R.color.nl_carbs);
        if (ratio < 1 - ProgressStats.RANGE_TOLERANCE) return color(R.color.nl_fat);
        return color(R.color.nl_lime_deep);
    }

    private void goal(ViewGoalRowBinding r, int label, int hit, int of, int c) {
        r.label.setText(label);
        r.value.setText(hit + "/" + of);
        r.bar.setColor(color(c));
        r.bar.setFraction(of == 0 ? 0 : (float) hit / of);
        r.getRoot().setContentDescription(getString(label) + " goal met on " + hit + " of " + of + " days");
    }

    private void avg(ViewGoalRowBinding r, int label, double v, int target, int c, int days) {
        r.label.setText(label);
        r.value.setText(days == 0 ? "—" : Math.round(v) + (target > 0 ? " / " + target + " g" : " g"));
        r.bar.setColor(color(c));
        r.bar.setFraction(target > 0 && days > 0 ? (float) (v / target) : 0);
    }

    private void renderWeight(ProgressViewModel.Model m) {
        boolean none = m.latest == null;
        b.weightEmpty.setVisibility(none ? View.VISIBLE : View.GONE);
        b.weightChart.setVisibility(none ? View.GONE : View.VISIBLE);
        b.weightNow.setText(none ? "—" : Units.weight(m.latest.kg, m.units));
        List<float[]> pts = new ArrayList<>();
        long span = Math.max(1, DateUtil.daysBetween(m.from, m.to));
        for (WeightEntity w : m.weights) {
            float x = (float) DateUtil.daysBetween(m.from, w.date) / span;
            float y = (float) (Units.imperial(m.units) ? Units.kgToLb(w.kg) : w.kg);
            pts.add(new float[]{x, y});
        }
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 4; i++) labels.add(DateUtil.monthDay(DateUtil.shift(m.from, (int) (span * i / 3))));
        b.weightChart.setData(pts, labels);
        Double change = ProgressStats.weightChange(m.weights, m.before);
        if (change == null) {
            b.weightChange.setText("");
        } else {
            double shown = Units.imperial(m.units) ? Units.kgToLb(change) : change;
            String sign = shown > 0 ? "+" : shown < 0 ? "−" : "±";
            b.weightChange.setText(getString(R.string.change_in_period,
                    sign + String.format(Locale.getDefault(), "%.1f", Math.abs(shown)) + " " + Units.weightUnit(m.units)));
            b.weightChange.setTextColor(color(R.color.nl_text_2)); // neutral: change is not judged good or bad
        }
        b.weightChart.setContentDescription(getString(R.string.weight) + ": " + b.weightNow.getText() + ". " + b.weightChange.getText());
    }

    /** Implicit intent: shares an image card of this period's averages through the Android chooser. */
    private void share() {
        if (last == null) return;
        ProgressStats s = last.stats;
        String period = getResources().getStringArray(R.array.period_segments)[last.period];
        com.transformsai.nutrifitai.ui.common.ShareCards.Card c = new com.transformsai.nutrifitai.ui.common.ShareCards.Card();
        c.kicker = getString(R.string.share_kicker_progress, period);
        c.title = getString(R.string.share_progress_title, s.loggedDays);
        c.subtitle = b.range.getText().toString();
        c.big = Ui.kcal(Math.round(s.avgKcal));
        c.caption = getString(R.string.share_progress_caption, s.inRangeDays, s.loggedDays);
        c.stat(Ui.kcal(Math.round(s.avgKcal)), getString(R.string.share_avg_kcal))
                .stat(Math.round(s.avgProtein) + " g", getString(R.string.protein))
                .stat(Units.volumeSmall((int) s.avgWaterMl, last.units), getString(R.string.water))
                .stat(String.valueOf(s.loggedDays), getString(R.string.share_days_logged));
        com.transformsai.nutrifitai.ui.common.ShareCards.share(requireActivity(), c, getString(R.string.share_summary));
    }

    @Override
    public void scrollToTop() {
        if (b != null) b.scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
