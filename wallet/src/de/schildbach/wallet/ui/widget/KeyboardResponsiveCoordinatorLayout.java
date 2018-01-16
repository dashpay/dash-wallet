package de.schildbach.wallet.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.support.annotation.Nullable;
import android.support.design.widget.CoordinatorLayout;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;

import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class KeyboardResponsiveCoordinatorLayout extends CoordinatorLayout {

    private final Rect rect;
    private View decorView;

    private final int idOfViewToHide;
    private @Nullable View viewToHide;

    public KeyboardResponsiveCoordinatorLayout(Context context) {
        this(context, null);
    }

    public KeyboardResponsiveCoordinatorLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyboardResponsiveCoordinatorLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        rect = new Rect();

        final TypedArray styleAttributeArray = getContext().getTheme().obtainStyledAttributes(
                attrs, R.styleable.KeyboardResponsiveCoordinatorLayout, 0, 0);

        try {
            idOfViewToHide = styleAttributeArray.getResourceId(R.styleable.KeyboardResponsiveCoordinatorLayout_viewToHideWhenSoftKeyboardIsOpen, -1);
        } finally {
            styleAttributeArray.recycle();
        }
    }

    private ViewTreeObserver.OnGlobalLayoutListener layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            int difference = calculateDifferenceBetweenHeightAndUsableArea();

            if (difference != 0) {
                if (viewToHide != null) {
                    viewToHide.setVisibility(View.GONE);
                }
            } else {
                if (viewToHide != null) {
                    viewToHide.setVisibility(View.VISIBLE);
                }
            }
        }
    };

    private int calculateDifferenceBetweenHeightAndUsableArea() {
        if (decorView == null) {
            decorView = getRootView();
        }

        decorView.getWindowVisibleDisplayFrame(rect);

        return getResources().getDisplayMetrics().heightPixels - rect.bottom;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);

        if (idOfViewToHide != -1) {
            viewToHide = findViewById(idOfViewToHide);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);

        viewToHide = null;
    }
}
