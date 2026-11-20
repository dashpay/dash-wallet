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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.shielded.assetLockMaxFeeReserve
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Utils

// Advanced-mode External Send, Core → third-party Platform address (MO-1121):
// the persisted recipient record the SDK's resume contract requires, and the
// fee/Max arithmetic. See ManagedPlatformWallet.fundFromAssetLockExternal.

/**
 * The Platform-fee margin, in duffs, a Core → Platform lock carries on top of what the
 * payee receives: `amount + margin` is locked, the payee is credited exactly `amount`,
 * and the wallet's own remainder output receives the rest, out of which Platform takes
 * the transition fee (rs-platform-wallet `remainder_fee_strategy` charges the fee to the
 * remainder, never to the payee).
 *
 * The value is the DPP admission floor of an `AddressFundingFromAssetLock` with 0 inputs
 * and 2 outputs (rs-dpp `calculate_address_funding_from_asset_lock_min_required_fee`):
 * `required_asset_lock_duff_balance_for_processing_start_for_address_funding` (50 000
 * duffs, dpp state-transition versions v1–v4) + 2 × `address_funds_transfer_output_cost`
 * (6 000 000 credits = 6 000 duffs each, state-transition min fees v1) = 62 000 duffs.
 * rs-platform-wallet refuses a lock below that floor before building it
 * (`enforce_asset_lock_amount_covers_address_funding_floor`), so a lock of
 * `amount + margin` always clears it. The metered fee itself is not knowable before the
 * send; whatever the margin does not use stays on the wallet's own Platform address.
 */
internal const val PLATFORM_FUNDING_FEE_MARGIN_DUFFS = 62_000L

/**
 * The smallest explicit Platform output, in credits (`min_output_amount`, dpp
 * state-transition versions v1–v4). A smaller payee output would be rejected by Platform
 * AFTER the lock is broadcast, so the send refuses it before building anything.
 */
internal const val PLATFORM_MIN_OUTPUT_CREDITS = 500_000L

/** The BIP44 Core account the lock is funded from (as every other SDK asset-lock build). */
internal const val EXTERNAL_FUNDING_ACCOUNT_INDEX = 0

/** The DIP-17 Platform payment account that owns the remainder address. */
internal const val EXTERNAL_FUNDING_PLATFORM_ACCOUNT_INDEX = 0

/**
 * SDK `AssetLockFundingType::AssetLockAddressTopUp` (`asset_locks.fundingTypeRaw == 4`):
 * how the SDK tracks a Platform-address funding lock, owned or external alike. No other
 * app sweep reads this type (the shielded sweep reads 5, identity recovery 0–2).
 */
internal const val ASSET_LOCK_FUNDING_TYPE_ADDRESS_TOPUP = 4

/** The Core → Platform lock in duffs: [amountDuffs] plus [PLATFORM_FUNDING_FEE_MARGIN_DUFFS]. */
internal fun platformFundingLockDuffs(amountDuffs: Long): Long =
    Math.addExact(amountDuffs, PLATFORM_FUNDING_FEE_MARGIN_DUFFS)

/**
 * The fee shown for a Core → Platform send, in duffs (an estimate, "~"): the L1
 * asset-lock fee reserve ([assetLockMaxFeeReserve], an upper bound) plus the Platform
 * margin, whose unused part stays on the wallet's own Platform address.
 */
internal fun coreToPlatformFeeDuffs(spendableUtxoCount: Int): Long =
    Math.addExact(assetLockMaxFeeReserve(spendableUtxoCount).duffs, PLATFORM_FUNDING_FEE_MARGIN_DUFFS)

/**
 * The Core → Platform Max, in duffs, or null when not positive: the asset-lock-eligible
 * balance less the L1 fee reserve and the Platform margin.
 */
internal fun coreToPlatformMaxDuffs(eligibleDuffs: Long, spendableUtxoCount: Int): Long? =
    (eligibleDuffs - coreToPlatformFeeDuffs(spendableUtxoCount)).takeIf { it > 0 }

/**
 * One Core → Platform external funding, persisted BEFORE the SDK call and kept until
 * the lock is consumed: the tracked lock stores no recipients, so this record is the
 * only thing that binds the lock ([outPointHex], recorded from `onAssetLockBuilt`) to
 * its payee. A resume must use exactly [recipients].
 *
 * [completionUnconfirmed]: an automatic resume got Platform's `AssetLockAlreadyConsumed` for
 * this lock (iOS `AssetLockRecoveryService`'s `completionUnconfirmed`). That answer is
 * unauthenticated, so the record is never deleted for it: the store moves it out of the
 * pending slot into its completion-unconfirmed set ([PlatformFundingStore.markCompletionUnconfirmed]),
 * where it no longer blocks a new Core → Platform send and is no longer resumed automatically,
 * but is kept for an explicit retry. Its lock stays non-terminal in the SDK.
 *
 * [toString] is redacted: no address, amount or wallet id is ever logged.
 */
