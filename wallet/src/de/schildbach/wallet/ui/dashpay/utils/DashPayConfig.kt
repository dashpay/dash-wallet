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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.schildbach.wallet.Constants
import org.bitcoinj.core.NetworkParameters
import de.schildbach.wallet.ui.more.TxMetadataSaveFrequency
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import org.dash.wallet.common.util.security.EncryptionProvider
import javax.inject.Inject
import javax.inject.Singleton

data class TransactionMetadataSettings(
    /** not saved to the data store */
    val savePastTxToNetwork: Boolean = false,
    /** save future transactions */
    val saveToNetwork: Boolean = false,
    val saveFrequency: TxMetadataSaveFrequency = TxMetadataSaveFrequency.defaultOption,
    val savePaymentCategory: Boolean = true,
    val saveTaxCategory: Boolean = true,
    val saveExchangeRates: Boolean = true,
    val savePrivateMemos: Boolean = true,
    val saveGiftcardInfo: Boolean = true,
    val saveAfterTimestamp: Long = System.currentTimeMillis(),
    /* not saved to the data store */
    val modified: Boolean = false
) {
    fun shouldSavePaymentCategory(saveAll: Boolean) = saveAll || savePaymentCategory
    fun shouldSaveTaxCategory(saveAll: Boolean) = saveAll || saveTaxCategory
    fun shouldSaveExchangeRates(saveAll: Boolean) = saveAll || saveExchangeRates
    fun shouldSavePrivateMemos(saveAll: Boolean) = saveAll || savePrivateMemos
    fun shouldSaveGiftcardInfo(saveAll: Boolean) = saveAll || saveGiftcardInfo

    /** determine if relavant changes have been made */
    fun isEqual(other: TransactionMetadataSettings?): Boolean {
        return other != null && other.savePastTxToNetwork == this.savePastTxToNetwork &&
            other.saveToNetwork == this.saveToNetwork &&
            other.saveFrequency == this.saveFrequency &&
            other.savePaymentCategory == this.savePaymentCategory &&
            other.saveExchangeRates == this.saveExchangeRates &&
            other.saveTaxCategory == this.saveTaxCategory &&
            other.savePrivateMemos == this.savePrivateMemos &&
            other.saveGiftcardInfo == this.saveGiftcardInfo
    }

    /** returns true if saveToNetwork is false or saveToNetwork is true while one of the categories is selection */
    fun isValid() = !saveToNetwork || (saveTaxCategory || savePaymentCategory ||
            saveGiftcardInfo || saveExchangeRates || savePrivateMemos)
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
        private val log = org.slf4j.LoggerFactory.getLogger(DashPayConfig::class.java)

        const val DISABLE_NOTIFICATIONS: Long = -1

        const val PREFERENCES_NAME = "dashpay"
        val LAST_SEEN_NOTIFICATION_TIME = longPreferencesKey("last_seen_notification_time")
        val LAST_METADATA_PUSH = longPreferencesKey("last_metadata_push")
        val HAS_DASH_PAY_INFO_SCREEN_BEEN_SHOWN = booleanPreferencesKey("has_dash_pay_info_screen_been_shown")
        val VOTING_INFO_SHOWN = booleanPreferencesKey("voting_info_shown")
        val KEYS_DONT_ASK_AGAIN = booleanPreferencesKey("dont_ask_again_for_keys")
        val FIRST_TIME_VOTING = booleanPreferencesKey("first_time_voting")
        val CREDIT_INFO_SHOWN = booleanPreferencesKey("credit_info_shown")
        val TOPUP_COUNTER = intPreferencesKey("topup_counter")
        val USERNAME_VOTE_COUNTER = intPreferencesKey("username_vote_counter")
        val GOOGLE_DRIVE_ACCESS_TOKEN = stringPreferencesKey("google_drive_access_token")
        val FREQUENT_CONTACTS = stringSetPreferencesKey("frequent_contacts")
        val UPGRADE_IDENTITY_REQUIRED = booleanPreferencesKey("upgrade_identity_required")
        // transaction metadata settings
        val TRANSACTION_METADATA_FEATURE_INSTALLED = longPreferencesKey("transaction_metadata_feature_installed")
        val TRANSACTION_METADATA_INFO_SHOWN = booleanPreferencesKey("transaction_metadata_info_shown")
        val TRANSACTION_METADATA_SAVE_TO_NETWORK = booleanPreferencesKey("transaction_metadata_save_to_network")
        val TRANSACTION_METADATA_SAVE_FREQUENCY = stringPreferencesKey("transaction_metadata_save_frequency")
        val TRANSACTION_METADATA_SAVE_PAYMENT_CATEGORY = booleanPreferencesKey("transaction_metadata_save_payment_category")
        val TRANSACTION_METADATA_SAVE_TAX_CATEGORY = booleanPreferencesKey("transaction_metadata_save_tax_category")
        val TRANSACTION_METADATA_SAVE_EXCHANGE = booleanPreferencesKey("transaction_metadata_save_exchange_rates")
        val TRANSACTION_METADATA_SAVE_MEMOS = booleanPreferencesKey("transaction_metadata_save_memos")
        val TRANSACTION_METADATA_SAVE_GIFT_CARD_INFO = booleanPreferencesKey("transaction_metadata_save_gift_card_info")
        val TRANSACTION_METADATA_SAVE_AFTER = longPreferencesKey("transaction_metadata_save_after")
        val TRANSACTION_METADATA_SAVE_ON_RESET = booleanPreferencesKey("transaction_metadata_save_on_reset")
        val TRANSACTION_METADATA_LAST_SAVE_WORK_ID = stringPreferencesKey("transaction_metadata_last_save_work_id")
        val TRANSACTION_METADATA_LAST_PAST_SAVE = longPreferencesKey("transaction_metadata_last_save_work_timestamp")
        val INVITATION_LINK = stringPreferencesKey("invitation_link")
        val INVITATION_FROM_ONBOARDING = booleanPreferencesKey("invitation_link_from_onboarding")

        /**
         * Whether the "Transfers take different times" sheet (Figma
         * 1740:16412) has been shown on the shielded internal-transfer
         * screen. It auto-opens once on the user's first visit and is set
         * on dismissal; the nav-bar info icon re-opens it manually any
         * time. Follows the *_INFO_SHOWN precedents above.
         */
        val SHIELDED_TIMING_INFO_SHOWN = booleanPreferencesKey("shielded_timing_info_shown")

        /**
         * Whether the post-upgrade MIXED-FUNDS (CoinJoin) migration has been
         * handled for this wallet — set once the user picks an option that
         * actually spends (shield / combine) or dismisses the prompt for
         * good. Deliberately NOT set when an attempt provably spent nothing
         * ([de.schildbach.wallet.service.platform.sdk.MixedFundsMigrationOutcome.NOT_ATTEMPTED]),
         * so a failed migration is re-offered on the next startup.
         *
         * The flag is a nag-suppressor, not the authority: the prompt also
         * requires a non-zero CoinJoin-keychain balance, so a completed
         * migration stops it regardless of whether this ever persisted.
         * See [de.schildbach.wallet.service.platform.sdk.CoinJoinFundsMigrationService].
         */
        val MIXED_FUNDS_MIGRATION_DONE = booleanPreferencesKey("mixed_funds_migration_done")

        /**
         * Phase 3c of the dashj → Kotlin SDK migration
         * (`docs/kotlin-sdk-migration-plan.md`): route read-only DPNS
         * username resolution/search through the Dash Platform Kotlin SDK
         * instead of dashj-platform. Default OFF — the dashj path is
         * untouched unless this is explicitly enabled, and any SDK-path
         * failure falls back to dashj per call. Re-read on every lookup, so
         * toggling either direction is instant (no restart).
         * See [de.schildbach.wallet.service.platform.sdk.SdkUsernameQueries].
         */
        val USE_KOTLIN_SDK_DPNS_READS = booleanPreferencesKey("use_kotlin_sdk_dpns_reads")

        /**
         * Phase 3e of the dashj → Kotlin SDK migration
         * (`docs/kotlin-sdk-migration-plan.md`): route the DashPay WRITE
         * operations (send contact request, create/update profile) through
         * the Dash Platform Kotlin SDK instead of dashj-platform. Default
         * OFF. Unlike the read flag, a failed SDK write only falls back to
         * dashj when the SDK path DEFINITIVELY did not broadcast (see
         * [de.schildbach.wallet.service.platform.sdk.SdkDashPayWrites] for
         * the decision table) — an ambiguous failure surfaces as an error
         * exactly like a dashj broadcast failure would, never as a silent
         * second broadcast. Re-read on every write, so toggling either
         * direction is instant (no restart).
         */
        val USE_KOTLIN_SDK_DASHPAY_WRITES = booleanPreferencesKey("use_kotlin_sdk_dashpay_writes")

        /**
         * Phase 4 of the dashj → Kotlin SDK migration
         * (`docs/kotlin-sdk-migration-plan.md`): enable the SHIELDED
         * (Orchard) balance runtime — the per-network commitment-tree
         * store, the wallet's shielded sub-wallet binding, the background
         * shielded sync loop, and the shielded spend operations (shield /
         * transfer / unshield / withdraw). Default OFF: with the flag off
         * the shielded service is provably inert (no native call, no
         * SQLite file, no sync loop). Spends follow the
         * [de.schildbach.wallet.service.platform.sdk.SdkWriteResult]
         * no-double-broadcast contract — see
         * [de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService].
         * Re-read on every call, so toggling ON is instant; toggling OFF
         * stops gating new work but does not tear down a running sync loop
         * (call `ShieldedBalanceService.stop()` for that).
         */
        val USE_KOTLIN_SDK_SHIELDED = booleanPreferencesKey("use_kotlin_sdk_shielded")

        /**
         * Phase 4 (invitation slice) of the dashj → Kotlin SDK migration
         * (`docs/kotlin-sdk-migration-plan.md`): route the TRANSPARENT (L1)
         * DashPay INVITATION create + claim through the Kotlin SDK's DIP-13
         * invitation wrappers
         * ([org.dashfoundation.dashsdk.identity.IdentityRegistration.createInvitation]
         * / [org.dashfoundation.dashsdk.identity.IdentityRegistration.claimInvitation])
         * instead of the dashj asset-lock voucher path
         * ([de.schildbach.wallet.service.platform.TopUpRepository.createInviteFundingTransaction]
         * / [de.schildbach.wallet.service.platform.TopUpRepository.obtainAssetLockTransaction]).
         * The transparent counterpart of the shielded-invite flag above.
         *
         * Default OFF: with the flag off (or the cutover not yet committed)
         * every L1-invite entry point returns [SdkWriteResult.NotBroadcast]
         * without touching the SDK, and the dashj create/claim paths are
         * byte-identical to before. Because the L1 funds live in the SDK only
         * AFTER the cutover, the routing seams additionally gate on the
         * COMMITTED CUTOVER (same as
         * [de.schildbach.wallet.service.platform.sdk.SdkTransparentUsernameCreation]).
         * See [de.schildbach.wallet.service.platform.sdk.SdkL1InviteCreation].
         */
        val USE_KOTLIN_SDK_L1_INVITE = booleanPreferencesKey("use_kotlin_sdk_l1_invite")

        /**
         * Phase 5a of the dashj → Kotlin SDK migration
         * (`docs/kotlin-sdk-migration-plan.md`): run the Kotlin SDK's Rust
         * SPV client ALONGSIDE dashj as a shadow — a verification harness
         * for the eventual L1 cutover, changing nothing user-facing. While
         * on (and the app wallet is bound to the SDK), the shadow service
         * starts the SDK's compact-filter sync into its own storage
         * directory and probes balance/tx-count parity against the dashj
         * wallet every minute, logging `L1Parity` one-liners. Default OFF:
         * with the flag off the service is provably inert (no native call,
         * no SPV storage, no probe loop).
         *
         * DEBUG-ONLY INSTRUMENTATION: shadow mode runs TWO SPV engines
         * (dashj + Rust) — double network and battery cost. It is seeded ON
         * only in debug builds (below) and must never ship enabled to prod.
         * See [de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService].
         */
        val USE_KOTLIN_SDK_L1_SHADOW = booleanPreferencesKey("use_kotlin_sdk_l1_shadow")

        /**
         * Phase 5b of the dashj → Kotlin SDK migration
         * (`docs/kotlin-sdk-migration-plan.md`): route the NORMAL L1 send —
         * a plain Dash payment to a base58 address — through the Kotlin
         * SDK's Core send pipeline (build + sign + broadcast over the SDK's
         * SPV peers) instead of dashj. Scope: ONLY the neutral
         * `SendPaymentService.sendCoins(String, Dash)` overload (the
         * integrations path — Coinbase/Maya); the dashj-typed main-UI send
         * stays on dashj until Phase 5c. Every send is hard-gated on the
         * same shadow-sync parity evidence as the shielded funding pipeline
         * (see `SdkL1SendService`), and any outcome where the SDK provably
         * broadcast nothing falls back to the unchanged dashj path.
         *
         * Default OFF and — unlike the other migration flags —
         * DELIBERATELY NOT seeded ON by the debug-build init block below:
         * this flag moves real user funds through the SDK's own
         * build/sign/broadcast stack, so it stays opt-in even for testers
         * (adb/debug-screen toggle) until the shadow-parity harness has
         * soak-validated the SDK's L1 view across enough devices. Re-read
         * on every send, so toggling either direction is instant.
         */
        val USE_KOTLIN_SDK_L1_SEND = booleanPreferencesKey("use_kotlin_sdk_l1_send")

        /**
         * The `USE_KOTLIN_SDK_*` flags a DEBUG build seeds ON when unset —
         * pure so [seedDebugDefaultsIfUnset]'s network split is
         * host-testable. Per Brian's explicit decision (2026-07-27), mainnet
         * debug builds (prodDebug — the external large-wallet validation
         * vehicle) now seed the SAME feature set as testnet, INCLUDING the
         * shielded pool and the SDK write paths — this deliberately exposes
         * those paths to REAL mainnet funds for on-device validation. Only
         * the prodDebug build seeds ([seedDebugDefaultsIfUnset] is
         * `!BuildConfig.DEBUG` gated), so a prodRelease stays features-off by
         * default. `USE_KOTLIN_SDK_L1_SEND` is never seeded anywhere.
         */
        internal fun debugSeedFlags(isMainnet: Boolean) = if (isMainnet) {
            // Mainnet: full set (real-funds validation vehicle — see KDoc).
            listOf(
                USE_KOTLIN_SDK_DPNS_READS,
                USE_KOTLIN_SDK_DASHPAY_WRITES,
                USE_KOTLIN_SDK_SHIELDED,
                USE_KOTLIN_SDK_L1_INVITE,
                USE_KOTLIN_SDK_L1_SHADOW
            )
        } else {
            listOf(
                USE_KOTLIN_SDK_DPNS_READS,
                USE_KOTLIN_SDK_DASHPAY_WRITES,
                USE_KOTLIN_SDK_SHIELDED,
                USE_KOTLIN_SDK_L1_INVITE,
                USE_KOTLIN_SDK_L1_SHADOW
            )
        }

        /**
         * Wall-clock ms of the last L1 shadow reset
         * ([de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService.resetShadowState]).
         * Persisted (not in-memory) so a reset survives a process death:
         * the reset-aftermath deficit detector uses it to distinguish
         * "the previous reset broke the shadow state" (recover with one
         * hard reset) from an organic SDK scan deficit (stand down) —
         * see `ShadowResetDecider`'s decision table.
         */
        val L1_SHADOW_LAST_RESET = longPreferencesKey("l1_shadow_last_reset")

        /**
         * Phase 5d cutover state machine (see docs/kotlin-sdk-migration-plan.md
         * and [de.schildbach.wallet.service.platform.sdk.CutoverState]).
         * Persisted per-install so the engine-start decision survives process
         * death; absent = [de.schildbach.wallet.service.platform.sdk
         * .CutoverState.DUAL_RUNNING] (today's behavior). The transition to
         * CUT_OVER is the single atomic write that makes the SDK the L1
         * source of truth; every engine-start site consults it first.
         */
        val CUTOVER_STATE = stringPreferencesKey("cutover_state")

        /**
         * Last shielded balance (in duffs) persisted from a fully-synced
         * (READY) shielded runtime, so the More-screen "Shielded" card can
         * render the known balance INSTANTLY on open — even while a
         * background re-scan re-binds the runtime on relaunch — instead of a
         * "Syncing…" placeholder. Written by
         * [de.schildbach.wallet.service.platform.sdk.ShieldedBalanceServiceImpl]
         * only when the balance is trustworthy (status READY); absent until
         * the first such emission. Follows the last-known-value precedents
         * above (e.g. [TRANSACTION_METADATA_LAST_PAST_SAVE]).
         */
        val LAST_SHIELDED_BALANCE_DUFFS = longPreferencesKey("last_shielded_balance_duffs")
    }

    init {
        // Debug builds seed the Kotlin SDK migration flags ON (once, only if unset) so
        // flag-gated SDK paths get exercised during testnet verification; release/prod
        // builds keep them OFF until rollout. QA can still toggle them afterwards.
        if (BuildConfig.DEBUG) {
            CoroutineScope(Dispatchers.IO).launch {
                seedDebugDefaultsIfUnset()
            }
        }
    }

    /**
     * Idempotent debug-flag seeding — callable OUTSIDE the init block
     * because a Reset Wallet wipes this DataStore MID-PROCESS: the
     * singleton already exists, so the init-time pass never re-runs, all
     * `USE_KOTLIN_SDK_*` flags silently read as OFF, and every SDK path
     * (binder, shadow, shielded) goes inert with no log trail — observed
     * live: the duck-say overnight restore ran with the SDK dark. The
     * wipe path calls this after clearing (debug builds only; a no-op on
     * release where BuildConfig.DEBUG gates the caller). Which flags are
     * seeded is network-dependent — see [debugSeedFlags].
     */
    suspend fun seedDebugDefaultsIfUnset() {
        if (!BuildConfig.DEBUG) return
        try {
            val seeded = mutableListOf<String>()
            val alreadySet = mutableListOf<String>()
            val debugDefaultOnFlags = debugSeedFlags(
                isMainnet = Constants.NETWORK_PARAMETERS.id == NetworkParameters.ID_MAINNET
            )

            for (flag in debugDefaultOnFlags) {
                if (get(flag) == null) {
                    set(flag, true)
                    seeded.add(flag.name)
                } else {
                    alreadySet.add(flag.name)
                }
            }

            log.info("debug SDK flag seeding: seeded ON = {}, already set (skipped) = {}", seeded, alreadySet)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // best-effort seeding; unset flags simply stay at their OFF default —
            // but that darkens every flag-gated SDK surface, so it must be loud
            log.warn("debug SDK flag seeding failed; unset USE_KOTLIN_SDK_* flags stay OFF", e)
        }
    }

    /**
     * The last shielded balance persisted from a fully-synced runtime, in
     * duffs, or null when none has ever been persisted. See
     * [LAST_SHIELDED_BALANCE_DUFFS].
     */
    suspend fun getLastShieldedBalanceDuffs(): Long? = get(LAST_SHIELDED_BALANCE_DUFFS)

    /** Persist the last trustworthy shielded balance (duffs). See [LAST_SHIELDED_BALANCE_DUFFS]. */
    suspend fun setLastShieldedBalanceDuffs(duffs: Long) = set(LAST_SHIELDED_BALANCE_DUFFS, duffs)

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
                savePrivateMemos = prefs[TRANSACTION_METADATA_SAVE_MEMOS] ?: false,
                saveGiftcardInfo = prefs[TRANSACTION_METADATA_SAVE_GIFT_CARD_INFO] ?: false
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
            saveGiftcardInfo = get(TRANSACTION_METADATA_SAVE_GIFT_CARD_INFO) ?: false,
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
        set(TRANSACTION_METADATA_SAVE_GIFT_CARD_INFO, settings.saveGiftcardInfo)
    }

    suspend fun shouldSaveOnReset(): Boolean = get(TRANSACTION_METADATA_SAVE_ON_RESET) == true

    suspend fun isSavingToNetwork(): Boolean = get(TRANSACTION_METADATA_SAVE_TO_NETWORK) ?: false

    suspend fun getSaveAfterTimestamp(): Long = get(TRANSACTION_METADATA_SAVE_AFTER) ?: 0L

    suspend fun getMetadataFeatureInstalled(): Long {
        val installedDate = get(TRANSACTION_METADATA_FEATURE_INSTALLED) ?: 0
        if (installedDate == 0L) {
            set(TRANSACTION_METADATA_FEATURE_INSTALLED, System.currentTimeMillis())
        }
        return installedDate
    }
}
