package de.schildbach.wallet.ui;

import android.content.Context;
import android.graphics.Color;
import android.support.design.widget.AppBarLayout;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import hashengineering.darkcoin.wallet.R;

/**
 * @author Tomasz Ludek
 */
public class ExtAppBarLayout extends AppBarLayout implements AppBarLayout.OnOffsetChangedListener {

    private View toolbarLogo;
    private View toolbarTitle;
    private View toolbarLink;

    private boolean expanded = true;

    private AlphaAnimation fadeInAnimation;
    private AlphaAnimation fadeOutAnimation;

    public ExtAppBarLayout(Context context) {
        super(context);
        init();
    }

    public ExtAppBarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        fadeInAnimation = new AlphaAnimation(0, 1);
        fadeInAnimation.setDuration(150);
        fadeOutAnimation = new AlphaAnimation(1, 0);
        fadeOutAnimation.setDuration(150);
        initView();
    }

    private void initView() {
        inflate(getContext(), R.layout.ext_app_bar_layout, this);
        inflate(getContext(), R.layout.ext_app_bar_bottom_layout, this);
        setBackgroundColor(Color.TRANSPARENT);
        addOnOffsetChangedListener(this);
        toolbarLogo = findViewById(R.id.toolbar_logo);
        toolbarTitle = findViewById(R.id.toolbar_title);
        toolbarLink = findViewById(R.id.toolbar_link);
    }

    @Override
    public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
        float totalScrollRange = appBarLayout.getTotalScrollRange();
        float scaleFactor = (totalScrollRange + verticalOffset) / totalScrollRange;
        float scale = 1f + 0.3f * scaleFactor;
        toolbarLogo.setPivotX(0);
        toolbarLogo.setPivotY(0);
        toolbarLogo.setScaleX(scale);
        toolbarLogo.setScaleY(scale);

        if (scaleFactor > 0.1) {
            toolbarTitle.setVisibility(GONE);
        } else {
            toolbarTitle.setVisibility(VISIBLE);
        }

        if (scaleFactor > 0.5) {
            if (!expanded) {
                expanded = true;
                showLink();
            }
        } else {
            if (expanded) {
                expanded = false;
                hideLink();
            }
        }
    }

    private void showLink() {
        toolbarLink.startAnimation(fadeInAnimation);
        fadeInAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                toolbarLink.setVisibility(VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animation animation) {

            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
    }

    private void hideLink() {
        toolbarLink.startAnimation(fadeOutAnimation);
        fadeOutAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {

            }

            @Override
            public void onAnimationEnd(Animation animation) {
                toolbarLink.setVisibility(INVISIBLE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });
    }
}