internal class PendingPlatformFunding(
    val id: String,
    val walletIdHex: String,
    val lockDuffs: Long,
    val destination: PlatformAddressBytes,
    val explicitCredits: Long,
    val remainder: PlatformAddressBytes,
    /** `<display txid hex>:<vout>`, the SDK `asset_locks` key; null until the lock is built. */
    val outPointHex: String?,
    val completionUnconfirmed: Boolean = false
) {
    init {
        require(id.isNotEmpty() && FIELD_SEPARATOR !in id) { "malformed id" }
        require(walletIdFromHex(walletIdHex) != null) { "malformed wallet id" }
        require(lockDuffs > 0) { "lock must be positive" }
        require(explicitCredits > 0) { "explicit amount must be positive" }
        require(destination.addressType == 0 && destination.hash.size == 20) { "destination must be P2PKH" }
        require(remainder.addressType == 0 && remainder.hash.size == 20) { "remainder must be P2PKH" }
        require(!destination.hash.contentEquals(remainder.hash)) { "destination and remainder must differ" }
        require(outPointHex == null || parseOutPointHex(outPointHex) != null) { "malformed outpoint" }
        require(!completionUnconfirmed || outPointHex != null) { "only a built lock can be completion-unconfirmed" }
    }

    /** The recipients, in the SDK's shape: the payee's explicit amount and the remainder. */
    val recipients: List<org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.FundRecipient>
        get() = listOf(
            org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.FundRecipient(
                destination.addressType, destination.hash.copyOf(), explicitCredits
            ),
            org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.FundRecipient(
                remainder.addressType, remainder.hash.copyOf(), null
            )
        )

    fun withOutPoint(outPointHex: String): PendingPlatformFunding =
        PendingPlatformFunding(id, walletIdHex, lockDuffs, destination, explicitCredits, remainder, outPointHex)

    /** This record, marked [completionUnconfirmed] (its lock must have been built). */
    fun asCompletionUnconfirmed(): PendingPlatformFunding = PendingPlatformFunding(
        id, walletIdHex, lockDuffs, destination, explicitCredits, remainder, outPointHex, completionUnconfirmed = true
    )

    /** Same content, field by field (the hashes by value). */
    fun sameAs(other: PendingPlatformFunding): Boolean =
        id == other.id &&
            walletIdHex.equals(other.walletIdHex, ignoreCase = true) &&
            lockDuffs == other.lockDuffs &&
            destination.addressType == other.destination.addressType &&
            destination.hash.contentEquals(other.destination.hash) &&
            explicitCredits == other.explicitCredits &&
            remainder.addressType == other.remainder.addressType &&
            remainder.hash.contentEquals(other.remainder.hash) &&
            outPointHex == other.outPointHex &&
            completionUnconfirmed == other.completionUnconfirmed

    /**
     * The stored form. An unmarked record keeps the original ten fields (what every earlier
     * build wrote and reads); [completionUnconfirmed] adds an eleventh, [COMPLETION_UNCONFIRMED].
     */
    fun encode(): String = listOfNotNull(
        FORMAT_VERSION,
        id,
        walletIdHex.lowercase(),
        lockDuffs.toString(),
        destination.addressType.toString(),
        Utils.HEX.encode(destination.hash),
        explicitCredits.toString(),
        remainder.addressType.toString(),
        Utils.HEX.encode(remainder.hash),
        outPointHex.orEmpty(),
        COMPLETION_UNCONFIRMED.takeIf { completionUnconfirmed }
    ).joinToString(FIELD_SEPARATOR)

    override fun toString(): String =
        "PendingPlatformFunding(id=$id, outPoint=${if (outPointHex == null) "none" else "recorded"}, " +
            "completionUnconfirmed=$completionUnconfirmed, <redacted>)"

    companion object {
        private const val FORMAT_VERSION = "1"
        private const val FIELD_SEPARATOR = "|"
        private const val FIELD_COUNT = 10
        private const val COMPLETION_UNCONFIRMED = "u"

        /**
         * Decode a stored record. Malformed is NOT absent: this throws
         * [IllegalStateException], and every caller fails closed on it. A record written
         * before [completionUnconfirmed] existed (ten fields) decodes as not marked.
         */
        fun decode(encoded: String): PendingPlatformFunding {
            val fields = encoded.split(FIELD_SEPARATOR)
            val marked = fields.size == FIELD_COUNT + 1 && fields[FIELD_COUNT] == COMPLETION_UNCONFIRMED
            check((fields.size == FIELD_COUNT || marked) && fields[0] == FORMAT_VERSION) {
                "malformed pending platform funding"
            }
            return try {
                PendingPlatformFunding(
                    id = fields[1],
                    walletIdHex = fields[2],
                    lockDuffs = fields[3].toLong(),
                    destination = PlatformAddressBytes(fields[4].toInt(), hash20(fields[5])),
                    explicitCredits = fields[6].toLong(),
                    remainder = PlatformAddressBytes(fields[7].toInt(), hash20(fields[8])),
                    outPointHex = fields[9].ifEmpty { null },
                    completionUnconfirmed = marked
                )
            } catch (e: IllegalArgumentException) {
                // NumberFormatException included; the message carries no field value.
                throw IllegalStateException("malformed pending platform funding")
            }
        }

        private fun hash20(hex: String): ByteArray {
            require(hex.length == 40 && hex.all { Character.digit(it, 16) >= 0 }) { "malformed hash" }
            return Utils.HEX.decode(hex)
        }
    }
}

