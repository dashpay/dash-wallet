package org.dash.wallet.common;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.Nullable;

public class UserInteractionAwareCallback implements Window.Callback {

    private final Window.Callback originalCallback;
    private final Activity activity;

    public UserInteractionAwareCallback(final Window.Callback originalCallback, final Activity activity) {
        this.originalCallback = originalCallback;
        this.activity = activity;
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent event) {
        return originalCallback.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(final KeyEvent event) {
        return originalCallback.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (activity != null) {
                    activity.onUserInteraction();
                }
                break;
            default:
        }
        return originalCallback.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(final MotionEvent event) {
        return originalCallback.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(final MotionEvent event) {
        return originalCallback.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(final AccessibilityEvent event) {
        return originalCallback.dispatchPopulateAccessibilityEvent(event);
    }

    @Nullable
    @Override
    public View onCreatePanelView(final int featureId) {
        return originalCallback.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(final int featureId, final Menu menu) {
        return originalCallback.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(final int featureId, final View view, final Menu menu) {
        return originalCallback.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(final int featureId, final Menu menu) {
        return originalCallback.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(final int featureId, final MenuItem item) {
        return originalCallback.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(final WindowManager.LayoutParams attrs) {
        originalCallback.onWindowAttributesChanged(attrs);
    }

    @Override
    public void onContentChanged() {
        originalCallback.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        originalCallback.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        originalCallback.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        originalCallback.onDetachedFromWindow();
    }

    @Override
    public void onPanelClosed(final int featureId, final Menu menu) {
        originalCallback.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return originalCallback.onSearchRequested();
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public boolean onSearchRequested(final SearchEvent searchEvent) {
        return originalCallback.onSearchRequested(searchEvent);
    }

    @Nullable
    @Override
    public ActionMode onWindowStartingActionMode(final ActionMode.Callback callback) {
        return originalCallback.onWindowStartingActionMode(callback);
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Nullable
    @Override
    public ActionMode onWindowStartingActionMode(final ActionMode.Callback callback, final int type) {
        return originalCallback.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(final ActionMode mode) {
        originalCallback.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(final ActionMode mode) {
        originalCallback.onActionModeFinished(mode);
    }
}
    