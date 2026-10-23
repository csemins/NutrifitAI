package com.transformsai.nutrifitai.domain;

import androidx.annotation.Nullable;

/** Nutrition amounts for a concrete portion. Optional nutrients are null when unknown. */
public class Nutrients {
    public double kcal;
    public double protein;
    public double carbs;
    public double fat;
    @Nullable
    public Double fibre;
    @Nullable
    public Double sugar;
    @Nullable
    public Double sodiumMg;

    public Nutrients() {
    }

    public Nutrients(double kcal, double protein, double carbs, double fat, @Nullable Double fibre) {
        this.kcal = kcal;
        this.protein = protein;
        this.carbs = carbs;
        this.fat = fat;
        this.fibre = fibre;
    }

    public Nutrients copy() {
        Nutrients n = new Nutrients(kcal, protein, carbs, fat, fibre);
        n.sugar = sugar;
        n.sodiumMg = sodiumMg;
        return n;
    }
}
