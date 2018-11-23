package de.schildbach.wallet.ui.widget;

import android.content.Context;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.content.ContextCompat;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class FingerprintView extends LinearLayout {

    private TextView fingerprintText;
    private ImageView fingerprintIcon;
    private View separator;
    private String initialText;

    public FingerprintView(Context context) {
        super(context);
        init();

    }

    public FingerprintView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FingerprintView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.fingerprint_view, this, true);
        separator = findViewById(R.id.separator);
        fingerprintText = findViewById(R.id.fingerprint_text);
        fingerprintIcon = findViewById(R.id.fingerprint_icon);
        initialText = fingerprintText.getText().toString();
    }

    public void setText(String text) {
        fingerprintText.setText(text);
        initialText = text;
    }

    public void setText(@StringRes int stringRes) {
        setText(getContext().getString(stringRes));
    }

    public void showError() {
        Context context = getContext();
        Animation shakeAnimation = AnimationUtils.loadAnimation(context, R.anim.shake);
        initialText = fingerprintText.getText().toString();

        fingerprintIcon.startAnimation(shakeAnimation);
        fingerprintIcon.setColorFilter(ContextCompat.getColor(context, R.color.fg_error));
        fingerprintText.setText(R.string.unlock_with_fingerprint_error);
    }

    public void hideError() {
        fingerprintIcon.setColorFilter(ContextCompat.getColor(getContext(),
                android.R.color.transparent));
        fingerprintText.setText(initialText);
    }

    public void hideSeparator() {
        separator.setVisibility(View.GONE);
    }

}
