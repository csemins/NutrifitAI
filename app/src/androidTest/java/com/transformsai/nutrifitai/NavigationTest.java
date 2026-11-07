package com.transformsai.nutrifitai;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.transformsai.nutrifitai.ui.auth.AuthActivity;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Smoke test: the sign-in screen renders its primary controls. */
@RunWith(AndroidJUnit4.class)
public class NavigationTest {
    @Rule
    public ActivityScenarioRule<AuthActivity> rule = new ActivityScenarioRule<>(AuthActivity.class);

    @Test
    public void authScreenShowsControls() {
        onView(withId(R.id.email)).check(matches(isDisplayed()));
        onView(withId(R.id.primary)).check(matches(isDisplayed()));
        onView(withId(R.id.google)).check(matches(isDisplayed()));
    }
}
