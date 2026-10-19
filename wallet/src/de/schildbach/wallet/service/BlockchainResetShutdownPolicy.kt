/*
 * Copyright 2026 Dash Core Group.
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
package de.schildbach.wallet.service

import kotlinx.coroutines.CancellationException

/**
 * What [BlockchainServiceImpl]'s shutdown cleanup does for a Settings → Rescan
 * Blockchain (reset) or a wallet wipe. Built by [BlockchainResetShutdownPolicy.plan].
 *
 * @property deleteStores delete the dashj SPV block/header store files
 * @property resetMnListsAndState reset the masternode lists and the blockchain state
 * @property wipeWallet stop the SDK engines and finish the wallet wipe
 * @property clearRescanDatabases clear the rescan databases (an owed SDK replay will refill them)
 * @property keepRescanDatabases a reset with no SDK replay owed: keep the databases, warn
 */
internal data class BlockchainResetShutdownPlan(
    val deleteStores: Boolean,
    val resetMnListsAndState: Boolean,
    val wipeWallet: Boolean,
    val clearRescanDatabases: Boolean,
    val keepRescanDatabases: Boolean
)

internal object BlockchainResetShutdownPolicy {
    val NOTHING = BlockchainResetShutdownPlan(
        deleteStores = false,
        resetMnListsAndState = false,
        wipeWallet = false,
        clearRescanDatabases = false,
        keepRescanDatabases = false
    )

    /**
     * Pure, so every combination is pinned by test.
     *
     * - A wipe deletes the dashj stores and runs the wipe, whatever the owe result.
     * - A reset (rescan) always keeps the dashj stores — only the SDK replays.
     *   The rescan databases are cleared only when an SDK replay is durably
     *   owed; otherwise nothing would ever refill them, so they are kept.
     * - Neither flag: nothing.
     */
    fun plan(
        resetBlockchainOnShutdown: Boolean,
        deleteWalletFileOnShutdown: Boolean,
        sdkRescanOwedForReset: Boolean
    ): BlockchainResetShutdownPlan = when {
        deleteWalletFileOnShutdown -> BlockchainResetShutdownPlan(
            deleteStores = true,
            resetMnListsAndState = true,
            wipeWallet = true,
            clearRescanDatabases = false,
            keepRescanDatabases = false
        )
        resetBlockchainOnShutdown -> BlockchainResetShutdownPlan(
            deleteStores = false,
            resetMnListsAndState = true,
            wipeWallet = false,
            clearRescanDatabases = sdkRescanOwedForReset,
            keepRescanDatabases = !sdkRescanOwedForReset
        )
        else -> NOTHING
    }

    /**
     * The owe-result mapping of ACTION_RESET_BLOCKCHAIN: true only when the
     * SDK owns L1 and [owe] durably recorded the replay, so a process or
     * engine stop before the rewind persists cannot lose it. Pre-cutover
     * ([sdkOwnsL1] false, nothing is owed), a false owe, or a failure
     * (reported to [onFailure]) all map to false. Never throws, except that a
     * [CancellationException] is rethrown.
     */
    suspend fun oweSdkRescan(
        sdkOwnsL1: suspend () -> Boolean,
        owe: suspend () -> Boolean,
        onFailure: (Throwable) -> Unit
    ): Boolean = try {
        sdkOwnsL1() && owe()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        onFailure(t)
        false
    }
}
