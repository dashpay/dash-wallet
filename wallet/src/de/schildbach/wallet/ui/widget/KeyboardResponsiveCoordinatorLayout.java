/*
 * Copyright 2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
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

            if (viewToHide != null) {
                if (difference != 0) {
                    viewToHide.setVisibility(View.GONE);
                } else {
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
