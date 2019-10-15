package de.schildbach.wallet.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.google.android.material.appbar.AppBarLayout;

public class CollapsingImageBehavior extends CoordinatorLayout.Behavior<View> {

    public CollapsingImageBehavior() {
    }

    public CollapsingImageBehavior(Context context, AttributeSet attrs) {
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return dependency instanceof AppBarLayout;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {

        if (!(dependency instanceof AppBarLayout)) {
            return false;
        }

        AppBarLayout appBarLayout = (AppBarLayout) dependency;

        int range = appBarLayout.getTotalScrollRange();

        float factor1 = appBarLayout.getY() / range; // 0.0..-1.0
        float alpha = 1 + factor1 * 3;

        child.setAlpha(alpha);

        return true;
    }
}