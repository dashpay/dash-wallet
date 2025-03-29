/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay.utils

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.schildbach.wallet.ui.more.TxMetadataSaveFrequency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import org.dash.wallet.common.util.security.EncryptionProvider
import javax.inject.Inject
import javax.inject.Singleton

data class TransactionMetadataSettings(
    val saveToNetwork: Boolean = false,
    val saveFrequency: TxMetadataSaveFrequency = TxMetadataSaveFrequency.defaultOption,
    val savePaymentCategory: Boolean = false,
    val saveTaxCategory: Boolean = false,
    val saveExchangeRates: Boolean = false,
    val savePrivateMemos: Boolean = false,
    val saveGiftcardInfo: Boolean = true,
    val saveAfterTimestamp: Long = System.currentTimeMillis()
) {
    fun shouldSavePaymentCategory() = saveToNetwork && savePaymentCategory
    fun shouldSaveTaxCategory() = saveToNetwork && saveTaxCategory
    fun shouldSaveExchangeRates() = saveToNetwork && saveExchangeRates
    fun shouldSavePrivateMemos() = saveToNetwork && savePrivateMemos
    fun shouldSaveGiftcardInfo() = saveToNetwork && saveGiftcardInfo
}

@Singleton
open class DashPayConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider,
    encryptionProvider: EncryptionProvider? = null
): BaseConfig(
    context,
    PREFERENCES_NAME,
    walletDataProvider,
    encryptionProvider,
    migrations = listOf(
        SharedPreferencesMigration(
            context = context,
            sharedPreferencesName = context.packageName + "_preferences",
            keysToMigrate = setOf(
                LAST_SEEN_NOTIFICATION_TIME.name
            )
        )
    )
) {
    companion object {
        const val DISABLE_NOTIFICATIONS: Long = -1

        const val PREFERENCES_NAME = "dashpay"
        val LAST_SEEN_NOTIFICATION_TIME = longPreferencesKey("last_seen_notification_time")
        val LAST_METADATA_PUSH = longPreferencesKey("last_metadata_push")
        val HAS_DASH_PAY_INFO_SCREEN_BEEN_SHOWN = booleanPreferencesKey("has_dash_pay_info_screen_been_shown")
        val VOTING_INFO_SHOWN = booleanPreferencesKey("voting_info_shown")
        val MIX_DASH_SHOWN = booleanPreferencesKey("mix_dash_shown")
        val KEYS_DONT_ASK_AGAIN = booleanPreferencesKey("dont_ask_again_for_keys")
        val FIRST_TIME_VOTING = booleanPreferencesKey("first_time_voting")
        val CREDIT_INFO_SHOWN = booleanPreferencesKey("credit_info_shown")
        val TOPUP_COUNTER = intPreferencesKey("topup_counter")
        val USERNAME_VOTE_COUNTER = intPreferencesKey("username_vote_counter")
        val GOOGLE_DRIVE_ACCESS_TOKEN = stringPreferencesKey("google_drive_access_token")
        // transaction metadata settings
        val TRANSACTION_METADATA_FEATURE_INSTALLED = longPreferencesKey("transaction_metadata_feature_installed")
        val TRANSACTION_METADATA_INFO_SHOWN = booleanPreferencesKey("transaction_metadata_info_shown")
        val TRANSACTION_METADATA_SAVE_TO_NETWORK = booleanPreferencesKey("transaction_metadata_save_to_network")
        val TRANSACTION_METADATA_SAVE_FREQUENCY = stringPreferencesKey("transaction_metadata_save_frequency")
        val TRANSACTION_METADATA_SAVE_PAYMENT_CATEGORY = booleanPreferencesKey("transaction_metadata_save_payment_category")
        val TRANSACTION_METADATA_SAVE_TAX_CATEGORY = booleanPreferencesKey("transaction_metadata_save_tax_category")
        val TRANSACTION_METADATA_SAVE_EXCHANGE = booleanPreferencesKey("transaction_metadata_save_exchange_rates")
        val TRANSACTION_METADATA_SAVE_MEMOS = booleanPreferencesKey("transaction_metadata_save_memos")
        val TRANSACTION_METADATA_SAVE_AFTER = longPreferencesKey("transaction_metadata_save_after")
        val TRANSACTION_METADATA_SAVE_ON_RESET = booleanPreferencesKey("transaction_metadata_save_on_reset")
    }

    open suspend fun areNotificationsDisabled(): Boolean {
        return (get(LAST_SEEN_NOTIFICATION_TIME) ?: 0) == DISABLE_NOTIFICATIONS
    }

    open suspend fun disableNotifications() {
        set(LAST_SEEN_NOTIFICATION_TIME, DISABLE_NOTIFICATIONS)
    }

    suspend fun getTopupCounter(): Int {
        val counter = get(TOPUP_COUNTER) ?: 1
        set(TOPUP_COUNTER, counter + 1)
        return counter
    }

    suspend fun getUsernameVoteCounter(): Int {
        val counter = (get(USERNAME_VOTE_COUNTER) ?: 0) + 1
        set(USERNAME_VOTE_COUNTER, counter)
        return counter
    }

    /**
     * Securely stores the Google Drive access token
     */
    suspend fun setGoogleDriveAccessToken(accessToken: String) {
        setSecuredData(GOOGLE_DRIVE_ACCESS_TOKEN, accessToken)
    }

    /**
     * Retrieves the securely stored Google Drive access token
     * @return The access token or null if not found
     */
    suspend fun getGoogleDriveAccessToken(): String? {
        return getSecuredData(GOOGLE_DRIVE_ACCESS_TOKEN)
    }

    suspend fun isTransactionMetadataInfoShown(): Boolean {
        return get(TRANSACTION_METADATA_INFO_SHOWN) ?: false
    }

    suspend fun setTransactionMetadataInfoShown() {
        return set(TRANSACTION_METADATA_INFO_SHOWN, true)
    }

    suspend fun isSavingTransactionMetadata(): Boolean {
        return get(TRANSACTION_METADATA_SAVE_TO_NETWORK) ?: false
    }

    private val transactionMetadataSettings: Flow<TransactionMetadataSettings> = data
        .map { prefs ->
            TransactionMetadataSettings(
                saveToNetwork = prefs[TRANSACTION_METADATA_SAVE_TO_NETWORK] ?: false,
                saveFrequency = TxMetadataSaveFrequency.valueOf(prefs[TRANSACTION_METADATA_SAVE_FREQUENCY] ?: TxMetadataSaveFrequency.defaultOption.name),
                savePaymentCategory = prefs[TRANSACTION_METADATA_SAVE_PAYMENT_CATEGORY] ?: false,
                saveTaxCategory = prefs[TRANSACTION_METADATA_SAVE_TAX_CATEGORY] ?: false,
                saveExchangeRates = prefs[TRANSACTION_METADATA_SAVE_EXCHANGE] ?: false,
                savePrivateMemos = prefs[TRANSACTION_METADATA_SAVE_MEMOS] ?: false
            )
        }

    fun observeTransactionMetadataSettings() = transactionMetadataSettings

    suspend fun getTransactionMetadataSettings(): TransactionMetadataSettings = withContext(Dispatchers.IO) {
        TransactionMetadataSettings(
            saveToNetwork = get(TRANSACTION_METADATA_SAVE_TO_NETWORK) ?: false,
            saveFrequency = TxMetadataSaveFrequency.valueOf(
                get(TRANSACTION_METADATA_SAVE_FREQUENCY) ?: TxMetadataSaveFrequency.defaultOption.name
            ),
            savePaymentCategory = get(TRANSACTION_METADATA_SAVE_PAYMENT_CATEGORY) ?: false,
            saveTaxCategory = get(TRANSACTION_METADATA_SAVE_TAX_CATEGORY) ?: false,
            saveExchangeRates = get(TRANSACTION_METADATA_SAVE_EXCHANGE) ?: false,
            savePrivateMemos = get(TRANSACTION_METADATA_SAVE_MEMOS) ?: false,
            saveAfterTimestamp = get(TRANSACTION_METADATA_SAVE_AFTER) ?: Long.MAX_VALUE
        )
    }

    suspend fun setTransactionMetadataSettings(settings: TransactionMetadataSettings) {
        set(TRANSACTION_METADATA_SAVE_TO_NETWORK, settings.saveToNetwork)
        set(TRANSACTION_METADATA_SAVE_FREQUENCY, settings.saveFrequency.name)
        set(TRANSACTION_METADATA_SAVE_PAYMENT_CATEGORY, settings.savePaymentCategory)
        set(TRANSACTION_METADATA_SAVE_TAX_CATEGORY, settings.saveTaxCategory)
        set(TRANSACTION_METADATA_SAVE_EXCHANGE, settings.saveExchangeRates)
        set(TRANSACTION_METADATA_SAVE_MEMOS, settings.savePrivateMemos)
    }

    suspend fun shouldSaveOnReset(): Boolean = get(TRANSACTION_METADATA_SAVE_ON_RESET) == true

    suspend fun isSavingToNetwork(): Boolean = get(TRANSACTION_METADATA_SAVE_TO_NETWORK) ?: false

    suspend fun getSaveAfterTimestamp(): Long = get(TRANSACTION_METADATA_SAVE_AFTER) ?: Long.MAX_VALUE

    suspend fun getMetadataFeatureInstalled(): Long {
        val installedDate = get(TRANSACTION_METADATA_FEATURE_INSTALLED) ?: 0
        if (installedDate == 0L) {
            set(TRANSACTION_METADATA_FEATURE_INSTALLED, System.currentTimeMillis())
        }
        return installedDate
    }
}
