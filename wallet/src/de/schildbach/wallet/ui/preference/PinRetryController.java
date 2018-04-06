package de.schildbach.wallet.ui.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.widget.Toast;

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

    public PinRetryController(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("pin_retry_controller_prefs", Context.MODE_PRIVATE);
    }

    public boolean isLocked() {
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

        if (locked) {
            showLockedAlert(lockTimeMinutes);
        }

        return locked;
    }

    public void successfulAttempt() {
        SharedPreferences.Editor prefsEditor = prefs.edit();
        prefsEditor.remove(PREFS_FAIL_HEIGHT);
        prefsEditor.remove(PREFS_FAILED_PINS);
        prefsEditor.apply();
    }

    public void failedAttempt(String pin) {
        long secureTime = prefs.getLong(PREFS_SECURE_TIME, 0);
        Set<String> failedPins = prefs.getStringSet(PREFS_FAILED_PINS, new HashSet<String>());

        if (!failedPins.contains(pin)) {
            failedPins.add(pin);

            int failCount = failedPins.size();
            SharedPreferences.Editor prefsEditor = prefs.edit();
            prefsEditor.putStringSet(PREFS_FAILED_PINS, failedPins);

            if (failCount >= FAIL_LIMIT) {
                wipeWallet();
            } else {
                prefsEditor.putLong(PREFS_FAIL_HEIGHT, secureTime + System.currentTimeMillis());
            }

            prefsEditor.apply();
        }
    }

    /**
     * Prepare alert with corresponding wait message and shows it.
     * @param lockTime in minutes.
     */
    private void showLockedAlert(long lockTime) {
        String unit;
        lockTime = (lockTime < 2) ? 1 : lockTime;

        if (lockTime < 60) {
            unit = context.getResources().getQuantityString(R.plurals.minute, (int) lockTime);
        } else {
            lockTime /= 60;
            unit = context.getResources().getQuantityString(R.plurals.hour, (int) lockTime);
        }

        String message = context.getString(R.string.wallet_lock_try_again, lockTime, unit);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(R.string.wallet_lock_wallet_disabled);
        dialogBuilder.setMessage(message);
        dialogBuilder.setNegativeButton(R.string.wallet_lock_reset, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showResetWalletDialog();
            }
        });
        dialogBuilder.setPositiveButton(android.R.string.ok, null);
        dialogBuilder.show();

    }

    private void showResetWalletDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(R.string.wallet_lock_reset_wallet_title);
        dialogBuilder.setMessage(R.string.wallet_lock_reset_wallet_message);
        //Inverting dialog answers to prevent accidental wallet reset
        dialogBuilder.setNegativeButton(android.R.string.yes, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                WalletApplication.getInstance().clearDataAndExit();
            }
        });
        dialogBuilder.setPositiveButton(android.R.string.no, null);
        dialogBuilder.show();
    }

    public String getRemainingAttemptsMessage() {
        int failCount = prefs.getStringSet(PREFS_FAILED_PINS, new HashSet<String>()).size();
        int attemptsRemaining = FAIL_LIMIT - failCount;
        return context.getResources().getQuantityString(R.plurals.wallet_lock_attempts_remaining,
                attemptsRemaining, attemptsRemaining);
    }

    private void wipeWallet() {
        Toast.makeText(context, "Locked 4ever. Wiping wallet... ", Toast.LENGTH_SHORT).show();
        //TODO: WIPE WALLET AND CLOSE THE APP
    }

    public void storeSecureTime(Date date) {
        prefs.edit().putLong(PREFS_SECURE_TIME, date.getTime()).apply();
    }

}
