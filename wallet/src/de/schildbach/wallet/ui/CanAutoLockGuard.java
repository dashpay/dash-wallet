package de.schildbach.wallet.ui;

import android.content.SharedPreferences;

import org.dash.wallet.common.Configuration;

public class CanAutoLockGuard implements SharedPreferences.OnSharedPreferenceChangeListener {

    private Configuration config;
    private OnAutoLockStatusChangedListener listener;

    public CanAutoLockGuard(Configuration config, OnAutoLockStatusChangedListener listener) {
        this.config = config;
        this.listener = listener;
    }

    public void register(boolean forceNotifyStatusChanged) {
        config.registerOnSharedPreferenceChangeListener(this);
        if (forceNotifyStatusChanged) {
            notifyListener(config.getCanAutoLock());
        }
    }

    public void unregister() {
        config.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Configuration.PREFS_KEY_CAN_AUTO_LOCK.equals(key)) {
            notifyListener(sharedPreferences.getBoolean(key, false));
        }
    }

    private void notifyListener(boolean active) {
        if (listener != null) {
            listener.onAutoLockStatusChanged(active);
        }
    }

    public boolean canAutoLock() {
        return config.getCanAutoLock();
    }

    public interface OnAutoLockStatusChangedListener {

        void onAutoLockStatusChanged(boolean active);
    }
}
