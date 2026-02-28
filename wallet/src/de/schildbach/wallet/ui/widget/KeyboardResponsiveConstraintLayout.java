/*
 * Copyright 2021 Dash Core Group.
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
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

import de.schildbach.wallet_test.R;

public class KeyboardResponsiveConstraintLayout extends ConstraintLayout {

    private final Rect rect;
    private View decorView;

    private final int idOfViewToHide;
    private @Nullable View viewToHide;
    private int initialVisibility;  //preserve the original visibility of viewToHide

    public KeyboardResponsiveConstraintLayout(Context context) {
        this(context, null);
    }

    public KeyboardResponsiveConstraintLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyboardResponsiveConstraintLayout(Context context, AttributeSet attrs, int defStyleAttr) {
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

    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
            int difference = calculateDifferenceBetweenHeightAndUsableArea();

            if (viewToHide != null) {
                // If difference > 0, keyboard is showing.
                // If difference =< 0, keyboard is not showing or is in multiview mode.
                if (difference > 0) {
                    viewToHide.setVisibility(View.GONE);
                } else {
                    if (initialVisibility == View.VISIBLE)
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
            initialVisibility = viewToHide.getVisibility();
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);

        viewToHide = null;
        initialVisibility = View.VISIBLE;
    }
}
