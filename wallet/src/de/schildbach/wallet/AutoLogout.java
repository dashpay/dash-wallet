/*
 * Copyright 2019 Dash Core Group
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

package de.schildbach.wallet;

import android.content.SharedPreferences;
import android.os.Handler;

import org.dash.wallet.common.Configuration;

import java.util.concurrent.TimeUnit;

public class AutoLogout {

    private static final long LOCK_TIMER_TICK_MS = TimeUnit.SECONDS.toMillis(5);

    private Handler lockTimerClock = new Handler();
    private long tickCounter;
    private boolean timerActive = false;

    private Configuration config;

    private boolean appInBackground = false;

    private OnLogoutListener onLogoutListener;

    @SuppressWarnings("FieldCanBeLocal")
    private SharedPreferences.OnSharedPreferenceChangeListener configListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (Configuration.PREFS_KEY_AUTO_LOGOUT_ENABLED.equals(key) || Configuration.PREFS_KEY_AUTO_LOGOUT_MINUTES.equals(key)) {
                setup();
            }
        }
    };

    AutoLogout(Configuration config) {
        this.config = config;
        this.config.registerOnSharedPreferenceChangeListener(configListener);
    }

    public void setup() {
        if (config.getAutoLogoutEnabled() && config.getAutoLogoutMinutes() > 0) {
            if (!timerActive) {
                startTimer();
            }
            resetTimer();
        } else {
            stopTimer();
        }
    }

    public void startTimer() {
        lockTimerClock.postDelayed(timerTask, LOCK_TIMER_TICK_MS);
        timerActive = true;
    }

    private Runnable timerTask = new Runnable() {
        @Override
        public void run() {
            tickCounter += LOCK_TIMER_TICK_MS;
            if (shouldLogout()) {
                if (onLogoutListener != null) {
                    onLogoutListener.onLogout(appInBackground);
                }
                timerActive = false;
            } else {
                lockTimerClock.postDelayed(timerTask, LOCK_TIMER_TICK_MS);
            }
        }
    };

    public boolean shouldLogout() {
        long autoLogoutMillis = TimeUnit.MINUTES.toMillis(config.getAutoLogoutMinutes());
        boolean logoutTimeExceeded = (config.getAutoLogoutMinutes() == 0) || tickCounter >= autoLogoutMillis;
        return config.getAutoLogoutEnabled() && logoutTimeExceeded;
    }

    public void stopTimer() {
        lockTimerClock.removeCallbacksAndMessages(null);
        timerActive = false;
        resetTimer();
    }

    public void setOnLogoutListener(OnLogoutListener onLogoutListener) {
        this.onLogoutListener = onLogoutListener;
    }

    public void resetTimer() {
        tickCounter = 0;
    }

    public void resetTimerIfActive() {
        if (isTimerActive()) {
            resetTimer();
        }
    }

    public void setAppInBackground(boolean appInBackground) {
        this.appInBackground = appInBackground;
    }

    public boolean isTimerActive() {
        return timerActive;
    }

    interface OnLogoutListener {
        void onLogout(boolean isAppInBackground);
    }
}
