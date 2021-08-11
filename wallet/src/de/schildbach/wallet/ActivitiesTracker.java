package de.schildbach.wallet;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;

public abstract class ActivitiesTracker implements Application.ActivityLifecycleCallbacks {

    private int numStarted;

    @Override
    public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {

    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        boolean isTheFirstOne = numStarted == 0;
        if (isTheFirstOne) {
            onStartedFirst(activity);
        }
        onStartedAny(isTheFirstOne);
        numStarted++;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {

    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {

    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        numStarted--;
        if (numStarted == 0) {
            // app went to background
            onStoppedLast();
        }
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity,
                                            @NonNull Bundle outState) {

    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {

    }

    protected void onStoppedLast() {

    }

    protected void onStartedFirst(Activity activity) {

    }

    protected void onStartedAny(boolean isTheFirstOne) {

    }
}
