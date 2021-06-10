/*
 * Copyright 2014-2015 the original author or authors.
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.net.Uri;
import android.text.format.DateUtils;

import com.google.common.base.Strings;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * @author Andreas Schildbach
 */
public class Configuration {
    public final int lastVersionCode;

    private final SharedPreferences prefs;
    private final Resources res;

    public static final String PREFS_KEY_BTC_PRECISION = "btc_precision";
    public static final String PREFS_KEY_OWN_NAME = "own_name";
    public static final String PREFS_KEY_HIDE_BALANCE = "hide_balance";
    public static final String PREFS_KEY_SEND_COINS_AUTOCLOSE = "send_coins_autoclose";
    public static final String PREFS_KEY_CONNECTIVITY_NOTIFICATION = "connectivity_notification";
    public static final String PREFS_KEY_EXCHANGE_CURRENCY = "exchange_currency";
    public static final String PREFS_KEY_EXCHANGE_CURRENCY_DETECTED = "exchange_currency_detected";
    public static final String PREFS_KEY_TRUSTED_PEER = "trusted_peer";
    public static final String PREFS_KEY_TRUSTED_PEER_ONLY = "trusted_peer_only";
    public static final String PREFS_KEY_BLOCK_EXPLORER = "block_explorer";
    public static final String PREFS_KEY_DATA_USAGE = "data_usage";
    public static final String PREFS_KEY_REMIND_BALANCE = "remind_balance";
    public static final String PREFS_KEY_DISCLAIMER = "disclaimer";
    private static final String PREFS_KEY_LABS_QR_PAYMENT_REQUEST = "labs_qr_payment_request";
    private static final String PREFS_KEY_PREVIOUS_VERSION = "previous_version";
    public static final String PREFS_KEY_AUTO_LOGOUT_ENABLED = "auto_logout_enabled";
    public static final String PREFS_KEY_AUTO_LOGOUT_MINUTES = "auto_logout_minutes";
    private static final String PREFS_KEY_SPENDING_CONFIRMATION_ENABLED = "spending_confirmation_enabled";
    private static final String PREFS_KEY_BIOMETRIC_LIMIT = "biometric_limit";

    private static final String PREFS_KEY_LAST_VERSION = "last_version";
    private static final String PREFS_KEY_LAST_USED = "last_used";
    private static final String PREFS_KEY_BEST_CHAIN_HEIGHT_EVER = "best_chain_height_ever";
    private static final String PREFS_KEY_LAST_EXCHANGE_DIRECTION = "last_exchange_direction";
    private static final String PREFS_KEY_CHANGE_LOG_VERSION = "change_log_version";
    public static final String PREFS_KEY_REMIND_BACKUP = "remind_backup";
    private static final String PREFS_KEY_LAST_BACKUP = "last_backup";
    public static final String PREFS_KEY_REMIND_BACKUP_SEED = "remind_backup_seed";
    private static final String PREFS_KEY_LAST_BACKUP_SEED_TIME = "last_backup_seed_time";
    private static final String PREFS_KEY_LAST_RESTORE = "last_restore";
    private static final String PREFS_KEY_LAST_ENCRYPT_KEYS = "last_encrypt_keys";
    private static final String PREFS_KEY_LAST_BLOCKCHAIN_RESET = "last_blockchain_reset";
    private static final String PREFS_KEY_LAST_BLUETOOTH_ADDRESS = "last_bluetooth_address";

    private static final String PREFS_REMIND_ENABLE_FINGERPRINT = "remind_enable_fingerprint";
    private static final String PREFS_ENABLE_FINGERPRINT = "enable_fingerprint";
    public static final String PREFS_RESTORING_BACKUP = "restoring_backup";
    public static final String PREFS_V7_REDESIGN_TUTORIAL_COMPLETED = "v7_tutorial_completed";
    public static final String PREFS_PIN_LENGTH = "pin_length";

    private static final int PREFS_DEFAULT_BTC_SHIFT = 0;
    private static final int PREFS_DEFAULT_BTC_PRECISION = 8;

    private static final Logger log = LoggerFactory.getLogger(Configuration.class);

    public Configuration(final SharedPreferences prefs, final Resources res) {
        this.prefs = prefs;
        this.res = res;

        this.lastVersionCode = prefs.getInt(PREFS_KEY_LAST_VERSION, 0);
    }

    @SuppressLint("ApplySharedPref")
    public void clear() {
        Editor edit = prefs.edit();
        try {
            edit.clear();
        } finally {
            edit.commit();
        }
    }

