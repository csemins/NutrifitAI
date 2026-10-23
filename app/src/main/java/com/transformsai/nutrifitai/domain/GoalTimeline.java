package com.transformsai.nutrifitai.domain;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.db.entity.GoalEntity;

import java.util.List;

/** Resolves which goal snapshot applied on a given date (snapshots sorted by effectiveDate asc). */
public final class GoalTimeline {
    private final List<GoalEntity> sorted;

    public GoalTimeline(List<GoalEntity> sortedAsc) {
        this.sorted = sortedAsc;
    }

    @Nullable
    public GoalEntity at(String date) {
        GoalEntity found = null;
        for (GoalEntity g : sorted) {
            if (g.effectiveDate.compareTo(date) <= 0) {
                if (found == null || g.effectiveDate.compareTo(found.effectiveDate) > 0
                        || (g.effectiveDate.equals(found.effectiveDate) && g.createdAt >= found.createdAt)) {
                    found = g;
                }
            }
        }
        return found;
    }
}