/**
 * Durable store of the (at most one) [PendingPlatformFunding]. Every call throws when the
 * store cannot be read or written; [read] also throws on a malformed record.
 */
internal interface PlatformFundingStore {
    /** The pending record, null when there is none. */
    suspend fun read(): PendingPlatformFunding?

    /** Replace the record and return only once it is durable. */
    suspend fun write(record: PendingPlatformFunding)

    /**
     * [write] from a non-coroutine thread (the SDK's `onAssetLockBuilt`): blocks until the
     * record is durable and reads back as written; throws otherwise.
     */
    fun writeBlocking(record: PendingPlatformFunding)

    /** Remove the record if it is still [id]'s (a malformed or different record is kept). */
    suspend fun clear(id: String)

    /**
     * In one durable transaction, move the pending record out of the pending slot into the
     * completion-unconfirmed set, marked ([PendingPlatformFunding.completionUnconfirmed]),
     * if it is still [id]'s and its lock was built; otherwise change nothing. Never deletes it.
     */
    suspend fun markCompletionUnconfirmed(id: String)

    /**
     * The completion-unconfirmed records (all wallets), never resumed automatically and never
     * blocking a new send; kept for an explicit retry. Throws when unreadable or malformed.
     */
    suspend fun readCompletionUnconfirmed(): List<PendingPlatformFunding>
}

/**
 * [PlatformFundingStore] over [DashPayConfig.EXTERNAL_PLATFORM_FUNDING_PENDING] and
 * [DashPayConfig.EXTERNAL_PLATFORM_FUNDING_UNCONFIRMED]; a wallet wipe clears both
 * (DashPayConfig's default `clearAll`).
 */
internal class DashPayConfigPlatformFundingStore(private val config: DashPayConfig) : PlatformFundingStore {
    override suspend fun read(): PendingPlatformFunding? =
        config.getPreservingErrors(DashPayConfig.EXTERNAL_PLATFORM_FUNDING_PENDING)
            ?.takeIf { it.isNotEmpty() }
            ?.let(PendingPlatformFunding::decode)

    // One DataStore transaction; it returns only after the file is written and synced.
    override suspend fun write(record: PendingPlatformFunding) =
        config.set(DashPayConfig.EXTERNAL_PLATFORM_FUNDING_PENDING, record.encode())

    override fun writeBlocking(record: PendingPlatformFunding) = runBlocking {
        write(record)
        val back = read()
        check(back != null && back.sameAs(record)) { "pending platform funding did not read back as written" }
    }

    override suspend fun clear(id: String) = config.editPreferences { prefs ->
        val stored = prefs[DashPayConfig.EXTERNAL_PLATFORM_FUNDING_PENDING]
        val storedId = try {
            stored?.takeIf { it.isNotEmpty() }?.let { PendingPlatformFunding.decode(it).id }
        } catch (e: IllegalStateException) {
            null
        }
        if (storedId == id) prefs.remove(DashPayConfig.EXTERNAL_PLATFORM_FUNDING_PENDING)
    }

    override suspend fun markCompletionUnconfirmed(id: String) = config.editPreferences { prefs ->
        val stored = prefs[DashPayConfig.EXTERNAL_PLATFORM_FUNDING_PENDING]
        val record = try {
            stored?.takeIf { it.isNotEmpty() }?.let(PendingPlatformFunding::decode)
        } catch (e: IllegalStateException) {
            null
        }
        if (record != null && record.id == id && record.outPointHex != null) {
            val unconfirmed = prefs[DashPayConfig.EXTERNAL_PLATFORM_FUNDING_UNCONFIRMED].orEmpty()
            prefs[DashPayConfig.EXTERNAL_PLATFORM_FUNDING_UNCONFIRMED] =
                unconfirmed + record.asCompletionUnconfirmed().encode()
            prefs.remove(DashPayConfig.EXTERNAL_PLATFORM_FUNDING_PENDING)
        }
    }

    override suspend fun readCompletionUnconfirmed(): List<PendingPlatformFunding> =
        config.getPreservingErrors(DashPayConfig.EXTERNAL_PLATFORM_FUNDING_UNCONFIRMED)
            .orEmpty()
            .map(PendingPlatformFunding::decode)
}
