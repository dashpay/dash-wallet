package de.schildbach.wallet.ui.preference;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import com.jakewharton.processphoenix.ProcessPhoenix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet_test.R;

import static java.lang.Math.pow;

public class PinRetryController {

    private final SharedPreferences prefs;

    private final static String PREFS_SECURE_TIME = "secure_time";
    private final static String PREFS_FAIL_HEIGHT = "fail_height";
    private final static String PREFS_FAILED_PINS = "failed_pins";
    private final static int RETRY_FAIL_TOLERANCE = 3;
    private final static int POW_LOCK_TIME_BASE = 6;
    private final static int FAIL_LIMIT = 8;
    private final static long ONE_MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final Logger log = LoggerFactory.getLogger(PinRetryController.class);

    private final static PinRetryController sInstance = new PinRetryController();

    public static PinRetryController getInstance() {
        return sInstance;
    }

    private PinRetryController() {
        this.prefs = WalletApplication.getInstance().getSharedPreferences("pin_retry_controller_prefs", Context.MODE_PRIVATE);
    }

    public boolean isLocked() {
        return getLockTimeMinutes() != null;
    }

    private Long getLockTimeMinutes() {
        int failCount = failCount();
        long secureTime = prefs.getLong(PREFS_SECURE_TIME, 0);
        long failHeight = prefs.getLong(PREFS_FAIL_HEIGHT, 0);
        long now = System.currentTimeMillis();

        double base = failHeight + pow(POW_LOCK_TIME_BASE, failCount - RETRY_FAIL_TOLERANCE) * ONE_MINUTE_MILLIS;
        boolean locked = secureTime + now < base && failCount >= RETRY_FAIL_TOLERANCE;
        //TODO: Null secureTime Edge Case
        double lockTimeMillis = (base - secureTime - now);
        long lockTimeMinutes = Math.round(lockTimeMillis / 1000 / 60);

        return locked ? lockTimeMinutes : null;
    }


    public boolean isLockedForever() {
        return failCount() >= FAIL_LIMIT;
    }

    @SuppressLint("ApplySharedPref")
    public void clearPinFailPrefs() {
        SharedPreferences.Editor prefsEditor = prefs.edit();
        prefsEditor.remove(PREFS_FAIL_HEIGHT);
        prefsEditor.remove(PREFS_FAILED_PINS);
        prefsEditor.commit();
    }

    @SuppressLint("ApplySharedPref")
    public void failedAttempt(String pin) {
        long secureTime = prefs.getLong(PREFS_SECURE_TIME, 0);
        Set<String> storedFailedPins = prefs.getStringSet(PREFS_FAILED_PINS, new HashSet<String>());
        Set<String> failedPins = new HashSet<>(storedFailedPins);

        if (!failedPins.contains(pin)) {
            failedPins.add(pin);

            int failCount = failedPins.size();
            SharedPreferences.Editor prefsEditor = prefs.edit();
            prefsEditor.putStringSet(PREFS_FAILED_PINS, failedPins);
            log.info("PIN entered incorrectly " + failCount + "times");

            if (failCount >= FAIL_LIMIT) {
                prefsEditor.commit();
                // wallet permanently locked, restart the app and show wallet disabled screen
                ProcessPhoenix.triggerRebirth(WalletApplication.getInstance());
                return;
            } else {
                prefsEditor.putLong(PREFS_FAIL_HEIGHT, secureTime + System.currentTimeMillis());
            }

            prefsEditor.commit();
        }
    }

    public String getWalletTemporaryLockedMessage(Context context) {
        String unit;
        Long lockTime = getLockTimeMinutes();
        if (lockTime == null) {
            return null;
        }
        lockTime = (lockTime < 2) ? 1 : lockTime;

        if (lockTime < 60) {
            unit = context.getResources().getQuantityString(R.plurals.minute, lockTime.intValue());
        } else {
            lockTime /= 60;
            unit = context.getResources().getQuantityString(R.plurals.hour, lockTime.intValue());
        }

        return context.getString(R.string.wallet_lock_try_again, lockTime, unit);
    }

    public int failCount() {
        return prefs.getStringSet(PREFS_FAILED_PINS, new HashSet<String>()).size();
    }

    public String getRemainingAttemptsMessage(Context context) {
        int attemptsRemaining = FAIL_LIMIT - failCount();
        return context.getResources().getQuantityString(R.plurals.wallet_lock_attempts_remaining,
                attemptsRemaining, attemptsRemaining);
    }

    public void storeSecureTime(Date date) {
        prefs.edit().putLong(PREFS_SECURE_TIME, date.getTime()).apply();
    }
}
