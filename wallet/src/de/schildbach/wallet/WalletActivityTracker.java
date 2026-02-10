/*
 * Copyright 2026 the original author or authors.
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;

import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.InteractionAwareActivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.service.RestartService;
import de.schildbach.wallet_test.BuildConfig;

public class WalletActivityTracker extends ActivitiesTracker {

    private static final Logger log = LoggerFactory.getLogger(WalletActivityTracker.class);

    private final WalletApplication app;
    private Activity currentActivity;

    private final Configuration config;
    private final AutoLogout autoLogout;
    private final RestartService restartService;

    private int activityCount = 0;
    private int foregroundActivityCount = 0;
    private int visibleActivityCount = 0;
    private final String logName = "activity lifecycle";

    public WalletActivityTracker(WalletApplication app, Configuration config,
                                 AutoLogout autoLogout, RestartService restartService) {
        this.app = app;
        this.config = config;
        this.autoLogout = autoLogout;
        this.restartService = restartService;
    }

    @Override
    protected void onStartedAny(boolean isTheFirstOne, Activity activity) {
        super.onStartedAny(isTheFirstOne, activity);
        // force restart if the app was updated
        // this ensures that v6.x or previous will go through the PIN upgrade process
        if (!BuildConfig.DEBUG && app.myPackageReplaced) {
            log.info("restarting app due to upgrade");
            app.myPackageReplaced = false;
            restartService.performRestart(activity, true, true);
        }
    }

    @Override
    protected void onStoppedLast() {
        autoLogout.setAppWentBackground(true);
        if (config.getAutoLogoutEnabled() && config.getAutoLogoutMinutes() == 0) {
            app.sendBroadcast(new Intent(InteractionAwareActivity.FORCE_FINISH_ACTION));
        }
    }

    public void onActivityCreated(@NonNull Activity activity, Bundle bundle) {
        if (activityCount == 0)
            log.info("{}: app started", logName);
        activityCount++;
        log.info("{}: activity {} created", logName, activity.getClass().getSimpleName());
        currentActivity = activity;
        logState();
    }

    public void onActivityDestroyed(@NonNull Activity activity) {
        log.info("{}: activity {} destroyed", logName, activity.getClass().getSimpleName());
        activityCount--;
        logState();
        if (activityCount == 0)
            log.info("{}: app closed", logName);
    }

    public void onActivityResumed(@NonNull Activity activity) {
        foregroundActivityCount++;
        currentActivity = activity;
        logState();
    }

    public void onActivityPaused(@NonNull Activity activity) {
        foregroundActivityCount--;
        currentActivity = activity;
        logState();
    }

    public void onActivityStarted(@NonNull Activity activity) {
        visibleActivityCount++;
        currentActivity = activity;
        logState();
    }

    public void onActivityStopped(@NonNull Activity activity) {
        visibleActivityCount--;
        currentActivity = activity;
        logState();
    }

    private void logState() {
        log.info("{}: current: {}, activities: {} visible: {} foreground: {}", logName,
                currentActivity.getClass().getSimpleName(), activityCount,
                visibleActivityCount, foregroundActivityCount);
    }
}