package de.schildbach.wallet.ui.preference;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;

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
    private final Context context;
    private final static String PREFS_SECURE_TIME = "secure_time";
    private final static String PREFS_FAIL_HEIGHT = "fail_height";
    private final static String PREFS_FAILED_PINS = "failed_pins";
    private final static int RETRY_FAIL_TOLERANCE = 3;
    private final static int POW_LOCK_TIME_BASE = 6;
    private final static int FAIL_LIMIT = 8;
    private final static long ONE_MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final Logger log = LoggerFactory.getLogger(PinRetryController.class);

    public PinRetryController(Context context) {
        this.context = context;
        this.prefs = getPrefs();
    }

    private static SharedPreferences getPrefs() {
        return WalletApplication.getInstance().getSharedPreferences("pin_retry_controller_prefs", Context.MODE_PRIVATE);
    }

    public boolean isLocked() {
        return getLockTimeMinutes() != null;
    }

    public Long getLockTimeMinutes() {
        int failCount = prefs.getStringSet(PREFS_FAILED_PINS, new HashSet<String>()).size();
        long secureTime = prefs.getLong(PREFS_SECURE_TIME, 0);
        long failHeight = prefs.getLong(PREFS_FAIL_HEIGHT, 0);
        long now = System.currentTimeMillis();

        boolean locked = secureTime + now < failHeight + pow(POW_LOCK_TIME_BASE, failCount - RETRY_FAIL_TOLERANCE) * ONE_MINUTE_MILLIS
                && failCount >= RETRY_FAIL_TOLERANCE;
        //TODO: Null secureTime Edge Case
        long lockTimeMillis = (long) (failHeight + pow(POW_LOCK_TIME_BASE, failCount - RETRY_FAIL_TOLERANCE)
                * ONE_MINUTE_MILLIS - secureTime - now);
        long lockTimeMinutes = TimeUnit.MILLISECONDS.toMinutes(lockTimeMillis);

        return locked ? lockTimeMinutes : null;
    }


    public boolean isLockedForever() {
        int failCount = prefs.getStringSet(PREFS_FAILED_PINS, new HashSet<String>()).size();
        return failCount >= FAIL_LIMIT;
    }

    public void clearPinFailPrefs() {
        clearPrefs(prefs);
    }

    public static void clearPrefs() {
        clearPrefs(getPrefs());
    }

    @SuppressLint("ApplySharedPref")
    public static void clearPrefs(SharedPreferences prefs) {
        SharedPreferences.Editor prefsEditor = prefs.edit();
        prefsEditor.remove(PREFS_FAIL_HEIGHT);
        prefsEditor.remove(PREFS_FAILED_PINS);
        prefsEditor.commit();
    }

    public void failedAttempt(String pin) {
        failedAttempt(pin, true);
    }

    public void failedAttempt(String pin, boolean showWipeDialog) {
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
                if (showWipeDialog) {
                    wipeWallet();
                }
            } else {
                prefsEditor.putLong(PREFS_FAIL_HEIGHT, secureTime + System.currentTimeMillis());
            }

            prefsEditor.apply();
        }
    }

    public String getWalletTemporaryLockedMessage() {
        String unit;
        Long lockTime = getLockTimeMinutes();
        lockTime = (lockTime < 2) ? 1 : lockTime;

        if (lockTime < 60) {
            unit = context.getResources().getQuantityString(R.plurals.minute, lockTime.intValue());
        } else {
            lockTime /= 60;
            unit = context.getResources().getQuantityString(R.plurals.hour, lockTime.intValue());
        }

        return context.getString(R.string.wallet_lock_try_again, lockTime, unit);
    }

    public static void showResetWalletDialog(final Context context, boolean forceClose) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(R.string.wallet_lock_reset_wallet_title);
        dialogBuilder.setMessage(R.string.wallet_lock_reset_wallet_message);
        //Inverting dialog answers to prevent accidental wallet reset
        dialogBuilder.setNegativeButton(R.string.wallet_lock_reset_wallet_title, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                WalletApplication.getInstance().wipe(context);
            }
        });
        dialogBuilder.setPositiveButton(android.R.string.no, forceClose ? new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                WalletApplication.getInstance().killAllActivities();
            }
        } : null);
        dialogBuilder.setCancelable(false);
        Dialog dialog = dialogBuilder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    public String getRemainingAttemptsMessage() {
        int failCount = prefs.getStringSet(PREFS_FAILED_PINS, new HashSet<String>()).size();
        int attemptsRemaining = FAIL_LIMIT - failCount;
        return context.getResources().getQuantityString(R.plurals.wallet_lock_attempts_remaining,
                attemptsRemaining, attemptsRemaining);
    }

    private void wipeWallet() {
        showResetWalletDialog(context, true);
    }

    // returns true if the wallet is wiped
    public static boolean handleLockedForever(Context context, boolean showDialog) {
        PinRetryController pinRetryController = new PinRetryController(context);
        if (pinRetryController.isLockedForever()) {
            if (showDialog) {
                pinRetryController.wipeWallet();
            }
            return true;
        }
        return false;
    }

    public static boolean handleLockedForever(Context context) {
        return handleLockedForever(context, true);
    }

    public void storeSecureTime(Date date) {
        prefs.edit().putLong(PREFS_SECURE_TIME, date.getTime()).apply();
    }
}
