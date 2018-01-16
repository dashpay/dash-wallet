package de.schildbach.wallet.ui.widget;

import android.content.Context;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.view.ViewCompat;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class ScrollFABBehavior extends FloatingActionButton.Behavior {

    private Animation showFabAnimation;
    private Animation hideFabAnimation;
    private Animation currentAnimation;
    private boolean isShown = true;
    private FloatingActionButton fab;

    public ScrollFABBehavior(Context context, AttributeSet attrs) {
        super();

        showFabAnimation = AnimationUtils.loadAnimation(context, R.anim.fab_show);
        hideFabAnimation = AnimationUtils.loadAnimation(context, R.anim.fab_hide);

        Animation.AnimationListener fabAnimationListener = new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                currentAnimation = animation;
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                isShown = animation == showFabAnimation;
                fab.setClickable(isShown);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        };

        showFabAnimation.setAnimationListener(fabAnimationListener);
        hideFabAnimation.setAnimationListener(fabAnimationListener);
    }

    @Override
    public boolean onStartNestedScroll(CoordinatorLayout coordinatorLayout,
                                       FloatingActionButton child, View directTargetChild,
                                       View target, int nestedScrollAxes) {
        fab = child;
        return nestedScrollAxes == ViewCompat.SCROLL_AXIS_VERTICAL ||
                super.onStartNestedScroll(coordinatorLayout, child, directTargetChild, target,
                        nestedScrollAxes);
    }

    @Override
    public void onNestedScroll(CoordinatorLayout coordinatorLayout, FloatingActionButton child,
                               View target, int dxConsumed, int dyConsumed, int dxUnconsumed,
                               int dyUnconsumed) {
        super.onNestedScroll(coordinatorLayout, child, target, dxConsumed, dyConsumed, dxUnconsumed,
                dyUnconsumed);
        //Cancel current animation
        if (currentAnimation != null &&
                currentAnimation.hasStarted() && !currentAnimation.hasEnded()) {
            currentAnimation.cancel();
        }
        //Start hide or show fab animation according to scrolling position
        if (dyConsumed > 0 && isShown) {
            child.startAnimation(hideFabAnimation); //scroll down -> hide
        } else if (dyConsumed < 0 && !isShown) {
            child.startAnimation(showFabAnimation); //scroll ul -> show
        }
    }

}
