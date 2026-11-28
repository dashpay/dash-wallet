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

import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation

/**
 * Re-derives the SDK address-pool rows that the gap-limit widening leaves
 * unpersisted, so that a reloaded pool is contiguous again.
 *
 * `core_wallet_set_gap_limit` derives the wider window in Rust memory only:
 * `PlatformWallet::set_gap_limit` drops the addresses it generates and
 * emits no `addresses_derived` changeset. Addresses derived later, during
 * sync, are persisted, so the stored pool ends up with every unused index
 * of the widened window missing below rows that do exist. On the next load
 * the pool is rebuilt from those rows, `highest_generated` comes from the
 * highest stored index, and `maintain_gap_limit` never re-derives anything
 * below it: an output paid to one of the missing addresses is invisible,
 * and no rescan finds it. A field wallet restored on 2026-10-09 had 477
 * external, 85 internal and 894 CoinJoin-internal indices missing, and
 * missed change that iOS had sent to internal index 637.
 *
 * This object holds the pure part of the repair: decoding the account key
 * the SDK stores, planning which indices a pool is missing, and deriving
 * the rows to insert. [DashSdkServiceImpl.backfillAddressPoolHoles] does
 * the database work around it.
 */
internal object SdkAddressPoolBackfill {

    /** A pool row as stored in the SDK's `core_addresses` table. */
    data class StoredRow(
        val addressIndex: Int,
        val address: String,
        val isUsed: Boolean,
        val derivationPath: String
    )

    /** One address to insert. */
    data class DerivedRow(
        val addressIndex: Int,
        val address: String,
        val publicKey: ByteArray,
        val derivationPath: String
    )

    /** What one pool is missing, before any key is derived. */
    data class PoolPlan(
        /** Indices to insert, ascending. */
        val missing: List<Int>,
        /**
         * How many of [missing] lie below the hole ceiling (see [plan]).
         * These are the true holes: the engine loaded the pool without them,
         * so this process does not watch them. Indices at or above it are
         * the widened tail, which the engine derived in memory this session.
         */
        val holes: Int
    )

    /**
     * The highest index stored for a pool when the engine loaded it, or -1
     * for a pool with no rows yet. Captured before this process's backfill
     * writes anything or its scan persists new rows, it separates the true
     * holes from rows that are only late to reach the database.
     */
    fun holeCeiling(rows: List<StoredRow>): Int = rows.maxOfOrNull { it.addressIndex } ?: -1

    /**
     * The serialized account key the SDK stores in
     * `accounts.accountExtendedPubKeyBytes` is key-wallet's bincode
     * `ExtendedPubKey`: network, depth, parent fingerprint, child number
     * (varint), then the 33-byte compressed public key and the 32-byte
     * chain code. The child number's length varies, so the two fixed-size
     * fields are read from the end.
     */
    private const val PUBLIC_KEY_LENGTH = 33
    private const val CHAIN_CODE_LENGTH = 32

    /**
     * The account-level public key from the SDK's stored xpub bytes, or
     * null when the bytes are not an ECDSA extended public key (the BLS and
     * EdDSA provider accounts store other shapes).
     */
    fun accountKey(xpubBytes: ByteArray?): DeterministicKey? {
        if (xpubBytes == null || xpubBytes.size < PUBLIC_KEY_LENGTH + CHAIN_CODE_LENGTH + 2) return null
        val chainCodeStart = xpubBytes.size - CHAIN_CODE_LENGTH
        val publicKeyStart = chainCodeStart - PUBLIC_KEY_LENGTH
        val publicKey = xpubBytes.copyOfRange(publicKeyStart, chainCodeStart)
        if (publicKey[0] != 0x02.toByte() && publicKey[0] != 0x03.toByte()) return null
        val chainCode = xpubBytes.copyOfRange(chainCodeStart, xpubBytes.size)
        return try {
            HDKeyDerivation.createMasterPubKeyFromBytes(publicKey, chainCode)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * The derivation-path prefix shared by every row of a pool, taken from
     * a stored row: `m/44'/5'/0'/1/637` at index 637 gives `m/44'/5'/0'/1/`.
     * Null when the path does not end in the row's own index.
     */
    fun pathPrefix(row: StoredRow): String? {
        val suffix = "/${row.addressIndex}"
        if (!row.derivationPath.endsWith(suffix)) return null
        return row.derivationPath.removeSuffix(row.addressIndex.toString())
    }

    /**
     * Plans the inserts that make a pool contiguous from index 0 through
     * the window the engine holds in memory: the highest stored index, or
     * the highest used index plus [gapLimit], whichever is higher.
     * Persisting the widened tail too keeps rows that sync derives later
     * from landing above a new run of missing indices.
     *
     * Missing indices below [holeCeiling] count as [PoolPlan.holes]. It
     * defaults to the current highest stored index; a caller that has
     * already seen this pool earlier in the process passes the value it
     * captured then ([holeCeiling]), so rows that sync persisted since
     * cannot turn the in-memory tail into apparent holes.
     */
    fun plan(rows: List<StoredRow>, gapLimit: Int, holeCeiling: Int = holeCeiling(rows)): PoolPlan {
        if (rows.isEmpty()) return PoolPlan(emptyList(), 0)
        val present = rows.mapTo(HashSet(rows.size)) { it.addressIndex }
        val storedMax = present.max()
        val highestUsed = rows.filter { it.isUsed }.maxOfOrNull { it.addressIndex } ?: -1
        val upTo = maxOf(storedMax, highestUsed + gapLimit)
        val missing = (0..upTo).filter { it !in present }
        return PoolPlan(missing, missing.count { it < holeCeiling })
    }

    /** The address at [addressIndex] on [chain] (0 external, 1 internal) of [account]. */
    fun derive(
        account: DeterministicKey,
        chain: Int,
        addressIndex: Int,
        pathPrefix: String,
        params: NetworkParameters
    ): DerivedRow {
        val chainKey = HDKeyDerivation.deriveChildKey(account, ChildNumber(chain, false))
        return derive(chainKey, addressIndex, pathPrefix, params)
    }

    /** As [derive], from an already-derived chain key, for deriving many rows of one pool. */
    fun derive(
        chainKey: DeterministicKey,
        addressIndex: Int,
        pathPrefix: String,
        params: NetworkParameters
    ): DerivedRow {
        val key = HDKeyDerivation.deriveChildKey(chainKey, ChildNumber(addressIndex, false))
        return DerivedRow(
            addressIndex = addressIndex,
            address = Address.fromKey(params, key).toBase58(),
            publicKey = key.pubKey,
            derivationPath = pathPrefix + addressIndex
        )
    }

    /**
     * True when [chainKey] reproduces the stored address of every row in
     * [samples]. The repair refuses a pool that fails this, so it can never
     * insert rows derived from the wrong key or chain.
     */
    fun matchesStoredRows(
        chainKey: DeterministicKey,
        samples: List<StoredRow>,
        pathPrefix: String,
        params: NetworkParameters
    ): Boolean = samples.isNotEmpty() && samples.all { row ->
        derive(chainKey, row.addressIndex, pathPrefix, params).address == row.address
    }

    /** Up to three stored rows spread across the pool, for [matchesStoredRows]. */
    fun verificationSamples(rows: List<StoredRow>): List<StoredRow> {
        val sorted = rows.sortedBy { it.addressIndex }
        return listOfNotNull(sorted.firstOrNull(), sorted.getOrNull(sorted.size / 2), sorted.lastOrNull())
            .distinctBy { it.addressIndex }
    }
}
