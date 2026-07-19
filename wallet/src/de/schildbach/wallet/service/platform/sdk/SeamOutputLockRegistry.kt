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

package de.schildbach.wallet.service.platform.sdk

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SEAM-LEVEL OUTPUT LOCK REGISTRY: the post-cutover twin of dashj's
 * `Wallet.lockedOutputs` for transactions the HELD dashj wallet never sees.
 *
 * Post-cutover, incoming SDK-fed transactions (most critically CrowdNode's
 * API-response transactions, whose account-address outputs the signup flow
 * locks via `WalletDataProvider.lockOutputsPayingTo`) exist only in the SDK
 * store — `Wallet.lockOutput` cannot be applied to them because there is no
 * wallet `Transaction` to resolve. [de.schildbach.wallet.data.WalletDataAdapter]
 * registers those locks here instead, keyed by outpoint (`txidHex:vout`,
 * display-order lowercase txid hex).
 *
 * ## In-memory on purpose (dashj parity)
 *
 * dashj's `Wallet.lockedOutputs` is a plain in-memory
 * `HashSet<TransactionOutPoint>`; the wallet protobuf
 * (`org.bitcoinj.wallet.Protos.Wallet`) has no locked-outputs field, so
 * dashj locks were never persisted across process restarts either
 * (verified against dashj-core 22.0.4). Keeping this registry in-memory is
 * therefore not a behavior delta — a restart drops the locks on both sides,
 * and the CrowdNode flow re-locks on resume exactly as it always has.
 *
 * ## TODO (integration branch): send-all drain guard
 *
 * The integration branch's send-all drain guard
 * (`SdkL1SendService.hasAppLockedSpendableOutputs` — NOT present on this
 * branch's base) currently consults only dashj's `Wallet.lockedOutputs`.
 * When this seam work lands there, that guard MUST union its dashj check
 * with [hasAnyLocks]/[isLocked] so an SDK-side send-all cannot drain
 * outputs locked through this registry. The read API below exists for
 * exactly that wiring; it is deliberately not wired here because the base
 * of this branch has no drain guard to extend.
 */
@Singleton
class SeamOutputLockRegistry @Inject constructor() {

    private val lockedOutpoints: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Registers the outpoint `txIdHex:vout` as locked. Idempotent. */
    fun lockOutput(txIdHex: String, vout: Int) {
        if (lockedOutpoints.add(key(txIdHex, vout))) {
            log.info("seam-locked output {}:{}", txIdHex.lowercase(), vout)
        }
    }

    /** True when at least one output is seam-locked (drain-guard union hook — see class KDoc). */
    fun hasAnyLocks(): Boolean = lockedOutpoints.isNotEmpty()

    /** True when the outpoint `txIdHex:vout` is seam-locked (drain-guard union hook — see class KDoc). */
    fun isLocked(txIdHex: String, vout: Int): Boolean = key(txIdHex, vout) in lockedOutpoints

    private fun key(txIdHex: String, vout: Int): String = "${txIdHex.lowercase()}:$vout"

    companion object {
        private val log = LoggerFactory.getLogger(SeamOutputLockRegistry::class.java)
    }
}
