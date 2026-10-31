package com.transformsai.nutrifitai.ui.food;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.db.entity.FoodEntity;
import com.transformsai.nutrifitai.data.db.model.FrequentFood;
import com.transformsai.nutrifitai.data.repo.FoodRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FoodSearchViewModel extends ViewModel {
    private final SavedStateHandle state;
    private final MutableLiveData<List<FoodEntity>> results = new MutableLiveData<>();
    private final MutableLiveData<List<FrequentFood>> frequent = new MutableLiveData<>();
    public final Set<String> favorites = new HashSet<>();
    private int generation;

    public FoodSearchViewModel(SavedStateHandle h) {
        state = h;
    }

    public LiveData<List<FoodEntity>> results() {
        return results;
    }

    public LiveData<List<FrequentFood>> frequent() {
        return frequent;
    }

    public String query() {
        String q = state.get("q");
        return q == null ? "" : q;
    }

    public String filter() {
        String f = state.get("f");
        return f == null ? "all" : f;
    }

    public void setFilter(String f) {
        state.set("f", f);
        run();
    }

    public void setQuery(String q) {
        state.set("q", q);
        run();
    }

    public void run() {
        int gen = ++generation;
        FoodRepository.SearchQuery q = new FoodRepository.SearchQuery();
        q.text = query();
        q.filter = filter();
        App.graph().foods.favoriteIds(r -> {
            favorites.clear();
            if (r.value != null) favorites.addAll(r.value);
            App.graph().foods.search(q, res -> {
                if (gen == generation) results.setValue(res.value);
            });
        });
        App.graph().foods.frequent(r -> frequent.setValue(r.value));
    }
}
