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

import org.bitcoinj.core.Sha256Hash
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SDK-sourced masternode lookups for contested-username VOTING — the
 * post-cutover replacement for the dashj deterministic-masternode-list
 * dependency (`MasternodeListManager.getMasternodesByVotingKey`), which goes
 * permanently empty once dashj sync is held.
 *
 * Call sites keep a dashj-FIRST contract: they consult the dashj list and use
 * its (richer) entries when non-empty — pre-cutover behavior byte-for-byte —
 * and fall back to this lookup only when dashj has nothing. The vote WRITE
 * path (`platform.names.broadcastVote`, dashj-platform DAPI) is untouched;
 * only the proTxHash discovery is routed here.
 *
 * ## Contract
 *
 * - **Fail-soft, never boots the SDK**: returns an empty list when the SDK
 *   isn't started (post-cutover it always is — it owns sync), when the DML
 *   hasn't synced Rust-side, or on any native/reflective error (logged).
 *   `ensureStarted()` is deliberately NOT called: one call site
 *   ([de.schildbach.wallet.ui.username.voting.UsernameRequestsViewModel.verifyMasterVotingKey])
 *   runs on the main thread, where the in-memory JNI lookup is fine but an
 *   SDK bootstrap would not be.
 * - **Cheap and blocking by design**: a single JNI call into the in-memory
 *   DML index (no network, no disk), mirroring how the dashj call it
 *   replaces was used inline.
 */
@Singleton
class SdkMasternodeQueries @Inject constructor(
    private val sdkService: DashSdkService
) {
    /**
     * The proTxHashes of every masternode whose voting-key hash equals
     * [votingKeyPubKeyHash] (the 20-byte hash160 of the voting public key —
     * dashj `ECKey.pubKeyHash` / `KeyId` bytes), per the SDK's current-tip
     * deterministic masternode list. Empty on any failure or when unknown.
     */
    fun proTxHashesByVotingKey(votingKeyPubKeyHash: ByteArray): List<Sha256Hash> {
        if (votingKeyPubKeyHash.size != VOTING_KEY_ID_SIZE) {
            log.warn(
                "voting key id must be {} bytes (hash160), got {} — returning no masternodes",
                VOTING_KEY_ID_SIZE, votingKeyPubKeyHash.size
            )
            return emptyList()
        }
        val manager = sdkService.walletManagerOrNull()
        if (manager == null) {
            log.info("SDK wallet manager not started; SDK masternode-by-voting-key lookup unavailable")
            return emptyList()
        }
        return try {
            val flat = MasternodeVotingNative.masternodesByVotingKey(manager, votingKeyPubKeyHash)
            if (flat.isEmpty()) {
                return emptyList()
            }
            if (flat.size % PRO_TX_HASH_SIZE != 0) {
                log.warn(
                    "SDK masternodesByVotingKey returned {} bytes — not a multiple of {}; ignoring",
                    flat.size, PRO_TX_HASH_SIZE
                )
                return emptyList()
            }
            val hashes = (0 until flat.size / PRO_TX_HASH_SIZE).map { i ->
                val chunk = flat.copyOfRange(i * PRO_TX_HASH_SIZE, (i + 1) * PRO_TX_HASH_SIZE)
                // BYTE ORDER (verify on device — see the first-use log below):
                // the FFI emits proTxHashes in INTERNAL (wire / rust-dashcore)
                // byte order, while dashj parses proTxHashes off the wire with
                // Message.readHash() = Sha256Hash.wrapReversed(...), so its
                // Sha256Hash stores display-order bytes. wrapReversed here makes
                // the constructed hash equal dashj's `masternode.proTxHash`
                // (same .bytes fed into the voting-identity-id derivation, same
                // display hex). If votes built from this path can't resolve
                // their voting identity, flip to Sha256Hash.wrap(chunk).
                Sha256Hash.wrapReversed(chunk)
            }
            if (loggedByteOrderSample.compareAndSet(false, true)) {
                // First-use verification aid: compare against a known proTxHash
                // (e.g. dashj's, or the masternode's registration tx on an
                // explorer, which shows display order). Exactly one of these
                // two hex strings must match the explorer/dashj value.
                val raw = flat.copyOfRange(0, PRO_TX_HASH_SIZE)
                log.info(
                    "SDK masternodesByVotingKey byte-order sample — wrapReversed (used): {}, wrap (unused): {}",
                    hashes.first(), Sha256Hash.wrap(raw)
                )
            }
            hashes
        } catch (t: Throwable) {
            log.warn("SDK masternodesByVotingKey lookup failed; returning no masternodes", t)
            emptyList()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkMasternodeQueries::class.java)
        private val loggedByteOrderSample = AtomicBoolean(false)

        private const val VOTING_KEY_ID_SIZE = 20
        private const val PRO_TX_HASH_SIZE = 32
    }
}
