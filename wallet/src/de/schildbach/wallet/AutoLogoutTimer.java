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

import android.os.Handler;

import org.dash.wallet.common.Configuration;

import java.util.concurrent.TimeUnit;

public class AutoLogoutTimer {

    private static final long LOCK_TIMER_TICK_MS = TimeUnit.SECONDS.toMillis(5);

    private Handler lockTimerClock = new Handler();
    private long tickCounter;

    private Configuration config;

    AutoLogoutTimer(Configuration config) {
        this.config = config;
    }

    boolean shouldLogout() {
        long autoLogoutMillis = TimeUnit.MINUTES.toMillis(config.getAutoLogoutMinutes());
        return config.getAutoLogoutEnabled() && tickCounter >= autoLogoutMillis;
    }

    public void start() {
        lockTimerClock.postDelayed(new Runnable() {
            @Override
            public void run() {
                tickCounter += LOCK_TIMER_TICK_MS;
                lockTimerClock.postDelayed(this, LOCK_TIMER_TICK_MS);
            }
        }, LOCK_TIMER_TICK_MS);
    }

    public void reset() {
        tickCounter = 0;
    }
}