    private int getBtcPrecision() {
        final String precision = prefs.getString(PREFS_KEY_BTC_PRECISION, null);
        if (precision != null)
            return precision.charAt(0) - '0';
        else
            return PREFS_DEFAULT_BTC_PRECISION;
    }

    public int getBtcShift() {
        final String precision = prefs.getString(PREFS_KEY_BTC_PRECISION, null);
        if (precision != null)
            return precision.length() == 3 ? precision.charAt(2) - '0' : 0;
        else
            return PREFS_DEFAULT_BTC_SHIFT;
    }

    public Coin getBtcBase() {
        final int shift = getBtcShift();
        if (shift == 0)
            return Coin.COIN;
        else if (shift == 3)
            return Coin.MILLICOIN;
        else if (shift == 6)
            return Coin.MICROCOIN;
        else
            throw new IllegalStateException("cannot handle shift: " + shift);
    }

    public MonetaryFormat getFormat() {
        final int shift = PREFS_DEFAULT_BTC_SHIFT;
        final int minPrecision = 2;
        final int numberToRepeat = 1;
        final int decimalRepetitions = (PREFS_DEFAULT_BTC_PRECISION - minPrecision) / numberToRepeat;
        return new MonetaryFormat().shift(shift).minDecimals(minPrecision).repeatOptionalDecimals(numberToRepeat,
                decimalRepetitions);
    }

    public MonetaryFormat getMaxPrecisionFormat() {
        final int shift = getBtcShift();
        if (shift == 0)
            return new MonetaryFormat().shift(0).minDecimals(2).optionalDecimals(2, 2, 2);
        else if (shift == 3)
            return new MonetaryFormat().shift(3).minDecimals(2).optionalDecimals(2, 1);
        else
            return new MonetaryFormat().shift(6).minDecimals(0).optionalDecimals(2);
    }

    public String getOwnName() {
        return Strings.emptyToNull(prefs.getString(PREFS_KEY_OWN_NAME, "").trim());
    }

    public boolean getHideBalance() {
        return prefs.getBoolean(PREFS_KEY_HIDE_BALANCE, false);
    }

    public void setHideBalance(final boolean hideBalance) {
        prefs.edit().putBoolean(PREFS_KEY_HIDE_BALANCE, hideBalance).apply();
    }

    public boolean getSendCoinsAutoclose() {
        return prefs.getBoolean(PREFS_KEY_SEND_COINS_AUTOCLOSE, true);
    }

    public boolean getConnectivityNotificationEnabled() {
        return prefs.getBoolean(PREFS_KEY_CONNECTIVITY_NOTIFICATION, false);
    }

    public String getTrustedPeerHost() {
        return Strings.emptyToNull(prefs.getString(PREFS_KEY_TRUSTED_PEER, "").trim());
    }

    public boolean getTrustedPeerOnly() {
        return prefs.getBoolean(PREFS_KEY_TRUSTED_PEER_ONLY, false);
    }

    public Uri getBlockExplorer(int defValueResValue) {
        return Uri.parse(prefs.getString(PREFS_KEY_BLOCK_EXPLORER, res.getStringArray(defValueResValue)[0]));
    }

    public boolean remindBalance() {
        return prefs.getBoolean(PREFS_KEY_REMIND_BALANCE, true);
    }

    public void setRemindBalance(final boolean remindBalance) {
        prefs.edit().putBoolean(PREFS_KEY_REMIND_BALANCE, remindBalance).apply();
    }

    public boolean getAutoLogoutEnabled() {
        return prefs.getBoolean(PREFS_KEY_AUTO_LOGOUT_ENABLED, true);
    }

    public void setAutoLogoutEnabled(final boolean enabled) {
        prefs.edit().putBoolean(PREFS_KEY_AUTO_LOGOUT_ENABLED, enabled).apply();
    }

    public int getAutoLogoutMinutes() {
        return prefs.getInt(PREFS_KEY_AUTO_LOGOUT_MINUTES, 1);
    }

    public void setAutoLogoutMinutes(final int minutes) {
        prefs.edit().putInt(PREFS_KEY_AUTO_LOGOUT_MINUTES, minutes).apply();
    }

    public boolean getSpendingConfirmationEnabled() {
        return prefs.getBoolean(PREFS_KEY_SPENDING_CONFIRMATION_ENABLED, true);
    }

    public void setSpendingConfirmationEnabled(final boolean enabled) {
        prefs.edit().putBoolean(PREFS_KEY_SPENDING_CONFIRMATION_ENABLED, enabled).apply();
    }

    public float getBiometricLimit() {
        return prefs.getFloat(PREFS_KEY_BIOMETRIC_LIMIT, 0.5f);
    }

