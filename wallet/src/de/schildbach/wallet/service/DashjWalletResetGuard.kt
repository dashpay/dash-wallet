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

/**
 * Whether [BlockchainServiceImpl] may `wallet.reset()` the dashj wallet
 * when it finds the SPV blockstore file missing (fresh install, corruption,
 * cleared storage).
 *
 * That reset exists so dashj can re-download the chain from scratch: dashj
 * `Wallet.reset()` clears all five transaction pools, `myUnspents`, the
 * spent-outpoint index and `lastBlockSeen*` (keys, keychains, the HD seed
 * and the wallet extensions all survive), then PERSISTS the emptied wallet.
 * Safe when a resync follows — it repopulates from the same keys.
 *
 * Post-cutover no resync follows: the peergroup is held. But dashj is still
 * the wallet-of-record whose transaction set `TxDisplayCacheService`
 * rebuilds the home-screen history from, and its wallet-reset listener
 * additionally deletes both Room display caches — while
 * `rebuildIfCacheIncomplete` cannot detect the loss, because its trigger is
 * `walletTxCount > cachedTxCount` and an emptied wallet reports 0. So the
 * history would be gone with nothing left to rebuild it from, in exchange
 * for a re-download that never happens.
 *
 * Hence: refuse the reset only when it would actually destroy something —
 * the SDK owns L1 AND the dashj wallet still holds transactions. A wallet
 * with no transactions loses nothing, so it keeps the original path
 * byte-for-byte (which covers a genuinely fresh install). Pure —
 * host-testable.
 */
fun mayResetDashjWalletForMissingBlockstore(
    sdkOwnsL1: Boolean,
    dashjTransactionCount: Int
): Boolean = !sdkOwnsL1 || dashjTransactionCount == 0
