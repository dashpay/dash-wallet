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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Strings;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import org.dash.wallet.common.data.CurrencyInfo;
import org.jetbrains.annotations.NotNull;
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

    public static final String PREFS_KEY_BTC_PRECISION = "btc_precision"; // TODO: this never changes. We might want to remove this preference and keep as a constant if it's still needed.
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

    private static final String PREFS_REMIND_ENABLE_FINGERPRINT = "remind_enable_fingerprint";
    private static final String PREFS_ENABLE_FINGERPRINT = "enable_fingerprint";
    public static final String PREFS_RESTORING_BACKUP = "restoring_backup";
    public static final String PREFS_V7_REDESIGN_TUTORIAL_COMPLETED = "v7_tutorial_completed";
    public static final String PREFS_PIN_LENGTH = "pin_length";
    public static final String PREFS_LAST_SEEN_NOTIFICATION_TIME = "last_seen_notification_time";
    private static final String PREFS_IMGUR_DELETE_HASH = "imgur_delete_hash";
    private static final String PREFS_UPLOAD_POLICY = "upload_policy_accepted_";
    private static final String PREFS_DEV_MODE = "developer_mode";
    private static final String PREFS_INVITER = "inviter";
    private static final String PREFS_INVITER_CONTACT_REQUEST_SENT_INFO = "inviter_contact_request_sent_info";
    private static final String PREFS_ONBOARDING_STAGE = "onboarding_state";
    private static final String PREFS_ONBOARDING_INVITE = "inviter_onboarding_invite";
    private static final String PREFS_ONBOARDING_INVITE_USERNAME = "inviter_onboarding_invite_username";
    private static final String PREFS_ONBOARDING_INVITE_PROCESSING = "inviter_onboarding_invite_processing";

    public static final String PREFS_KEY_LAST_LIQUID_BALANCE = "last_liquid_balance";
    public static final String PREFS_KEY_LAST_UPHOLD_BALANCE = "last_uphold_balance";

    private static final int PREFS_DEFAULT_BTC_SHIFT = 0;
    private static final int PREFS_DEFAULT_BTC_PRECISION = 8;
    public static final String PREFS_KEY_IS_DASH_TO_FIAT_DIRECTION = "is_dash_to_fiat_direction";
    public static final String PREFS_KEY_SHOW_NOTIFICATIONS_EXPLAINER = "show_notifications_explainer";

    private static final long DISABLE_NOTIFICATIONS = -1;

    // Explore Dash
    public static final String PREFS_KEY_HAS_INFO_SCREEN_BEEN_SHOWN_ALREADY = "has_info_screen_been_shown";
    public static final String PREFS_KEY_HAS_LOCATION_DIALOG_BEEN_SHOWN = "has_location_dialog_been_shown";
    public static final String PREFS_KEY_EXPLORE_DATABASE_NAME = "explore_database_name";

    // CrowdNode
    public static final String PREFS_KEY_CROWDNODE_ACCOUNT_ADDRESS = "crowdnode_account_address";
    public static final String PREFS_KEY_CROWDNODE_PRIMARY_ADDRESS = "crowdnode_primary_address";

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

    @Nullable
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

    @Nullable
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

    @Nullable
    public String getExchangeCurrencyCode() {
        String currencyCode = prefs.getString(PREFS_KEY_EXCHANGE_CURRENCY, null);
        // previous versions of the app (prior to 7.3.3) may have stored an obsolete
        // currency code in the preferences.  Let's change to the most up to date.
        return CurrencyInfo.getOtherName(currencyCode);
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

    public long getLastSeenNotificationTime() {
        return prefs.getLong(PREFS_LAST_SEEN_NOTIFICATION_TIME, 0);
    }

    public boolean areNotificationsDisabled() {
        return getLastSeenNotificationTime() == DISABLE_NOTIFICATIONS;
    }

    public void disableNotifications() {
        setLastSeenNotificationTime(DISABLE_NOTIFICATIONS);
    }

    public void setLastSeenNotificationTime(long lastSeenNotificationTime) {
        prefs.edit().putLong(PREFS_LAST_SEEN_NOTIFICATION_TIME, lastSeenNotificationTime).apply();
    }

    public String getImgurDeleteHash() {
        return prefs.getString(PREFS_IMGUR_DELETE_HASH, "");
    }

    public void setImgurDeleteHash(String deleteHash) {
        prefs.edit().putString(PREFS_IMGUR_DELETE_HASH, deleteHash).apply();
    }

    public Boolean getAcceptedUploadPolicy(String service) {
        return prefs.getBoolean(PREFS_UPLOAD_POLICY + service, false);
    }

    public void setAcceptedUploadPolicy(String service, Boolean accepted) {
        prefs.edit().putBoolean(PREFS_UPLOAD_POLICY + service, accepted).apply();
    }

    public Boolean getDeveloperMode() {
        return BuildConfig.DEBUG || prefs.getBoolean(PREFS_DEV_MODE, false);
    }

    public void setDeveloperMode(boolean activate) {
        prefs.edit().putBoolean(PREFS_DEV_MODE, activate).apply();
    }

    public String getInviter() {
        return prefs.getString(PREFS_INVITER, null);
    }

    public void setInviter(String iviter) {
        prefs.edit().putString(PREFS_INVITER, iviter).apply();
    }

    public Boolean getInviterContactRequestSentInfoShown() {
        return prefs.getBoolean(PREFS_INVITER_CONTACT_REQUEST_SENT_INFO, false);
    }

    public void setInviterContactRequestSentInfoShown(Boolean shown) {
        prefs.edit().putBoolean(PREFS_INVITER_CONTACT_REQUEST_SENT_INFO, shown).apply();
    }

    public boolean getOnboardingInviteProcessing() {
        return getOnboardingInvite() != null && prefs.getBoolean(PREFS_ONBOARDING_INVITE_PROCESSING, true);
    }

    public void setOnboardingInviteProcessingDone() {
        prefs.edit().putBoolean(PREFS_ONBOARDING_INVITE_PROCESSING, false).apply();
    }

    public int getOnboardingStage() {
        return prefs.getInt(PREFS_ONBOARDING_STAGE, 0);
    }

    public void setOnboardingStage(final int onboardingStage) {
        prefs.edit().putInt(PREFS_ONBOARDING_STAGE, onboardingStage).apply();
    }

    public Uri getOnboardingInvite() {
        String invite = prefs.getString(PREFS_ONBOARDING_INVITE, null);
        return invite != null ? Uri.parse(invite) : null;
    }

    public void setOnboardingInvite(final Uri onboardingInvite) {
        prefs.edit().putBoolean(PREFS_ONBOARDING_INVITE_PROCESSING, true).apply();
        prefs.edit().putString(PREFS_ONBOARDING_INVITE, onboardingInvite.toString()).apply();
    }

    public String getOnboardingInviteUsername() {
        return prefs.getString(PREFS_ONBOARDING_INVITE_USERNAME, null);
    }

    public void setOnboardingInviteUsername(final String username) {
        prefs.edit().putString(PREFS_ONBOARDING_INVITE_USERNAME, username).apply();
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

    public void setLastUpholdBalance(String balance) {
        prefs.edit().putString(PREFS_KEY_LAST_UPHOLD_BALANCE, balance).apply();
    }

    @NonNull
    public String getLastUpholdBalance() {
        return prefs.getString(PREFS_KEY_LAST_UPHOLD_BALANCE, "0.00");
    }

    public Boolean isDashToFiatDirection() {
        return prefs.getBoolean(PREFS_KEY_IS_DASH_TO_FIAT_DIRECTION, true);
    }

    public void setDashToFiatDirection(final Boolean isDashToFiatDirection) {
        prefs.edit().putBoolean(PREFS_KEY_IS_DASH_TO_FIAT_DIRECTION, isDashToFiatDirection).apply();
    }

    public boolean getShowNotificationsExplainer() {
        return prefs.getBoolean(PREFS_KEY_SHOW_NOTIFICATIONS_EXPLAINER, true);
    }

    public void setShowNotificationsExplainer(boolean needToShow) {
        prefs.edit().putBoolean(PREFS_KEY_SHOW_NOTIFICATIONS_EXPLAINER, needToShow).apply();
    }


    // Explore Dash

    public boolean hasExploreDashInfoScreenBeenShown() {
        return prefs.getBoolean(PREFS_KEY_HAS_INFO_SCREEN_BEEN_SHOWN_ALREADY, false);
    }

    public void setHasExploreDashInfoScreenBeenShown(boolean isShown){
        prefs.edit().putBoolean(PREFS_KEY_HAS_INFO_SCREEN_BEEN_SHOWN_ALREADY, isShown).apply();
    }

    public boolean hasExploreDashLocationDialogBeenShown() {
        return prefs.getBoolean(PREFS_KEY_HAS_LOCATION_DIALOG_BEEN_SHOWN, false);
    }

    public void setHasExploreDashLocationDialogBeenShown(boolean isShown) {
        prefs.edit().putBoolean(PREFS_KEY_HAS_LOCATION_DIALOG_BEEN_SHOWN, isShown).apply();
    }

    public String setExploreDatabaseName(Long timestamp) {
        String dbName = "explore-database-" + timestamp;
        prefs.edit().putString(PREFS_KEY_EXPLORE_DATABASE_NAME, dbName).apply();
        return dbName;
    }

    @NonNull
    public String getExploreDatabaseName() {
        return prefs.getString(PREFS_KEY_EXPLORE_DATABASE_NAME, "explore-database");
    }

    // CrowdNode

    @NonNull
    public String getCrowdNodeAccountAddress() {
        return prefs.getString(PREFS_KEY_CROWDNODE_ACCOUNT_ADDRESS, "");
    }

    public void setCrowdNodeAccountAddress(@NonNull String address) {
        prefs.edit().putString(PREFS_KEY_CROWDNODE_ACCOUNT_ADDRESS, address).apply();
    }

    @NonNull
    public String getCrowdNodePrimaryAddress() {
        return prefs.getString(PREFS_KEY_CROWDNODE_PRIMARY_ADDRESS, "");
    }

    public void setCrowdNodePrimaryAddress(@NonNull String address) {
        prefs.edit().putString(PREFS_KEY_CROWDNODE_PRIMARY_ADDRESS, address).apply();
    }
}