    public void setBiometricLimit(final float limit) {
        prefs.edit().putFloat(PREFS_KEY_BIOMETRIC_LIMIT, limit).apply();
    }

    public boolean remindBackup() {
        return prefs.getBoolean(PREFS_KEY_REMIND_BACKUP, true);
    }

    public long getLastBackupTime() {
        return prefs.getLong(PREFS_KEY_LAST_BACKUP, 0);
    }

    public void armBackupReminder() {
        prefs.edit().putBoolean(PREFS_KEY_REMIND_BACKUP, true).apply();
    }

    public void disarmBackupReminder() {
        prefs.edit().putBoolean(PREFS_KEY_REMIND_BACKUP, false)
                .putLong(PREFS_KEY_LAST_BACKUP, System.currentTimeMillis()).apply();
    }

    public void armBackupSeedReminder() {
        prefs.edit()
                .putBoolean(PREFS_KEY_REMIND_BACKUP_SEED, true)
                .apply();
    }

    public void disarmBackupSeedReminder() {
        prefs.edit()
                .putBoolean(PREFS_KEY_REMIND_BACKUP_SEED, false)
                .apply();
    }

    public boolean getRemindBackupSeed() {
        return prefs.getBoolean(PREFS_KEY_REMIND_BACKUP_SEED, true);
    }

    public long getLastRestoreTime() {
        return prefs.getLong(PREFS_KEY_LAST_RESTORE, 0);
    }

    public void updateLastRestoreTime() {
        prefs.edit().putLong(PREFS_KEY_LAST_RESTORE, System.currentTimeMillis()).apply();
    }

    public boolean lastBackupSeedReminderMoreThan24hAgo() {
        long lastReminder = prefs.getLong(PREFS_KEY_LAST_BACKUP_SEED_TIME, 0);
        if (lastReminder > 0) {
            long now = System.currentTimeMillis();
            return now - lastReminder > TimeUnit.HOURS.toMillis(24);
        }
        return false;
    }

    public long getLastBackupSeedTime() {
        return prefs.getLong(PREFS_KEY_LAST_BACKUP_SEED_TIME, 0);
    }

    public void setLastBackupSeedTime() {
        prefs.edit()
                .putLong(PREFS_KEY_LAST_BACKUP_SEED_TIME, System.currentTimeMillis())
                .apply();
    }

    public void resetBackupSeedReminderTimer() {
        prefs.edit().putLong(PREFS_KEY_LAST_BACKUP_SEED_TIME, -1).apply();
    }

    public boolean getDisclaimerEnabled() {
        return prefs.getBoolean(PREFS_KEY_DISCLAIMER, true);
    }

