/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.utils

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.BaseConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class CrowdNodeConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider
) : BaseConfig(context, PREFERENCES_NAME, walletDataProvider) {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeConfig::class.java)

        const val PREFERENCES_NAME = "crowdnode"

        val INFO_SHOWN = booleanPreferencesKey("info_shown")
        val ONLINE_INFO_SHOWN = booleanPreferencesKey("online_info_shown")
        val CONFIRMATION_DIALOG_SHOWN = booleanPreferencesKey("confirmation_dialog_shown")
        val BACKGROUND_ERROR = stringPreferencesKey("error")
        val SIGN_UP_FINISHED = booleanPreferencesKey("sign_up_finished")

        // Wallet transaction count at the last full scan that found no sign up
        val NO_SIGN_UP_SCAN_TX_COUNT = intPreferencesKey("no_sign_up_scan_tx_count")
        val ONLINE_ACCOUNT_STATUS = intPreferencesKey("online_account_status")
        val LAST_BALANCE = longPreferencesKey("last_balance")
        val SIGNED_EMAIL_MESSAGE_ID = intPreferencesKey("signed_email_message_id")
        val WITHDRAWAL_LIMITS_SHOWN = booleanPreferencesKey("withdrawal_limits_shown")
        val WITHDRAWAL_LIMIT_PER_TX = longPreferencesKey("withdrawal_limit_per_tx")
        val WITHDRAWAL_LIMIT_PER_HOUR = longPreferencesKey("withdrawal_limit_per_hour")
        val WITHDRAWAL_LIMIT_PER_DAY = longPreferencesKey("withdrawal_limit_per_day")
        val LAST_WITHDRAWAL_BLOCK = intPreferencesKey("last_withdrawal_block")
        val FEE_PERCENTAGE = doublePreferencesKey("fee_double")
        val LAST_FEE_REQUEST = longPreferencesKey("last_fee_request")
    }

    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val walletReplacements = AtomicInteger()
    private val scanCacheClearedThrough = AtomicInteger()

    /**
     * Bumped by [onWalletReplaced]. A [NO_SIGN_UP_SCAN_TX_COUNT] scan captures it before it starts
     * and only caches its result if it is unchanged, so a scan of the old wallet that finishes after
     * the replacement can't re-cache a count that now describes the new wallet.
     */
    open val walletGeneration: Int
        get() = walletReplacements.get()

    /**
     * True from [onWalletReplaced] until a removal of [NO_SIGN_UP_SCAN_TX_COUNT] that started after
     * the latest replacement lands, so a reader doesn't trust the old wallet's count meanwhile.
     */
    open val isNoSignUpScanClearPending: Boolean
        get() = scanCacheClearedThrough.get() < walletReplacements.get()

    /**
     * Forgets [NO_SIGN_UP_SCAN_TX_COUNT] when a created or restored wallet replaces the current one
     * without a wipe: the cached count describes the old wallet's transactions, and a replacement
     * with the same count would otherwise skip the sign-up scan. Fire-and-forget, so it is safe to
     * call from the main thread; [isNoSignUpScanClearPending] covers the window until it lands.
     * A failed removal is retried a few times with backoff, then by [retryPendingNoSignUpScanClear].
     */
    fun onWalletReplaced() {
        walletReplacements.incrementAndGet()
        configScope.launch {
            val cleared = retryWithBackoff(
                CLEAR_ATTEMPTS,
                CLEAR_INITIAL_DELAY_MILLIS,
                CLEAR_MAX_DELAY_MILLIS,
                onFailure = { attempt, e ->
                    if (attempt == 1 || attempt == CLEAR_ATTEMPTS) {
                        log.warn("failed to clear the CrowdNode no-sign-up scan cache (attempt $attempt)", e)
                    }
                }
            ) {
                if (isNoSignUpScanClearPending) {
                    clearNoSignUpScan()
                }
            }

            if (!cleared) {
                log.warn("giving up clearing the CrowdNode no-sign-up scan cache until the next restore")
            }
        }
    }

    /** Retries the removal while [isNoSignUpScanClearPending]; a failure just leaves it pending. */
    open suspend fun retryPendingNoSignUpScanClear() {
        if (!isNoSignUpScanClearPending) {
            return
        }

        try {
            clearNoSignUpScan()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("failed to clear the CrowdNode no-sign-up scan cache", e)
        }
    }

    /**
     * The removal is idempotent and covers every replacement counted before it started, so on
     * success the pending state is cleared through the generation read beforehand. Taking the max
     * keeps a slower, older removal from moving it back.
     */
    private suspend fun clearNoSignUpScan() {
        val generation = walletReplacements.get()
        remove(NO_SIGN_UP_SCAN_TX_COUNT)
        scanCacheClearedThrough.accumulateAndGet(generation, ::maxOf)
    }
}

private const val CLEAR_ATTEMPTS = 6
private const val CLEAR_INITIAL_DELAY_MILLIS = 1_000L
private const val CLEAR_MAX_DELAY_MILLIS = 30_000L

/**
 * Runs [block] up to [maxAttempts] times until it completes without throwing, waiting
 * [initialDelayMillis] after the first failure and doubling up to [maxDelayMillis]. Cancellation
 * propagates. Returns whether an attempt succeeded.
 */
internal suspend fun retryWithBackoff(
    maxAttempts: Int,
    initialDelayMillis: Long,
    maxDelayMillis: Long,
    onFailure: (attempt: Int, e: Exception) -> Unit = { _, _ -> },
    block: suspend () -> Unit
): Boolean {
    var delayMillis = initialDelayMillis

    for (attempt in 1..maxAttempts) {
        try {
            block()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(attempt, e)
        }

        if (attempt < maxAttempts) {
            delay(delayMillis)
            delayMillis = minOf(delayMillis * 2, maxDelayMillis)
        }
    }

    return false
}
