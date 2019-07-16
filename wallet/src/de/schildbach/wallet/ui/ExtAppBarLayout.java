package de.schildbach.wallet.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import com.google.android.material.appbar.AppBarLayout;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import de.schildbach.wallet_test.R;

/**
 * @author Tomasz Ludek
 */
public class ExtAppBarLayout extends AppBarLayout {

    private static final String DASH_WEBPAGE_URL = "http://www.dash.org";

    private View toolbarLogoView;

    public ExtAppBarLayout(Context context) {
        super(context);
        init();
    }

    public ExtAppBarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        initView();
    }

    private void initView() {
        inflate(getContext(), R.layout.ext_app_bar_layout, this);
        inflate(getContext(), R.layout.ext_app_bar_bottom_layout, this);
        setBackgroundColor(Color.TRANSPARENT);

        toolbarLogoView = findViewById(R.id.toolbar_logo);
        toolbarLogoView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                blinkViews(toolbarLogoView);
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
