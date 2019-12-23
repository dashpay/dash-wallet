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

public class AutoLogoutTimer {

    private static final long LOCK_TIMER_TICK_MS = TimeUnit.SECONDS.toMillis(5);

    private Handler lockTimerClock = new Handler();
    private long tickCounter;

    private Configuration config;

    private boolean appInBackground = false;

    private OnLogoutListener onLogoutListener;

    private SharedPreferences.OnSharedPreferenceChangeListener configListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            System.out.println("AutoLogoutTimer\tonSharedPreferenceChanged: " + key);
            if (Configuration.PREFS_KEY_AUTO_LOGOUT_ENABLED.equals(key)) {
                setup();
            }
        }
    };

    AutoLogoutTimer(Configuration config) {
        this.config = config;
        this.config.registerOnSharedPreferenceChangeListener(configListener);
    }

    public void setup() {
        System.out.println("AutoLogoutTimer\tsetup: " + config.getAutoLogoutEnabled());
        if (config.getAutoLogoutEnabled()) {
            startTimer();
        } else {
            stopTimer();
        }
    }

    public void startTimer() {
        lockTimerClock.postDelayed(new Runnable() {
            @Override
            public void run() {
                tickCounter += LOCK_TIMER_TICK_MS;
                System.out.println("AutoLogoutTimer\ttickCounter:\t" + tickCounter + "\t\t / " + TimeUnit.MINUTES.toMillis(config.getAutoLogoutMinutes()) + "\t\t" + appInBackground);
                if (shouldLogout()) {
                    if (onLogoutListener != null) {
                        System.out.println("AutoLogoutTimer\tonLogout(" + appInBackground + ")");
                        onLogoutListener.onLogout(appInBackground);
                    }
                } else {
                    lockTimerClock.postDelayed(this, LOCK_TIMER_TICK_MS);
                }
            }
        }, LOCK_TIMER_TICK_MS);
    }

    private boolean shouldLogout() {
        long autoLogoutMillis = TimeUnit.MINUTES.toMillis(config.getAutoLogoutMinutes());
        return config.getAutoLogoutEnabled() && tickCounter >= autoLogoutMillis;
    }

    public void stopTimer() {
        System.out.println("AutoLogoutTimer\tstopTimer()");
        lockTimerClock.removeCallbacksAndMessages(null);
        resetTimer();
    }

    public void setOnLogoutListener(OnLogoutListener onLogoutListener) {
        this.onLogoutListener = onLogoutListener;
    }

    public void resetTimer() {
        System.out.println("AutoLogoutTimer\tresetTimer()");
        tickCounter = 0;
    }

    public void setAppInBackground(boolean appInBackground) {
        this.appInBackground = appInBackground;
    }

    interface OnLogoutListener {
        void onLogout(boolean isAppInBackground);
    }
}
