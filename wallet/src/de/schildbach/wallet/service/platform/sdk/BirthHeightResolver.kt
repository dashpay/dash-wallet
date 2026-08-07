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

import org.bitcoinj.core.CheckpointManager
import org.bitcoinj.core.NetworkParameters
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * Dash's block target spacing (2.5 minutes), used to size the safety
 * margin below in blocks. Not read from [NetworkParameters] because the
 * margin only needs to be roughly a week — precision buys nothing here.
 */
private const val BLOCK_TARGET_SPACING_SECS = 150L

/**
 * Safety margin subtracted from the resolved checkpoint height: about one
 * week of blocks (7d / 2.5min = 4032). A checkpoint at-or-before the birth
 * TIME is already behind the birth block, but wallet clocks, backup
 * `creationTime` rounding (dashj subtracts a week itself when writing
 * protobufs) and time-vs-height skew all push in the "maybe earlier"
 * direction — and a too-HIGH height silently hides funds, while a too-low
 * one merely rescans a few thousand extra filter headers.
 */
internal const val BIRTH_HEIGHT_SAFETY_MARGIN_BLOCKS = (7L * 24 * 60 * 60 / BLOCK_TARGET_SPACING_SECS).toInt() // 4032

/**
 * Clamp-subtract the safety margin from a checkpoint height. Pure, so the
 * arithmetic edge cases (heights below the margin, genesis) are host-JVM
 * unit-testable without a checkpoint file.
 */
internal fun safeBirthHeight(
    checkpointHeight: Int,
    marginBlocks: Int = BIRTH_HEIGHT_SAFETY_MARGIN_BLOCKS
): UInt = if (checkpointHeight <= marginBlocks) 0u else (checkpointHeight - marginBlocks).toUInt()

/**
 * Phase 5a of the dashj → Kotlin SDK migration
 * (`docs/kotlin-sdk-migration-plan.md`): map the app wallet's birth *time*
 * ([org.bitcoinj.wallet.Wallet.getEarliestKeyCreationTime], Unix seconds)
 * to a SAFE block *height* for the SDK's `createWallet(birthHeight = …)`,
 * closing the gap that made Phase 3b hardcode `0` (full scan from
 * genesis).
 *
 * ## Source: the app's own dashj checkpoint files
 *
 * The app already ships `checkpoints.txt` / `checkpoints-testnet.txt`
 * (`wallet/assets/`, selected by `Constants.Files.CHECKPOINTS_FILENAME`)
 * and parses them with dashj's [CheckpointManager] — the exact same
 * time→height source `BlockchainServiceImpl` uses to fast-forward a new
 * dashj block store past the wallet's birth. This resolver reuses it:
 * [CheckpointManager.getCheckpointBefore] returns the last checkpoint
 * whose header time is at-or-before the birth time (dashj checkpoints one
 * block per 576, ~1 day apart), and [safeBirthHeight] then subtracts
 * [BIRTH_HEIGHT_SAFETY_MARGIN_BLOCKS] (~1 week) so the compact-filter
 * scan provably starts BELOW every key's first possible use.
 *
 * ## Failure policy: genesis, never higher
 *
 * Anything unresolvable — null/absent birth time, birth time at-or-before
 * the genesis header time (dashj throws), missing/corrupt checkpoint
 * asset, no checkpoint before the time — resolves to `0` (genesis, the
 * Phase 3b behavior). A wrong-high height silently hides funds; a wrong-low
 * height is only slower. This method never throws.
 *
 * ## Already-bound wallets (documented limitation)
 *
 * [DashSdkServiceImpl.bindAppWallet] is idempotent: a wallet bound before
 * this resolver landed keeps the `birthHeight = 0` stored at creation, and
 * re-binding matches the persisted mnemonic and returns the existing id
 * WITHOUT re-running `createWallet` — so re-binding does not (and must
 * not) change what has already been scanned. Only FUTURE first-time binds
 * (fresh installs, wallet restores) benefit. That is fine for Phase 5a:
 * the shadow-sync harness compares end-state balances, not sync cost.
 *
 * @param networkParameters the network whose checkpoints [openCheckpoints]
 *   yields — must match the file, or [CheckpointManager] rejects it.
 * @param openCheckpoints opens a FRESH stream over the checkpoint file on
 *   every call (assets can be opened repeatedly); closed by the resolver.
 */
class BirthHeightResolver(
    private val networkParameters: NetworkParameters,
    private val openCheckpoints: () -> InputStream
) {
    /**
     * The SDK `birthHeight` for a wallet whose earliest key was created at
     * [birthTimeSecs] (Unix seconds), or `0u` (genesis / full scan) when
     * unresolvable. Never throws.
     */
    fun resolve(birthTimeSecs: Long?): UInt {
        if (birthTimeSecs == null || birthTimeSecs <= 0) return 0u
        return try {
            openCheckpoints().use { stream ->
                val checkpoints = CheckpointManager(networkParameters, stream)
                // Last checkpoint with header time <= birth time; dashj
                // falls back to a height-0 genesis StoredBlock when the
                // time predates every checkpoint, and throws when it
                // predates genesis itself (caught below).
                val checkpoint = checkpoints.getCheckpointBefore(birthTimeSecs)
                val height = safeBirthHeight(checkpoint.height)
                log.info(
                    "resolved birth time {} to SDK birth height {} (checkpoint height {}, margin {} blocks)",
                    birthTimeSecs, height, checkpoint.height, BIRTH_HEIGHT_SAFETY_MARGIN_BLOCKS
                )
                height
            }
        } catch (e: Exception) {
            log.warn(
                "birth-height resolution failed for time {}; falling back to a full scan from genesis ({})",
                birthTimeSecs, e.toString()
            )
            0u
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BirthHeightResolver::class.java)
    }
}
