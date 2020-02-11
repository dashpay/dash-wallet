/*
 * Copyright 2018 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class FingerprintView extends LinearLayout {

    private TextView fingerprintText;
    private ImageView fingerprintIcon;
    private String initialText;

    public FingerprintView(Context context) {
        super(context);
        init(null);

    }

    public FingerprintView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public FingerprintView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        int layoutResId = R.layout.fingerprint_view;
        if (attrs != null) {
            TypedArray attrsArray = getContext().obtainStyledAttributes(attrs, R.styleable.FingerprintView);
            try {
                int customLayoutResId = attrsArray.getResourceId(R.styleable.FingerprintView_custom_layout, 0);
                if (customLayoutResId > 0) {
                    layoutResId = customLayoutResId;
                }
            } finally {
                attrsArray.recycle();
            }
        }
        LayoutInflater.from(getContext()).inflate(layoutResId, this, true);
        fingerprintText = findViewById(R.id.fingerprint_text);
        fingerprintIcon = findViewById(R.id.fingerprint_icon);
        initialText = fingerprintText.getText().toString();
    }

    public void setText(String text) {
        fingerprintText.setVisibility(View.VISIBLE);
        fingerprintText.setText(text);
        initialText = text;
    }

    public void setText(@StringRes int stringRes) {
        setText(getContext().getString(stringRes));
    }

    public void showError(boolean exceededMaxAttempts) {
        Context context = getContext();
        Animation shakeAnimation = AnimationUtils.loadAnimation(context, R.anim.shake);
        initialText = fingerprintText.getText().toString();

        fingerprintIcon.startAnimation(shakeAnimation);
        if (exceededMaxAttempts) {
            fingerprintText.setText(R.string.unlock_with_fingerprint_error_max_attempts);
        } else {
            fingerprintText.setText(R.string.unlock_with_fingerprint_error);
        }
        fingerprintText.setVisibility(View.VISIBLE);
    }

    public void hideError() {
        fingerprintText.setText(initialText);
    }
}
