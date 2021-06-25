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

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;

import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.InteractionAwareActivity;

import java.util.concurrent.TimeUnit;

public class AutoLogout {

    private static final long LOCK_TIMER_TICK_MS = TimeUnit.SECONDS.toMillis(5);

    private final Handler lockTimerClock = new Handler();
    private long tickCounter;
    private boolean timerActive = false;

    private final Configuration config;

    private boolean appWentBackground = true;
    public boolean keepLockedUntilPinEntered = true;

    private OnLogoutListener onLogoutListener;

    public boolean deviceWasLocked = false;

    @SuppressWarnings("FieldCanBeLocal")
    private SharedPreferences.OnSharedPreferenceChangeListener configListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (Configuration.PREFS_KEY_AUTO_LOGOUT_ENABLED.equals(key) || Configuration.PREFS_KEY_AUTO_LOGOUT_MINUTES.equals(key)) {
                setAppWentBackground(false);
                setup();
            }
        }
    };

    public AutoLogout(Configuration config) {
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

    public void maybeStartAutoLogoutTimer() {
        setup();
    }

    public void startTimer() {
        lockTimerClock.postDelayed(timerTask, LOCK_TIMER_TICK_MS);
        timerActive = true;
    }

    private final Runnable timerTask = new Runnable() {
        @Override
        public void run() {
            tickCounter += LOCK_TIMER_TICK_MS;
            if (shouldLogout()) {
                if (onLogoutListener != null) {
                    onLogoutListener.onLogout(appWentBackground);
                    WalletApplication.getInstance().sendBroadcast(new Intent(InteractionAwareActivity.FORCE_FINISH_ACTION));
                }
                timerActive = false;
            } else {
                lockTimerClock.postDelayed(timerTask, LOCK_TIMER_TICK_MS);
            }
        }
    };

    public boolean shouldLogout() {
        long autoLogoutMillis = TimeUnit.MINUTES.toMillis(config.getAutoLogoutMinutes());
        boolean logoutTimeExceeded = (config.getAutoLogoutMinutes() == 0) ? appWentBackground : (tickCounter >= autoLogoutMillis);
        return (config.getAutoLogoutEnabled() && logoutTimeExceeded) || deviceWasLocked;
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

    public void setAppWentBackground(boolean appWentBackground) {
        this.appWentBackground = appWentBackground;
    }

    public boolean isTimerActive() {
        return timerActive;
    }

    public void registerDeviceInteractiveReceiver(Context context) {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                KeyguardManager myKM = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                deviceWasLocked |= Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 ? myKM.isDeviceLocked() : myKM.inKeyguardRestrictedInputMode();
            }
        }, filter);
    }

    public interface OnLogoutListener {
        void onLogout(boolean isAppInBackground);
    }
}
