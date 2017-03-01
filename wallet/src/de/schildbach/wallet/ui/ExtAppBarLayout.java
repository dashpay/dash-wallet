package de.schildbach.wallet.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.support.design.widget.AppBarLayout;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Toast;

import de.schildbach.wallet_test.R;

/**
 * @author Tomasz Ludek
 */
public class ExtAppBarLayout extends AppBarLayout implements AppBarLayout.OnOffsetChangedListener {

    private static final String DASH_WEBPAGE_URL = "http://www.dash.org";

    private View toolbarTitlePanelView;
    private View toolbarLogoView;
    private View toolbarTitleView;
    private View toolbarSloganView;

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
        toolbarTitlePanelView = findViewById(R.id.toolbar_title_panel);
        toolbarLogoView = findViewById(R.id.toolbar_logo);
        toolbarTitleView = findViewById(R.id.toolbar_title);
        toolbarSloganView = findViewById(R.id.toolbar_slogan);
    }

    @Override
    public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
        float totalScrollRange = appBarLayout.getTotalScrollRange();
        float scaleFactor = (totalScrollRange + verticalOffset) / totalScrollRange;
        float scale = 1f + 0.68f * scaleFactor;
        toolbarLogoView.setPivotX(0);
        toolbarLogoView.setPivotY(0);
        toolbarLogoView.setScaleX(scale);
        toolbarLogoView.setScaleY(scale);

        if (scaleFactor > 0.1) {
            toolbarTitleView.setVisibility(INVISIBLE);
        } else {
            toolbarTitleView.setVisibility(VISIBLE);
        }

        if (scaleFactor > 0.5) {
            if (!expanded) {
                expanded = true;
                showSlogan();
            }
        } else {
            if (expanded) {
                expanded = false;
                hideLink();
            }
        }
    }

    private void showSlogan() {
        toolbarSloganView.startAnimation(fadeInAnimation);
        fadeInAnimation.setAnimationListener(new AnimationListenerAdapter() {
            @Override
            public void onAnimationStart(Animation animation) {
                toolbarSloganView.setVisibility(VISIBLE);
            }
        });
        toolbarTitlePanelView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                blinkViews(toolbarLogoView, toolbarSloganView);
                openUrl(DASH_WEBPAGE_URL);
            }
        });
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            getContext().startActivity(i);
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(getContext(), "Unable to open " + url, Toast.LENGTH_LONG).show();
        }
    }

    private void hideLink() {
        toolbarTitlePanelView.setOnClickListener(null);
        fadeOutAnimation.setAnimationListener(new AnimationListenerAdapter() {
            @Override
            public void onAnimationEnd(Animation animation) {
                toolbarSloganView.setVisibility(INVISIBLE);
            }
        });
        toolbarSloganView.startAnimation(fadeOutAnimation);
    }

    private void blinkViews(final View... views) {
        for (View v : views) {
            v.setAlpha(0.8f);
        }
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                for (View v : views) {
                    v.setAlpha(1.0f);
                }
            }
        }, 200);
    }
}
