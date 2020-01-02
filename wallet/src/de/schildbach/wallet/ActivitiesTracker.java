package de.schildbach.wallet;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public abstract class ActivitiesTracker implements Application.ActivityLifecycleCallbacks {

    private int numStarted;

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {

    }

    @Override
    public void onActivityStarted(Activity activity) {
        boolean isTheFirstOne = numStarted == 0;
        if (isTheFirstOne) {
            onStartedFirst(activity);
        }
        onStartedAny(isTheFirstOne);
        numStarted++;
    }

    @Override
    public void onActivityResumed(Activity activity) {

    }

    @Override
    public void onActivityPaused(Activity activity) {

    }

    @Override
    public void onActivityStopped(Activity activity) {
        numStarted--;
        if (numStarted == 0) {
            // app went to background
            onStoppedLast();
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

    }

    @Override
    public void onActivityDestroyed(Activity activity) {

    }

    protected void onStoppedLast() {

    }

    protected void onStartedFirst(Activity activity) {

    }

    protected void onStartedAny(boolean isTheFirstOne) {

    }
}
