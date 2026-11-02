package com.transformsai.nutrifitai.ui.analysis;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.ai.AiParseException;
import com.transformsai.nutrifitai.ai.AiResult;
import com.transformsai.nutrifitai.ai.AiResultParser;
import com.transformsai.nutrifitai.data.db.entity.AiAnalysisEntity;

/**
 * Holds the analysis row (observed from Room) and the user's in-progress edits. Edits are written
 * to SavedStateHandle so they survive process death before the user confirms.
 */
public class AnalysisViewModel extends ViewModel {
    private final SavedStateHandle state;
    private AiResult edited;
    public String meal;
    public boolean saving;

    public AnalysisViewModel(SavedStateHandle h) {
        state = h;
        meal = h.get("meal");
    }

    public LiveData<AiAnalysisEntity> observe(String id) {
        return App.graph().analysis.observe(id);
    }

    /** Returns the editable result, seeded from the stored validated JSON the first time. */
    public AiResult result(AiAnalysisEntity a) {
        if (edited != null) return edited;
        String json = state.get("edited");
        if (json == null) json = a.resultJson;
        try {
            edited = AiResultParser.parse(json);
        } catch (AiParseException e) {
            edited = null;
        }
        return edited;
    }

    public void persist() {
        if (edited != null) state.set("edited", AiResultParser.toJson(edited));
        state.set("meal", meal);
    }

    public void reset() {
        edited = null;
        state.remove("edited");
    }
}
