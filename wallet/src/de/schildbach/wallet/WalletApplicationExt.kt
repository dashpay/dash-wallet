/*
 * Copyright (c) 2022.
 * Copyright 2022 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package de.schildbach.wallet

import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

object WalletApplicationExt {
    private val log = LoggerFactory.getLogger(WalletApplicationExt::class.java)

    /**
     * Clear databases
     *
     * @param isWalletWipe This is true for Reset Wallet, false for Rescan Blockchain
     *
     * The wipe path BLOCKS until every store is cleared: `finalizeWipe()`
     * nulls the wallet, invokes the post-wipe callback and lets the
     * process die right after this call, so a fire-and-forget launch
     * races process death — observed live as DashPay identity/contact/
     * notification data surviving a Reset Wallet and resurrecting the
     * DashPay UI on the next (fresh) wallet. The rescan path keeps the
     * async launch (it runs during service start and its data is
     * re-syncable either way).
     */
    fun WalletApplication.clearDatabases(isWalletWipe: Boolean) {
        if (isWalletWipe) {
            runBlocking { clearDatabasesInner(isWalletWipe = true) }
        } else {
            CoroutineScope(Dispatchers.IO).launch { clearDatabasesInner(isWalletWipe = false) }
        }
    }

    /**
     * Every step is failure-contained: one failing store (most notably
     * the platform metadata push inside [PlatformSyncService.clearDatabases])
     * must never abort the remaining clears — that partial-clear mode is
     * exactly the resurrected-DashPay-UI bug.
     */
    private suspend fun WalletApplication.clearDatabasesInner(isWalletWipe: Boolean) {
        runCatching { platformSyncService.clearDatabases() }
            .onFailure { rethrowCancellation(it); log.warn("platform-sync clear failed during reset", it) }
        if (isWalletWipe) {
            runCatching { transactionMetadataProvider.clear() }
                .onFailure { rethrowCancellation(it); log.warn("tx-metadata clear failed during wipe", it) }
        }
        runCatching { identityRepository.clearDatabase(isWalletWipe) }
            .onFailure { rethrowCancellation(it); log.warn("identity/DashPay clear failed during reset", it) }
        runCatching { txDisplayCacheService.clearDatabase() }
            .onFailure { rethrowCancellation(it); log.warn("tx-display-cache clear failed during reset", it) }
        WorkManager.getInstance(this).cancelAllWork()
        log.info("databases cleared (isWalletWipe = {})", isWalletWipe)
    }

    private fun rethrowCancellation(t: Throwable) {
        if (t is CancellationException) throw t
    }

    fun WalletApplication.clearCachedAddresses(): Unit = runBlocking {
        exchangeIntegrationProvider.clearCachedAddresses()
    }
}