    public void setDisclaimerEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREFS_KEY_DISCLAIMER, enabled).apply();
    }

    public String getExchangeCurrencyCode() {
        return prefs.getString(PREFS_KEY_EXCHANGE_CURRENCY, null);
    }

    public void setExchangeCurrencyCode(final String exchangeCurrencyCode) {
        prefs.edit().putString(PREFS_KEY_EXCHANGE_CURRENCY, exchangeCurrencyCode).apply();
    }

    public boolean getExchangeCurrencyCodeDetected() {
        return prefs.getBoolean(PREFS_KEY_EXCHANGE_CURRENCY_DETECTED, false);
    }

    public void setExchangeCurrencyCodeDetected(boolean detected) {
        prefs.edit().putBoolean(PREFS_KEY_EXCHANGE_CURRENCY_DETECTED, detected).apply();
    }

    /**
     * @return whether the app was ever upgraded of if it's running on the first version in which
     * it was installed
     */
    public boolean wasUpgraded() {
        return prefs.getInt(PREFS_KEY_PREVIOUS_VERSION, 0) != 0;
    }

    public boolean getQrPaymentRequestEnabled() {
        return prefs.getBoolean(PREFS_KEY_LABS_QR_PAYMENT_REQUEST, false);
    }

    public boolean versionCodeCrossed(final int currentVersionCode, final int triggeringVersionCode) {
        final boolean wasBelow = lastVersionCode < triggeringVersionCode;
        final boolean wasUsedBefore = lastVersionCode > 0;
        final boolean isNowAbove = currentVersionCode >= triggeringVersionCode;

        return wasUsedBefore && wasBelow && isNowAbove;
    }

    public void updateLastVersionCode(final int currentVersionCode) {
        Editor editor = prefs.edit();
        editor.putInt(PREFS_KEY_PREVIOUS_VERSION, prefs.getInt(PREFS_KEY_LAST_VERSION, 0));
        editor.putInt(PREFS_KEY_LAST_VERSION, currentVersionCode);
        editor.apply();

        if (currentVersionCode > lastVersionCode)
            log.info("detected app upgrade: " + lastVersionCode + " -> " + currentVersionCode);
        else if (currentVersionCode < lastVersionCode)
            log.warn("detected app downgrade: " + lastVersionCode + " -> " + currentVersionCode);
    }

    public boolean hasBeenUsed() {
        return prefs.contains(PREFS_KEY_LAST_USED);
    }

    public long getLastUsedAgo() {
        final long now = System.currentTimeMillis();

        return now - prefs.getLong(PREFS_KEY_LAST_USED, 0);
    }

    public void touchLastUsed() {
        final long prefsLastUsed = prefs.getLong(PREFS_KEY_LAST_USED, 0);
        final long now = System.currentTimeMillis();
        prefs.edit().putLong(PREFS_KEY_LAST_USED, now).apply();

        log.info("just being used - last used {} minutes ago", (now - prefsLastUsed) / DateUtils.MINUTE_IN_MILLIS);
    }

    public int getBestChainHeightEver() {
        return prefs.getInt(PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, 0);
    }

    public void maybeIncrementBestChainHeightEver(final int bestChainHeightEver) {
        if (bestChainHeightEver > getBestChainHeightEver())
            prefs.edit().putInt(PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, bestChainHeightEver).apply();
    }

    public boolean isRestoringBackup() {
        return prefs.getBoolean(PREFS_RESTORING_BACKUP, false);
    }

    public void setRestoringBackup(final boolean isRestoringBackup) {
        prefs.edit().putBoolean(PREFS_RESTORING_BACKUP, isRestoringBackup).apply();
    }

    public boolean getLastExchangeDirection() {
        return prefs.getBoolean(PREFS_KEY_LAST_EXCHANGE_DIRECTION, true);
    }

    public void setLastExchangeDirection(final boolean exchangeDirection) {
        prefs.edit().putBoolean(PREFS_KEY_LAST_EXCHANGE_DIRECTION, exchangeDirection).apply();
    }

    public boolean changeLogVersionCodeCrossed(final int currentVersionCode, final int triggeringVersionCode) {
        final int changeLogVersion = prefs.getInt(PREFS_KEY_CHANGE_LOG_VERSION, 0);

        final boolean wasBelow = changeLogVersion < triggeringVersionCode;
        final boolean wasUsedBefore = changeLogVersion > 0;
        final boolean isNowAbove = currentVersionCode >= triggeringVersionCode;

        prefs.edit().putInt(PREFS_KEY_CHANGE_LOG_VERSION, currentVersionCode).apply();

        return /* wasUsedBefore && */wasBelow && isNowAbove;
    }

    public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public boolean getRemindEnableFingerprint() {
        return prefs.getBoolean(PREFS_REMIND_ENABLE_FINGERPRINT, true);
    }

    public void setRemindEnableFingerprint(boolean remind) {
        prefs.edit().putBoolean(PREFS_REMIND_ENABLE_FINGERPRINT, remind).apply();
    }

    public boolean getV7TutorialCompleted() {
        return prefs.getBoolean(PREFS_V7_REDESIGN_TUTORIAL_COMPLETED, false);
    }

    public void setV7TutorialCompleted() {
        prefs.edit().putBoolean(PREFS_V7_REDESIGN_TUTORIAL_COMPLETED, true).apply();
    }

    public boolean getEnableFingerprint() {
        return prefs.getBoolean(PREFS_ENABLE_FINGERPRINT, false);
    }

    public void setEnableFingerprint(boolean remind) {
        prefs.edit().putBoolean(PREFS_ENABLE_FINGERPRINT, remind).apply();
    }

    public int getPinLength() {
        return prefs.getInt(PREFS_PIN_LENGTH, 4);
    }

    public void setPinLength(int pinLength) {
        prefs.edit().putInt(PREFS_PIN_LENGTH, pinLength).apply();
    }

    public long getLastEncryptKeysTime() {
        return prefs.getLong(PREFS_KEY_LAST_ENCRYPT_KEYS, 0);
    }

    public void updateLastEncryptKeysTime() {
        prefs.edit().putLong(PREFS_KEY_LAST_ENCRYPT_KEYS, System.currentTimeMillis()).apply();
    }

    public long getLastBlockchainResetTime() {
        return prefs.getLong(PREFS_KEY_LAST_BLOCKCHAIN_RESET, 0);
    }

    public void updateLastBlockchainResetTime() {
        prefs.edit().putLong(PREFS_KEY_LAST_BLOCKCHAIN_RESET, System.currentTimeMillis()).apply();
    }

}
