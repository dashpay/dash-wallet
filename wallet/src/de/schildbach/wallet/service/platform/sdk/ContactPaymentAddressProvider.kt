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

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.sdkL1ScanCaughtUp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Utils
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.FriendChainAccess
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Where a contact payment address came from — logged on every pick. */
enum class ContactAddressSource { SDK, DASHJ }

/**
 * Who a contact payment goes to and on which channel: [ourUserId] and
 * [contactUserId] are base58 identity ids, [accountReference] is the
 * `accountReference` of the contact request [contactUserId] addressed to
 * [ourUserId] that the send screen selected (the newest one). That request
 * carries the contact's xpub, i.e. the chain the payment address comes from.
 */
data class ContactPaymentTarget(
    val ourUserId: String,
    val contactUserId: String,
    val accountReference: Int
)

/**
 * A payment address for [target] and the stack that supplied it. [index] is
 * the address's derivation index on the channel when known (always for an SDK
 * pick; for dashj when its cursor could be read) — recorded with the claim so
 * the channel's frontier survives the record's expiry ([PaidContactAddressStore]).
 * Null is only usable on a channel without a folded frontier: once one exists
 * the provider never returns an unindexed address and the claim refuses one.
 */
data class ContactPaymentAddress(
    val target: ContactPaymentTarget,
    val address: Address,
    val source: ContactAddressSource,
    val index: Int? = null
)

/**
 * [ContactPaymentAddressProvider.sendThenMarkUsed] refused to pay [payment]:
 * its address is already recorded paid, another send is paying it, or the
 * chain cannot vouch that it is unpaid (post-cutover: the SDK's transaction
 * store shows it paid, cannot be read, or does not yet hold the wallet's
 * earlier payments). Nothing was sent; resolve the contact's address again
 * ([ContactPaymentAddressProvider.nextAddress]) for a new one.
 */
class ContactAddressAlreadyPaidException(val payment: ContactPaymentAddress) :
    IllegalStateException(
        "contact payment address for ${payment.target.contactUserId} was already paid, is being paid or " +
            "cannot be shown unpaid; nothing was sent — resolve the contact's payment address again"
    )

/**
 * [ContactPaymentAddressProvider.sendThenMarkUsed] refused to pay [payment]
 * because its address could not be claimed durably: the paid-address store
 * could not be read (its exclusions are unknown) or the claim could not be
 * written to disk (it would not survive a process death). Nothing was sent.
 * Resolving the address again does not help; the user can try again later.
 */
class ContactPaymentNotRecordableException(val payment: ContactPaymentAddress) :
    IllegalStateException(
        "contact payment to ${payment.target.contactUserId} refused: its address could not be recorded " +
            "durably before sending; nothing was sent"
    )

/**
 * What a contact payment's send reports to
 * [ContactPaymentAddressProvider.sendThenMarkUsed] while it runs.
 */
class ContactSendSignals internal constructor(private val onBroadcast: (txid: String?) -> Unit) {
    /** Set by [notBroadcast]. */
    @Volatile
    internal var provenNotBroadcast: Boolean = false
        private set

    /**
     * The payment may have gone out: the network has transaction [txid], or,
     * with [txid] null, the outcome is unknown. Records the address paid at
     * once — before any later step of the send that can still throw.
     */
    fun broadcast(txid: String?) = onBroadcast(txid)

    /**
     * The send is failing and provably nothing was broadcast (the SDK reported
     * NotBroadcast, or dashj failed before committing the transaction). Call
     * right before throwing; the address is then given back for a retry. A
     * send that throws without calling it — including a cancellation — has an
     * unknown outcome, and the address is kept as paid.
     */
    fun notBroadcast() {
        provenNotBroadcast = true
    }
}

/** One row of the SDK's persisted address pool for a contact's sending account. */
data class SdkContactPoolAddress(
    val address: String,
    val index: Int,
    val isUsed: Boolean,
    val poolTypeTag: Int
)

/**
 * What the SDK's Room mirror holds for our sending (`DashpayExternalAccount`)
 * account to one contact.
 *
 * @property incomingRequestFound whether the SDK has the contact's request to us.
 * @property paymentChannelBroken the SDK's verdict that the channel cannot be
 *   paid on (e.g. the contact's xpub failed to decrypt). Only stamped on
 *   established rows.
 * @property externalAccountReference the `accountReference` of the incoming
 *   request the SDK built the sending account from; null when the SDK knows of
 *   no account built for the current request.
 * @property addresses the account's address pool, or null when the SDK has no
 *   `DashpayExternalAccount` row for this contact.
 */
data class SdkContactSendingAccount(
    val incomingRequestFound: Boolean,
    val paymentChannelBroken: Boolean,
    val externalAccountReference: Int?,
    val addresses: List<SdkContactPoolAddress>?
)

/**
 * Where the SDK's scan of a wallet stands, for the recovery barrier of
 * [ContactPaymentAddressProvider] (the paid-address history,
 * [PaidContactAddressStore.paidHistoryKnown]; PR #1608 review, round 10).
 *
 * @property tipHeight the header-chain tip the SDK knows
 *   ([ShadowSyncProgress.bestKnownTipHeight]); 0 when unknown.
 * @property processedHeight the height through which the wallet's
 *   transactions are processed AND in the SDK's store, where
 *   [ContactPaymentChainView.paysAddress] reads them: the lower of the
 *   engine's committed cursor ([ShadowSyncProgress.walletSyncedHeight]) and
 *   the store's own `WalletEntity.syncedHeight`, which the SDK commits in the
 *   same Room transaction as the round's transactions. 0 when either is unknown.
 * @property scanSettled the scan is caught up ([sdkL1ScanCaughtUp]), its block
 *   pipeline is not lagging and no rescan was just armed.
 */
data class ContactChainScanState(
    val tipHeight: Long,
    val processedHeight: Long,
    val scanSettled: Boolean
) {
    /**
     * Whether every transaction up to the known tip — and at least up to
     * [floor] — is in the SDK's store. No tolerance: a payment in the last
     * block or two before the tip is exactly the one a lagging cursor has
     * not imported yet.
     */
    fun processedThroughTip(floor: Long = 0L): Boolean =
        scanSettled && tipHeight > 0 && processedHeight >= tipHeight && processedHeight >= floor
}

/**
 * [ContactChainScanState] from the app's L1 sync [progress], the SDK store's
 * durable `syncedHeight` ([durableSyncedHeight], null when unknown) and whether
 * a rescan was just armed.
 *
 * Deliberately stricter than the balance-save gate ([sdkL1ScanCaughtUp] and
 * not [ShadowSyncProgress.blockPipelineLagging]): that gate accepts cursors
 * [ShadowSyncProgress.SCAN_TIP_TOLERANCE_BLOCKS] behind the tip, which is
 * harmless for a balance but not for the paid-address history — a payment in
 * one of those blocks would not be in the store yet, and its address would be
 * offered again ([ContactChainScanState.processedThroughTip]).
 */
internal fun contactChainScanState(
    progress: ShadowSyncProgress,
    durableSyncedHeight: Long?,
    rescanArmed: Boolean
): ContactChainScanState = ContactChainScanState(
    tipHeight = progress.bestKnownTipHeight,
    processedHeight = minOf(progress.walletSyncedHeight, durableSyncedHeight ?: 0L).coerceAtLeast(0L),
    scanSettled = sdkL1ScanCaughtUp(progress) && !progress.blockPipelineLagging && !rescanArmed
)

/**
 * What the blockchain shows about contact payment addresses, through the SDK —
 * the recovery source of truth for payments [PaidContactAddressStore] has no
 * record of (made before a recovery, on a lost device; PR #1608 review,
 * round 9).
 */
interface ContactPaymentChainView {
    /** The id of the SDK wallet the app uses, or null when none is loaded (or more than one). */
    fun walletId(): String?

    /**
     * Where the SDK's scan of [walletId] stands ([ContactChainScanState]), or
     * null when that cannot be read.
     */
    suspend fun scanState(walletId: String): ContactChainScanState?

    /**
     * Why the SDK cannot yet vouch that every earlier transaction of
     * [ourUserId]'s DIP-15 contact accounts in [walletId] is one its scan
     * tested, or null when it can ([contactHistoryUncoveredBecause]): contact
     * discovery finished, a receiving account registered for every contact
     * we sent a request to, and each of them in the SDK's durable backfill
     * record. That record vouches for the blocks from each account's
     * checkpoint up to the store's synced height, so the caller still needs
     * [scanState] processed through the tip — read AFTER this, so a rewind
     * for an account registered in between shows as a lower height.
     */
    suspend fun contactHistoryUncoveredBecause(walletId: String, ourUserId: String): String?

    /**
     * Whether a transaction in the SDK's store pays [address] (an output to
     * it); null when the store cannot be read. Blocking.
     */
    fun paysAddress(address: Address): Boolean?

    companion object {
        /** Tests' default: processed through the tip and covered, nothing paid on chain. */
        internal val NOTHING_PAID = object : ContactPaymentChainView {
            override fun walletId(): String = "test-wallet"
            override suspend fun scanState(walletId: String) =
                ContactChainScanState(tipHeight = 1, processedHeight = 1, scanSettled = true)
            override suspend fun contactHistoryUncoveredBecause(walletId: String, ourUserId: String): String? = null
            override fun paysAddress(address: Address): Boolean = false
        }
    }
}

/** Size of one entry of the SDK's backfill cover set: owner, contact, account index, covered-from height. */
private const val COVER_SET_ENTRY_SIZE = 72

/** The int24–int26 layout: owner, contact, covered-from height; account index 0 implied. */
private const val LEGACY_COVER_SET_ENTRY_SIZE = 68

/**
 * [ownerId]'s entries of the SDK's durable DashPay backfill record
 * (`WalletEntity.dashPayBackfillCovered`): (contact id hex, receiving
 * account index) → the height the account is covered from — every block
 * from there up to the store's synced height was tested with the account's
 * addresses watched. Empty for no record; null when the blob cannot be
 * decoded. Like the SDK's own restore decoder it also reads the int24–int26
 * layout ([LEGACY_COVER_SET_ENTRY_SIZE]); a length that fits both layouts
 * is read as the one whose every owner is [ownerId], and as undecodable
 * when that does not single one out.
 */
internal fun decodeDashPayBackfillCoverSet(blob: ByteArray?, ownerId: ByteArray): Map<Pair<String, Int>, Long>? {
    if (blob == null || blob.isEmpty()) return emptyMap()
    class Entry(val owner: ByteArray, val contactHex: String, val index: Int, val coveredFrom: Long)
    fun uint32Le(offset: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((blob[offset + i].toLong() and 0xff) shl (8 * i)) }
    fun decode(size: Int): List<Entry>? = if (blob.size % size != 0) {
        null
    } else {
        (0 until blob.size / size).map { n ->
            val at = n * size
            Entry(
                owner = blob.copyOfRange(at, at + 32),
                contactHex = Utils.HEX.encode(blob.copyOfRange(at + 32, at + 64)),
                index = if (size == COVER_SET_ENTRY_SIZE) uint32Le(at + 64).toInt() else 0,
                coveredFrom = uint32Le(at + size - 4)
            )
        }
    }
    val current = decode(COVER_SET_ENTRY_SIZE)
    val legacy = decode(LEGACY_COVER_SET_ENTRY_SIZE)
    val entries = when {
        current != null && legacy != null -> {
            fun ours(entries: List<Entry>) = entries.all { it.owner.contentEquals(ownerId) }
            when {
                ours(current) && !ours(legacy) -> current
                ours(legacy) && !ours(current) -> legacy
                else -> return null
            }
        }
        else -> current ?: legacy ?: return null
    }
    return entries.filter { it.owner.contentEquals(ownerId) }
        .associate { (it.contactHex to it.index) to it.coveredFrom }
}

/**
 * Why the SDK cannot yet vouch for [ourUserId]'s DIP-15 contact-account
 * history, or null when it can (PR #1608 review, round 11). Reaching the tip
 * proves the history only of the scripts the scan watched: a payment funded
 * entirely from a DIP-15 receiving account (no BIP44 change) is in the SDK's
 * store only if that receiving account was watched when its block was
 * scanned — and L1 sync starts while contact bring-up is still running
 * (it times out or fails), with discovery unfinished or account builds
 * pending. The SDK rewinds for an account registered late (its provisioning
 * backfill) and records each account it has covered, with the height it is
 * covered from, in its durable backfill record. So the history is covered
 * when:
 * - [discoveryCompleted]: the SDK's contact discovery finished (before that
 *   its store may list none of the contacts, and so none of the accounts);
 * - every contact in [sentRequestContacts] (we sent a request to them, so
 *   they may have paid us) has a receiving account in [receivalAccounts];
 * - every receiving account is in [coverSet] (null: unreadable), covered
 *   from no later than its earliest contact request ([requestFloors]) or the
 *   wallet's birth checkpoint ([birthHeight] - 1), the SDK's own checkpoint
 *   rule; nothing it receives can be older.
 * Ids are contact identity ids in hex; receiving accounts are
 * (contact, account index).
 */
internal fun contactHistoryUncoveredBecause(
    discoveryCompleted: Boolean,
    sentRequestContacts: Set<String>,
    requestFloors: Map<String, Long>,
    birthHeight: Long,
    receivalAccounts: List<Pair<String, Int>>,
    coverSet: Map<Pair<String, Int>, Long>?
): String? {
    if (!discoveryCompleted) return "the SDK's contact discovery has not finished"
    val missing = sentRequestContacts - receivalAccounts.mapTo(HashSet()) { it.first }
    if (missing.isNotEmpty()) {
        return "${missing.size} contact(s) we sent a request to have no receiving account registered"
    }
    coverSet ?: return "the SDK's backfill record cannot be read"
    val birthCheckpoint = (birthHeight - 1).coerceAtLeast(0L)
    val uncovered = receivalAccounts.count { account ->
        val coveredFrom = coverSet[account] ?: return@count true
        coveredFrom > maxOf(birthCheckpoint, requestFloors[account.first] ?: birthCheckpoint)
    }
    return if (uncovered > 0) {
        "$uncovered receiving account(s) not yet covered by the SDK's backfill record"
    } else {
        null
    }
}

/** [ContactPaymentChainView] over the app's SDK and its L1 sync progress. */
internal class SdkContactPaymentChainView(
    private val sdkService: DashSdkService,
    private val l1ShadowSyncService: L1ShadowSyncService,
    /** [SdkWalletBinder.contactDiscoveryCompleted]. */
    private val contactDiscoveryCompleted: (walletId: String) -> Boolean
) : ContactPaymentChainView {
    override fun walletId(): String? = sdkService.loadedWalletIds().singleOrNull()

    override suspend fun scanState(walletId: String): ContactChainScanState? {
        // The tip first, the store's height after it: the height is never
        // older than the tip it is compared with.
        val progress = l1ShadowSyncService.progress.value
        val rescanArmed = sdkService.spvRescanArmedWithin(CutoverUiDataService.RESCAN_ARM_PERSIST_HOLD_MS)
        val db = sdkService.databaseOrNull() ?: return null
        val id = walletIdFromHex(walletId) ?: return null
        val durable = try {
            db.walletDao().getByWalletId(id)?.syncedHeight?.toLong()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("contact payment address: reading the SDK wallet's synced height failed", e)
            return null
        }
        return contactChainScanState(progress, durable, rescanArmed)
    }

    override suspend fun contactHistoryUncoveredBecause(walletId: String, ourUserId: String): String? {
        // Discovery first: the store read after it then holds what it fetched.
        val discovered = contactDiscoveryCompleted(walletId)
        val db = sdkService.databaseOrNull() ?: return "the SDK store is not available"
        val id = walletIdFromHex(walletId) ?: return "malformed SDK wallet id"
        return try {
            val owner = Identifier.from(ourUserId).toBuffer()
            val wallet = db.walletDao().getByWalletId(id) ?: return "the SDK store has no row for the wallet"
            val requests = db.dashpayDao().getContactRequestsByOwner(owner)
            val receival = db.accountDao()
                .observeByWalletAndType(id, ACCOUNT_TYPE_TAG_DASHPAY_RECEIVING_FUNDS)
                .first()
                .filter { it.userIdentityId.contentEquals(owner) }
            contactHistoryUncoveredBecause(
                discoveryCompleted = discovered,
                sentRequestContacts = requests.filter { it.isOutgoing }
                    .mapTo(HashSet()) { Utils.HEX.encode(it.contactIdentityId) },
                requestFloors = requests.groupBy { Utils.HEX.encode(it.contactIdentityId) }
                    .mapValues { (_, pair) -> pair.minOf { it.coreHeightCreatedAt.toLong() } },
                birthHeight = wallet.birthHeight.toLong(),
                receivalAccounts = receival.mapNotNull { account ->
                    account.friendIdentityId?.let { Utils.HEX.encode(it) to account.accountIndex }
                },
                coverSet = decodeDashPayBackfillCoverSet(wallet.dashPayBackfillCovered, owner)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("contact payment address: reading the SDK's contact accounts and backfill record failed", e)
            "the SDK's contact accounts cannot be read"
        }
    }

    override fun paysAddress(address: Address): Boolean? {
        val db = sdkService.databaseOrNull() ?: return null
        return try {
            sdkStorePaysAddress(db, address)
        } catch (e: Exception) {
            log.warn("contact payment address: reading the SDK's transaction store failed", e)
            null
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(SdkContactPaymentChainView::class.java)
    }
}

/**
 * Whether the SDK store has a transaction paying [address]: a `txos` row for
 * it (the SDK mirrors outputs to the contact sending accounts it watches), or
 * — independent of which accounts existed when the transaction was processed
 * — a stored raw transaction containing its output script. A chance match of
 * the 25-byte script elsewhere only skips an address (fail-safe). Blocking.
 */
internal fun sdkStorePaysAddress(db: DashDatabase, address: Address): Boolean {
    val readable = db.openHelper.readableDatabase
    val inTxos = readable.query(
        SimpleSQLiteQuery("SELECT 1 FROM txos WHERE address = ? LIMIT 1", arrayOf<Any>(address.toBase58()))
    ).use { it.moveToFirst() }
    if (inTxos) return true
    val script = ScriptBuilder.createOutputScript(address).program
    return readable.query(
        SimpleSQLiteQuery(
            "SELECT 1 FROM transactions WHERE length(transactionData) > 0 AND instr(transactionData, ?) > 0 LIMIT 1",
            arrayOf<Any>(script)
        )
    ).use { it.moveToFirst() }
}

/** Outcome of [selectSdkContactAddress]. */
sealed interface SdkContactAddressSelection {
    data class Selected(val address: String, val index: Int) : SdkContactAddressSelection
    data class Unavailable(val reason: String) : SdkContactAddressSelection
}

/**
 * Picks the SDK's next payment address for a contact, or says why it cannot.
 *
 * The address must come from the sending account built from the SAME contact
 * request the send screen selected: [expectedAccountReference] has to equal the
 * SDK's [SdkContactSendingAccount.externalAccountReference]. A different
 * reference means a different xpub (the contact re-issued their request), and
 * paying an address from the wrong one would go to a chain the contact may not
 * watch.
 *
 * Among the account's persisted addresses it takes the lowest-index one not
 * marked used — the engine's own rule (`AddressPool::next_unused`, which the
 * SDK's `send_payment` uses) — skipping what the mirror may not have flagged
 * yet (the engine writes used-flags behind itself, one persistence pass later):
 * - every index below [dashjCursor], dashj's persisted sending cursor for the
 *   same contact and channel (null: dashj has no chain for it). dashj and the
 *   SDK derive this chain from the same contact xpub, so index i is the same
 *   address on both, and every index below dashj's cursor has been paid;
 * - [alreadyPaid], the addresses recorded paid ([PaidContactAddressStore]),
 *   which covers the time until dashj's advanced cursor reaches disk;
 * - every index at or below [paidFrontier], the highest index recorded paid
 *   or claimed on this channel ([PaidContactAddressStore.paidFrontier]) —
 *   which survives the expiry of the records and a wallet whose dashj chain
 *   is behind (an older key backup, a restore).
 */
internal fun selectSdkContactAddress(
    account: SdkContactSendingAccount?,
    expectedAccountReference: Int,
    alreadyPaid: Set<String>,
    dashjCursor: Int? = null,
    paidFrontier: Int? = null
): SdkContactAddressSelection {
    fun unavailable(reason: String) = SdkContactAddressSelection.Unavailable(reason)
    if (account == null) return unavailable("SDK store not available")
    if (!account.incomingRequestFound) return unavailable("SDK has no contact request from this contact")
    if (account.paymentChannelBroken) return unavailable("SDK marked the payment channel broken")
    val builtFrom = account.externalAccountReference
        ?: return unavailable("SDK has no sending account built for the current contact request")
    if (builtFrom != expectedAccountReference) {
        return unavailable(
            "SDK sending account was built from accountReference $builtFrom, " +
                "the payment uses $expectedAccountReference"
        )
    }
    val addresses = account.addresses ?: return unavailable("SDK has no sending account for this contact")
    if (addresses.isEmpty()) return unavailable("SDK sending account has no addresses persisted")
    if (addresses.map { it.poolTypeTag }.distinct().size > 1) {
        return unavailable("SDK sending account has more than one address pool")
    }
    val floor = maxOf(dashjCursor ?: 0, paidFrontier?.let { it + 1 } ?: 0)
    val next = addresses
        .filter { !it.isUsed && it.address !in alreadyPaid && it.index >= floor }
        .minByOrNull { it.index }
        ?: return unavailable(
            if (floor > 0) {
                "SDK sending account has no unused address persisted at or above index $floor " +
                    "(dashj's cursor $dashjCursor, paid frontier $paidFrontier)"
            } else {
                "SDK sending account has no unused address persisted"
            }
        )
    return SdkContactAddressSelection.Selected(next.address, next.index)
}

/**
 * The address a payment to a DashPay contact goes to.
 *
 * Before the cutover dashj is live and serves it (its current SENDING-chain
 * key, which it advances itself when it sees the payment). After the cutover
 * dashj is held and never sees the payment, so its current key never moves and
 * every payment would reuse one address — breaking DIP-15's one address per
 * payment and linking the payments. Post-cutover the address therefore comes
 * from the SDK's sending account for the contact (`DashpayExternalAccount`,
 * whose pool the engine marks used as it sees payments), read from the SDK's
 * Room mirror. dashj is the fallback whenever the SDK cannot answer for this
 * contact and channel — see [selectSdkContactAddress] for the cases, the real
 * one being a contact whose channel the SDK marked broken.
 *
 * Either way, once the payment is broadcast ([sendThenMarkUsed]) the paid key
 * is marked used on dashj's sending chain, so the dashj fallback moves on too,
 * and the address is recorded in [paidAddresses].
 *
 * Why the Room mirror and not a live engine read: the Kotlin SDK exposes no
 * call that returns a contact sending account's next address
 * (`platform_wallet_account_address_pools` exists in the Rust FFI but has no
 * JNI binding; `Dashpay.sendPayment` derives one only inside its own send).
 * The mirror trails the engine by one persistence pass, so an SDK pick is
 * checked against what is known to be paid and survives a restart:
 * - dashj's sending cursor for the contact and channel (saved with the dashj
 *   wallet, advanced by every [sendThenMarkUsed]) — the state that already
 *   exists, so no SDK address below it is offered;
 * - [paidAddresses], a small file written BEFORE each payment is sent (the
 *   address is claimed there, so two sends can never use one address; a claim
 *   that cannot be read or written refuses the send). dashj's cursor reaches
 *   disk only on the next wallet autosave (up to a minute on a large wallet),
 *   and dashj cannot always advance it (no chain yet, key not found); the file
 *   covers both until dashj has, and keeps each channel's paid frontier after
 *   that, for a later wallet whose chain is behind.
 *
 * Neither survives a recovery on another device or after the app's data was
 * cleared: the file is not backed up, and the restored dashj chain starts at
 * an old cursor. The blockchain is the source of truth then
 * ([ContactPaymentChainView]): post-cutover no address is handed out until the
 * SDK has registered and covered every DIP-15 contact account
 * ([ContactPaymentChainView.contactHistoryUncoveredBecause]) and processed the
 * recovered wallet's transactions through the chain tip into its store
 * ([ContactChainScanState.processedThroughTip],
 * [PaidContactAddressStore.paidHistoryKnown]), no candidate the SDK's
 * transaction store shows paid is ever handed out, and [sendThenMarkUsed]
 * checks the store again under its claim gate. SDK candidates the store shows
 * paid are recorded ([PaidContactAddressStore.recordPaidOnChain]), so a lookup
 * that stops at [MAX_DASHJ_PAID_SKIPS] of them leaves the next one to continue.
 */
@Singleton
class ContactPaymentAddressProvider internal constructor(
    /** Persisted cutover state is CUT_OVER / SETTLED ([SdkL1SendService.cutoverCommitted]). */
    private val cutoverCommitted: suspend () -> Boolean,
    /** The SDK mirror for (our identity id, contact identity id); null when unavailable. */
    private val sdkAccountReader: suspend (ByteArray, ByteArray) -> SdkContactSendingAccount?,
    /** dashj's current SENDING-chain address ([IdentityRepository.getNextContactAddress]). */
    private val dashjNextAddress: (contactUserId: String, accountReference: Int) -> Address?,
    /** [IdentityRepository.markContactAddressUsed]. */
    private val dashjMarkUsed: (
        contactUserId: String,
        accountReference: Int,
        address: Address
    ) -> FriendChainAccess.MarkResult?,
    private val params: NetworkParameters,
    /** [IdentityRepository.getContactSendingCursor]. */
    private val dashjSendingCursor: (contactUserId: String, accountReference: Int) -> Int? = { _, _ -> null },
    /** Addresses recorded paid; survives a restart when file-backed. */
    private val paidAddresses: PaidContactAddressStore = PaidContactAddressStore(file = null),
    /** Where the blocking dashj and file work runs. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** What the chain shows paid ([ContactPaymentChainView]). */
    private val chain: ContactPaymentChainView = ContactPaymentChainView.NOTHING_PAID
) {
    /**
     * SDK wallet id → the header tip seen when its paid-address history was
     * first found unknown in this process ([paidHistoryKnown]).
     */
    private val historyUnknownAtTip = ConcurrentHashMap<String, Long>()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        sdkL1SendService: SdkL1SendService,
        sdkService: DashSdkService,
        identityRepository: IdentityRepository,
        l1ShadowSyncService: L1ShadowSyncService,
        sdkWalletBinder: SdkWalletBinder
    ) : this(
        cutoverCommitted = { sdkL1SendService.cutoverCommitted() },
        sdkAccountReader = { ourId, contactId ->
            readSdkContactSendingAccount(
                sdkService.databaseOrNull(),
                sdkService.loadedWalletIds(),
                ourId,
                contactId
            )
        },
        dashjNextAddress = identityRepository::getNextContactAddress,
        dashjMarkUsed = identityRepository::markContactAddressUsed,
        params = Constants.NETWORK_PARAMETERS,
        dashjSendingCursor = identityRepository::getContactSendingCursor,
        paidAddresses = PaidContactAddressStore.app(context.filesDir),
        chain = SdkContactPaymentChainView(sdkService, l1ShadowSyncService, sdkWalletBinder::contactDiscoveryCompleted)
    )

    /**
     * The address to pay [target] on, or null when neither stack can supply one.
     * Logs which source was used and, on a post-cutover fallback, why.
     *
     * Post-cutover, nothing is handed out while the paid-address history is
     * unknown ([paidHistoryKnown]: after a recovery, until the SDK has
     * processed the wallet's transactions through the tip), and no candidate —
     * SDK pick or dashj fallback — that the SDK's transaction store shows paid
     * ([ContactPaymentChainView]).
     */
    suspend fun nextAddress(target: ContactPaymentTarget): ContactPaymentAddress? = withContext(ioDispatcher) {
        reapplyDashjMarks(target)
        reapplyFoldedFrontier(target)
        if (!cutoverCommitted()) {
            // dashj is the live engine and sees the chain itself; the SDK's
            // store is only consulted when it can be read.
            dashjAddress(target, "pre-cutover", chainRequired = false)
        } else if (!paidHistoryKnown(target)) {
            null
        } else {
            postCutoverAddress(target)
        }
    }

    /**
     * Whether every earlier contact payment of the current wallet is known:
     * recorded in [paidAddresses] since it covered the wallet, or — once every
     * DIP-15 contact account is registered and covered by the SDK's backfill
     * ([ContactPaymentChainView.contactHistoryUncoveredBecause]) and the SDK
     * has processed the wallet's transactions into its store through the
     * header tip it knows, and through the tip seen when the history was first
     * found unknown ([ContactChainScanState.processedThroughTip], no
     * tolerance) — in the SDK's transaction store, which is then recorded for
     * good ([PaidContactAddressStore.markPaidHistoryKnown]). False after a
     * recovery (no paid-address file, or a wallet change) until then: a
     * payment from before the recovery may not be known anywhere yet, and
     * dashj's restored chain — or the SDK's not yet flagged pool — would hand
     * its address out again. The balance-save gate is not enough (PR #1608
     * review, round 10): it accepts cursors two blocks short of the tip, and a
     * payment in those blocks is not in the store yet.
     */
    private suspend fun paidHistoryKnown(target: ContactPaymentTarget): Boolean {
        val walletId = chain.walletId()
        if (walletId == null) {
            log.warn(
                "contact payment address for {} (accountReference {}): no SDK wallet loaded, the paid " +
                    "addresses cannot be checked",
                target.contactUserId, target.accountReference
            )
            return false
        }
        if (paidAddresses.paidHistoryKnown(walletId)) return true
        // Before any evidence is read: a reset or re-creation that drops the
        // marker while this lookup holds evidence about the old transaction
        // store makes the promotion below a no-op (PR #1608 review, round 13).
        val generation = paidAddresses.paidHistoryGeneration()
        // The tip proves the history only of the scripts the scan watched
        // (PR #1608 review, round 11): every DIP-15 contact account must be
        // registered and covered by the SDK's backfill first. Read before
        // the scan state, so a rewind for an account registered in between
        // shows there as a lower height.
        val uncovered = chain.contactHistoryUncoveredBecause(walletId, target.ourUserId)
        val state = chain.scanState(walletId)
        // The tip seen when the history was first found unknown (in this
        // process): the store must be processed at least that far, even if
        // the SDK's header chain later reports a lower tip (a reset header
        // store re-syncing).
        val floor = historyUnknownAtTip.compute(walletId) { _, seen ->
            seen ?: state?.tipHeight?.takeIf { it > 0 }
        } ?: 0L
        if (uncovered == null && state != null && state.processedThroughTip(floor) && chain.walletId() == walletId) {
            if (paidAddresses.markPaidHistoryKnown(walletId, generation)) {
                log.info(
                    "the SDK has processed the wallet's transactions through the tip {} (first seen unknown at {}) " +
                        "with every contact account covered: its earlier contact payments are known from now on",
                    state.tipHeight, floor
                )
                historyUnknownAtTip.remove(walletId)
                return true
            }
            log.warn(
                "contact payment address for {} (accountReference {}): the SDK's transaction store was reset " +
                    "while its scan state was being read; the wallet's earlier contact payments are not known yet",
                target.contactUserId, target.accountReference
            )
            return false
        }
        log.warn(
            "contact payment address for {} (accountReference {}): the wallet's earlier contact payments are " +
                "not known yet (recovered wallet; the SDK has processed through {} of tip {}, must reach {}, " +
                "scan settled {}; contact accounts: {})",
            target.contactUserId, target.accountReference,
            state?.processedHeight ?: "unknown", state?.tipHeight ?: "unknown",
            maxOf(floor, state?.tipHeight ?: 0L), state?.scanSettled ?: "unknown", uncovered ?: "covered"
        )
        return false
    }

    /**
     * Whether the chain shows [address] paid: true or false, or null when
     * that cannot be checked and [chainRequired] (the candidate must not be
     * used). With [chainRequired] false an unreadable store reads as false.
     * Blocking.
     */
    private fun paidOnChain(address: Address, chainRequired: Boolean): Boolean? =
        when (chain.paysAddress(address)) {
            true -> true
            false -> false
            null -> if (chainRequired) null else false
        }

    private suspend fun postCutoverAddress(target: ContactPaymentTarget): ContactPaymentAddress? {
        var account: SdkContactSendingAccount? = null
        var cursor: Int? = null
        var frontier: Int? = null
        var unavailable = "SDK store not available"
        try {
            val ourId = Identifier.from(target.ourUserId).toBuffer()
            val contactId = Identifier.from(target.contactUserId).toBuffer()
            account = sdkAccountReader(ourId, contactId)
            cursor = try {
                dashjSendingCursor(target.contactUserId, target.accountReference)
            } catch (e: Exception) {
                log.warn("contact payment address: reading dashj's sending cursor failed", e)
                null
            }
            frontier = paidAddresses.paidFrontier(target.contactUserId, target.accountReference)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            account = null
            unavailable = "SDK read failed: $e"
        }
        // SDK candidates the chain shows paid (the mirror's used flag lags,
        // or the account was built after the payment was processed):
        // address → index. Recorded in [paidAddresses] before this lookup
        // returns, so the next one resumes above them ([recordPaidOnChain]).
        val paidOnChain = LinkedHashMap<String, Int>()
        var selection: SdkContactAddressSelection
        try {
            while (true) {
                selection = if (account == null) {
                    SdkContactAddressSelection.Unavailable(unavailable)
                } else {
                    selectSdkContactAddress(
                        account,
                        target.accountReference,
                        paidAddresses.paid() + paidOnChain.keys,
                        cursor,
                        maxOf(frontier ?: -1, paidOnChain.values.maxOrNull() ?: -1).takeIf { it >= 0 }
                    )
                }
                if (selection !is SdkContactAddressSelection.Selected) break
                val address = try {
                    Address.fromString(params, selection.address)
                } catch (e: Exception) {
                    log.warn("contact payment address: SDK address unparseable for this network", e)
                    selection = SdkContactAddressSelection.Unavailable("SDK address unparseable")
                    break
                }
                when (paidOnChain(address, chainRequired = true)) {
                    false -> {
                        log.info(
                            "contact payment address for {} (accountReference {}): source=SDK, index {}",
                            target.contactUserId, target.accountReference, selection.index
                        )
                        return ContactPaymentAddress(target, address, ContactAddressSource.SDK, selection.index)
                    }
                    true -> {
                        log.info(
                            "contact payment address for {} (accountReference {}): SDK index {} is paid on " +
                                "chain but not flagged used — skipped",
                            target.contactUserId, target.accountReference, selection.index
                        )
                        paidOnChain[address.toBase58()] = selection.index
                        if (paidOnChain.size > MAX_DASHJ_PAID_SKIPS) {
                            log.warn(
                                "contact payment address for {} (accountReference {}): the SDK pool's next {} " +
                                    "unflagged addresses are paid on chain — no address this time; they are " +
                                    "recorded, the next lookup continues above index {}",
                                target.contactUserId, target.accountReference, paidOnChain.size, selection.index
                            )
                            return null
                        }
                    }
                    null -> {
                        log.warn(
                            "contact payment address for {} (accountReference {}): the SDK's transaction store " +
                                "cannot be read to check index {} — no address",
                            target.contactUserId, target.accountReference, selection.index
                        )
                        return null
                    }
                }
            }
        } finally {
            recordPaidOnChain(target, paidOnChain)
        }
        // What the SDK mirror (durable) flags used on this channel, and what
        // the chain showed paid: the dashj fallback never serves those either
        // — a chain dashj built or reloaded without seeing those payments
        // starts below them.
        val sdkUsed = account
            ?.takeIf { it.externalAccountReference == target.accountReference }
            ?.addresses
            ?.filter { it.isUsed }
            ?.mapTo(HashSet()) { it.address }
            .orEmpty() + paidOnChain.keys
        val reason = (selection as? SdkContactAddressSelection.Unavailable)?.reason
        return dashjAddress(target, "post-cutover fallback ($reason)", chainRequired = true, sdkUsed = sdkUsed)
    }

    /**
     * Records the SDK pool addresses [paid] (address → index) that the chain
     * showed paid on [target]'s channel ([PaidContactAddressStore.recordPaidOnChain]):
     * the next lookup's SDK pick starts above them (the channel's paid
     * frontier) and dashj is advanced past them ([reapplyDashjMarks]) — so a
     * channel with more such payments than one lookup skips
     * ([MAX_DASHJ_PAID_SKIPS]) makes progress lookup by lookup. Blocking.
     * Never throws: without the record the next lookup finds them again.
     */
    private fun recordPaidOnChain(target: ContactPaymentTarget, paid: Map<String, Int>) {
        if (paid.isEmpty()) return
        try {
            paidAddresses.recordPaidOnChain(target, paid)
        } catch (e: Exception) {
            log.warn("contact payment address: recording the addresses paid on chain failed", e)
        }
    }

    /**
     * Re-applies the dashj mark of each address recorded paid to [target] that
     * dashj has not been advanced past in this process — the marks of a
     * process that died before dashj's wallet save. Idempotent on dashj
     * (an address already behind its current key changes nothing); the
     * outcome tells [paidAddresses] when the entry can settle. Blocking.
     */
    private fun reapplyDashjMarks(target: ContactPaymentTarget) {
        val awaiting = try {
            paidAddresses.awaitingDashj(target.contactUserId, target.accountReference)
        } catch (e: Exception) {
            log.warn("contact payment address: reading the paid addresses failed", e)
            return
        }
        awaiting.forEach { base58 ->
            val address = try {
                Address.fromString(params, base58)
            } catch (e: Exception) {
                log.warn("contact payment address: a recorded paid address is unparseable for this network", e)
                return@forEach
            }
            val outcome = try {
                dashjMarkUsed(target.contactUserId, target.accountReference, address)
            } catch (e: Exception) {
                log.warn("contact payment address: re-marking a paid address on dashj failed", e)
                return@forEach
            }
            paidAddresses.dashjMarked(base58, outcome)
            log.info(
                "contact payment address for {} (accountReference {}): re-applied a paid address's dashj " +
                    "mark: {}",
                target.contactUserId, target.accountReference, outcome ?: "no dashj identity"
            )
        }
    }

    /**
     * Advances dashj's sending chain for [target] past the channel's folded
     * frontier ([PaidContactAddressStore.foldedFrontier]) when its cursor is
     * at or below it — a wallet whose chain is behind what was paid (an older
     * key backup, a restore) after the records of those payments expired.
     * Marking the frontier address moves dashj past every earlier key. Runs on
     * every lookup and is cheap when dashj is already past it. Blocking.
     */
    private fun reapplyFoldedFrontier(target: ContactPaymentTarget) {
        try {
            val frontier = paidAddresses.foldedFrontier(target.contactUserId, target.accountReference) ?: return
            val cursor = dashjSendingCursor(target.contactUserId, target.accountReference) ?: return
            if (cursor > frontier.index) return
            val address = Address.fromString(params, frontier.address)
            val outcome = dashjMarkUsed(target.contactUserId, target.accountReference, address)
            log.info(
                "contact payment address for {} (accountReference {}): dashj's cursor {} is at or below the " +
                    "paid frontier {} — advanced past it: {}",
                target.contactUserId, target.accountReference, cursor, frontier.index, outcome ?: "no dashj identity"
            )
        } catch (e: Exception) {
            log.warn("contact payment address: re-applying the paid frontier on dashj failed", e)
        }
    }

    /**
     * The index of [served], the address dashj just served for [target]:
     * dashj's cursor, read between [served] and a second request that returns
     * the same address (so dashj's current key did not move in between). Null
     * when unknown. Blocking.
     */
    private fun dashjServedIndex(target: ContactPaymentTarget, served: Address): Int? = try {
        val cursor = dashjSendingCursor(target.contactUserId, target.accountReference)
        if (cursor != null && dashjNextAddress(target.contactUserId, target.accountReference) == served) {
            cursor
        } else {
            null
        }
    } catch (e: Exception) {
        log.warn("contact payment address: reading dashj's sending cursor failed", e)
        null
    }

    /**
     * dashj's current sending address for [target], never one recorded paid
     * or claimed ([paidAddresses]), never one in [sdkUsed] (flagged used by
     * the SDK mirror, or shown paid on chain) and never one the SDK's
     * transaction store shows paid ([ContactPaymentChainView.paysAddress] —
     * after a recovery dashj's chain starts at the restored cursor, below
     * every payment made since). When that store cannot be read: no address
     * if [chainRequired] (post-cutover), else the check is skipped (pre-cutover
     * dashj is the live engine).
     *
     * dashj can serve such an address: marking it used ([dashjMarkUsed]) only
     * schedules a wallet save, so a process that died before that save comes
     * back at the old current key; a chain dashj built after the payment (or a
     * restored wallet's) starts below it; and a send in flight has not marked
     * it yet. Such an address is detected here and dashj is advanced past it
     * (the same mark, which issues a fresh current key) and asked again — at
     * most [MAX_DASHJ_PAID_SKIPS] times per lookup (the marks stay, so the next
     * lookup continues from there), and never when the mark does not move
     * dashj on. If dashj cannot
     * be advanced to a usable address, null: the send screen reports no address
     * rather than paying a used one. Likewise null when the channel has a
     * folded frontier and the served address's index is at or below it (dashj
     * could not be advanced past it by [reapplyFoldedFrontier]) or unknown
     * ([dashjServedIndex] null: the frontier keeps only its highest address,
     * so an earlier paid one would pass [paidAddresses]). Blocking (dashj lock,
     * SDK store); runs on [ioDispatcher].
     */
    private suspend fun dashjAddress(
        target: ContactPaymentTarget,
        why: String,
        chainRequired: Boolean,
        sdkUsed: Set<String> = emptySet()
    ): ContactPaymentAddress? {
        var address = dashjNextAddress(target.contactUserId, target.accountReference)
        if (address == null) {
            log.warn(
                "contact payment address for {} (accountReference {}): dashj has none — {}",
                target.contactUserId, target.accountReference, why
            )
            return null
        }
        val excluded = paidAddresses.paid() + sdkUsed
        var skipped = 0
        while (address != null) {
            val paid = address.toBase58() in excluded || when (paidOnChain(address, chainRequired)) {
                true -> true
                false -> false
                null -> {
                    log.warn(
                        "contact payment address for {} (accountReference {}): the SDK's transaction store " +
                            "cannot be read to check dashj's address — no address",
                        target.contactUserId, target.accountReference
                    )
                    return null
                }
            }
            if (!paid) break
            if (skipped >= MAX_DASHJ_PAID_SKIPS) {
                log.warn(
                    "contact payment address for {} (accountReference {}): dashj still serves a paid " +
                        "address after advancing {} time(s) — no address",
                    target.contactUserId, target.accountReference, skipped
                )
                return null
            }
            val outcome = try {
                dashjMarkUsed(target.contactUserId, target.accountReference, address)
            } catch (e: Exception) {
                log.warn(
                    "contact payment address for {}: dashj served a paid address and advancing past it " +
                        "failed — no address",
                    target.contactUserId, e
                )
                return null
            }
            if (outcome != FriendChainAccess.MarkResult.MARKED) {
                log.warn(
                    "contact payment address for {} (accountReference {}): dashj served a paid address " +
                        "and could not advance past it ({}) — no address",
                    target.contactUserId, target.accountReference, outcome ?: "no dashj identity"
                )
                return null
            }
            paidAddresses.dashjMarked(address.toBase58(), outcome)
            skipped++
            val previous = address
            address = dashjNextAddress(target.contactUserId, target.accountReference)
            if (address == previous) {
                log.warn(
                    "contact payment address for {} (accountReference {}): dashj still serves a paid " +
                        "address after marking it used — no address",
                    target.contactUserId, target.accountReference
                )
                return null
            }
        }
        if (address == null) {
            log.warn(
                "contact payment address for {} (accountReference {}): dashj has none after advancing " +
                    "past {} paid address(es) — {}",
                target.contactUserId, target.accountReference, skipped, why
            )
            return null
        }
        val index = dashjServedIndex(target, address)
        val folded = paidAddresses.foldedFrontier(target.contactUserId, target.accountReference)
        // With a folded frontier, an address whose index is unknown cannot be
        // shown to lie above it: the frontier keeps only its highest address,
        // so an earlier paid one is no longer in [excluded].
        if (folded != null && (index == null || index <= folded.index)) {
            log.warn(
                "contact payment address for {} (accountReference {}): dashj serves index {}, not provably " +
                    "above the paid frontier {} it could not be advanced past — no address",
                target.contactUserId, target.accountReference, index ?: "unknown", folded.index
            )
            return null
        }
        if (skipped > 0) {
            log.info(
                "contact payment address for {} (accountReference {}): dashj served {} address(es) " +
                    "already paid (wallet not saved before a restart, chain built later, or a send in " +
                    "flight) — advanced past them",
                target.contactUserId, target.accountReference, skipped
            )
        }
        log.info(
            "contact payment address for {} (accountReference {}): source=DASHJ — {}",
            target.contactUserId, target.accountReference, why
        )
        return ContactPaymentAddress(target, address, ContactAddressSource.DASHJ, index)
    }

    /**
     * Sends a payment to [payment]'s address with [send], never to an address
     * another send uses or used:
     * 1. CLAIMS the address before [send] runs — recorded in [paidAddresses]
     *    and written to disk, atomically with the check that it is neither
     *    paid nor claimed. An address that is refuses the payment with
     *    [ContactAddressAlreadyPaidException] (nothing is sent; the caller
     *    resolves the contact's address again): a retained payment resubmitted
     *    after its broadcast, or a second send screen paying the same contact
     *    while the first send runs. A claim survives a process death mid-send.
     *    A claim that cannot be made durable — the store could not be read, or
     *    the claim could not be written — refuses the payment with
     *    [ContactPaymentNotRecordableException]: [send] never runs.
     * 2. Marks the key used on dashj's sending chain as soon as the payment may
     *    have gone out — when [send] signals [ContactSendSignals.broadcast]
     *    (before any later step that can still throw, e.g. post-cutover the
     *    display bridge; txid null = outcome unknown, SDK `Ambiguous`), or when
     *    [send] returns.
     * 3. If [send] throws without that signal: gives the address back only when
     *    it signalled [ContactSendSignals.notBroadcast] (a proven pre-broadcast
     *    failure). Any other failure — a cancellation included — has an unknown
     *    outcome: the address stays claimed and is marked used. That costs at
     *    most one skipped address; giving it back risks paying it twice.
     * Marking happens at most once and never throws: a marking failure is
     * logged, the money may already have moved. [payment] null (not a contact
     * payment) just runs [send].
     *
     * Before the claim the chain is checked again (PR #1608 review, round 10:
     * [payment] may have been resolved before the SDK's store held an earlier
     * payment to it): post-cutover the wallet's paid history must be known
     * ([paidHistoryKnown]), and under [claimGate], right before the claim, the
     * SDK's transaction store must show the address unpaid
     * ([ContactPaymentChainView.paysAddress]; post-cutover also readable).
     * Otherwise [ContactAddressAlreadyPaidException]: nothing is claimed or
     * sent, and resolving the contact's address again skips it.
     *
     * [claimGate] runs the claim (blocking, on [ioDispatcher]): the caller
     * checks there, under the lock its own invalidation takes, that [payment]
     * is still the one to send, and runs the claim only then — or throws, and
     * nothing is claimed or sent (PR #1608 review, round 9: a send that read
     * the payee before a re-resolution started went on to pay it). It must run
     * the claim exactly once or throw.
     */
    suspend fun <T> sendThenMarkUsed(
        payment: ContactPaymentAddress?,
        claimGate: (claim: () -> Unit) -> Unit = { claim -> claim() },
        send: suspend (signals: ContactSendSignals) -> T
    ): T {
        if (payment == null) {
            return send(ContactSendSignals { })
        }
        val base58 = payment.address.toBase58()
        val claim = withContext(ioDispatcher) {
            // Post-cutover the chain must be able to vouch for the address:
            // the wallet's paid history known (it may have been resolved
            // before the SDK had processed the chain, or before a wallet
            // change), and — under the gate, right before the claim — no
            // transaction in the SDK's store paying it.
            val chainRequired = cutoverCommitted()
            val historyWallet = if (chainRequired) {
                chain.walletId()?.takeIf { paidHistoryKnown(payment.target) }
            } else {
                null
            }
            if (chainRequired && historyWallet == null) {
                log.warn(
                    "contact payment to {} (accountReference {}, address source {}) refused: the wallet's " +
                        "earlier contact payments are not known yet — nothing sent",
                    payment.target.contactUserId, payment.target.accountReference, payment.source
                )
                throw ContactAddressAlreadyPaidException(payment)
            }
            var result: PaidContactAddressStore.ClaimResult? = null
            claimGate {
                // An unreadable store refuses the claim itself (STORE_UNAVAILABLE),
                // and its paid history never reads known.
                result = if (paidAddresses.unreadable() || unpaidOnChain(payment, chainRequired, historyWallet)) {
                    paidAddresses.claim(base58, payment.target, payment.index)
                } else {
                    PaidContactAddressStore.ClaimResult.ALREADY_PAID
                }
            }
            checkNotNull(result) { "the claim gate returned without running the claim" }
        }
        when (claim) {
            PaidContactAddressStore.ClaimResult.CLAIMED -> Unit
            PaidContactAddressStore.ClaimResult.ALREADY_PAID -> {
                log.warn(
                    "contact payment to {} (accountReference {}, address source {}) refused: the address was " +
                        "already paid, another send is paying it, or it cannot be shown unpaid",
                    payment.target.contactUserId, payment.target.accountReference, payment.source
                )
                throw ContactAddressAlreadyPaidException(payment)
            }
            PaidContactAddressStore.ClaimResult.STORE_UNAVAILABLE -> {
                log.warn(
                    "contact payment to {} (accountReference {}, address source {}) refused: its address could " +
                        "not be recorded durably — nothing sent",
                    payment.target.contactUserId, payment.target.accountReference, payment.source
                )
                throw ContactPaymentNotRecordableException(payment)
            }
        }
        val marked = AtomicBoolean(false)
        val signals = ContactSendSignals { txid ->
            if (marked.compareAndSet(false, true)) {
                if (txid == null) {
                    log.info(
                        "contact payment to {} (address source {}): send outcome unknown, it may have " +
                            "been broadcast — marking the address used so it is not reused",
                        payment.target.contactUserId, payment.source
                    )
                }
                markPaid(payment, txid)
            }
        }
        val result = try {
            send(signals)
        } catch (t: Throwable) {
            // NonCancellable: whatever the failure, the claim must be settled.
            withContext(NonCancellable + ioDispatcher) { afterFailedSend(payment, signals, marked, t) }
            throw t
        }
        if (marked.compareAndSet(false, true)) {
            // NonCancellable: the payment went out; a cancellation arriving now
            // must not skip recording it.
            withContext(NonCancellable + ioDispatcher) { markPaid(payment, txid = null) }
        }
        return result
    }

    /**
     * The claim-time chain check of [payment]'s address (blocking; runs under
     * the claim gate): false when the SDK's transaction store shows it paid —
     * e.g. a recipient resolved before the store held a payment made before a
     * recovery. Post-cutover ([chainRequired]) also false when the store cannot
     * be read, or when [historyWallet] (the wallet whose paid history was
     * found known before the gate) is no longer the current one with a known
     * history (a wallet change meanwhile). Pre-cutover an unreadable store is
     * no obstacle (dashj is the live engine).
     */
    private fun unpaidOnChain(
        payment: ContactPaymentAddress,
        chainRequired: Boolean,
        historyWallet: String?
    ): Boolean {
        if (chainRequired) {
            val walletId = chain.walletId()
            if (walletId == null || walletId != historyWallet || !paidAddresses.paidHistoryKnown(walletId)) {
                log.warn(
                    "contact payment to {}: the SDK wallet or its paid history changed before the claim",
                    payment.target.contactUserId
                )
                return false
            }
        }
        return when (paidOnChain(payment.address, chainRequired)) {
            false -> true
            true -> {
                log.warn(
                    "contact payment to {} (accountReference {}, address source {}): the chain shows its " +
                        "address paid — refused",
                    payment.target.contactUserId, payment.target.accountReference, payment.source
                )
                false
            }
            null -> {
                log.warn(
                    "contact payment to {}: the SDK's transaction store cannot be read to check its address — " +
                        "refused",
                    payment.target.contactUserId
                )
                false
            }
        }
    }

    /**
     * Whether [payment]'s address is recorded paid or claimed by a send — the
     * address must not be submitted again. After a send threw: false only when
     * it failed provably before broadcasting (and no other send claimed it
     * since). Blocking (the first call reads the file). Never throws.
     */
    fun isClaimedOrPaid(payment: ContactPaymentAddress): Boolean = try {
        paidAddresses.contains(payment.address.toBase58())
    } catch (e: Exception) {
        log.warn("contact payment address: reading the paid addresses failed", e)
        true
    }

    /** [send] threw [failure]: settles the claim. Blocking. Never throws. */
    private fun afterFailedSend(
        payment: ContactPaymentAddress,
        signals: ContactSendSignals,
        marked: AtomicBoolean,
        failure: Throwable
    ) {
        if (marked.get()) {
            return // signalled broadcast (or an unknown outcome): already marked paid
        }
        if (signals.provenNotBroadcast) {
            try {
                paidAddresses.release(payment.address.toBase58())
            } catch (e: Exception) {
                log.warn("contact payment not sent; releasing its address failed — it stays skipped", e)
            }
            log.info(
                "contact payment to {} (address source {}) failed before broadcast — the address is free again",
                payment.target.contactUserId, payment.source
            )
            return
        }
        if (marked.compareAndSet(false, true)) {
            log.warn(
                "contact payment to {} (address source {}) failed with an unknown outcome ({}) — it may have " +
                    "been broadcast; marking the address used so it is not reused",
                payment.target.contactUserId, payment.source, failure.javaClass.simpleName
            )
            markPaid(payment, txid = null)
        }
    }

    /** Blocking (file write, dashj lock). Never throws. */
    private fun markPaid(payment: ContactPaymentAddress, txid: String?) {
        val base58 = payment.address.toBase58()
        try {
            paidAddresses.sent(base58)
        } catch (e: Exception) {
            log.warn("contact payment sent; recording the paid address failed", e)
        }
        val outcome = try {
            dashjMarkUsed(payment.target.contactUserId, payment.target.accountReference, payment.address)
        } catch (e: Exception) {
            log.warn("contact payment sent; marking the dashj sending key used failed", e)
            return
        }
        try {
            paidAddresses.dashjMarked(base58, outcome)
        } catch (e: Exception) {
            log.warn("contact payment sent; noting the dashj mark failed", e)
        }
        log.info(
            "contact payment to {} sent (tx {}, address source {}); dashj sending key: {}",
            payment.target.contactUserId, txid ?: "-", payment.source, outcome ?: "no dashj identity"
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ContactPaymentAddressProvider::class.java)

        /**
         * Bound on how many paid addresses one lookup skips: dashj advanced past
         * ([dashjAddress]), or SDK pool addresses the chain shows paid. Each is
         * a payment whose record was lost (a dashj save, a recovery); the bound
         * keeps one lookup short. What a lookup verified stays — dashj's
         * advances, and the SDK addresses shown paid are recorded
         * ([PaidContactAddressStore.recordPaidOnChain]) — so the next lookup
         * continues from there and a channel with many lost records reaches its
         * unused address after a few lookups.
         */
        internal const val MAX_DASHJ_PAID_SKIPS = 10
    }
}

/**
 * Reads [SdkContactSendingAccount] for (ourIdentityId, contactIdentityId) from
 * the SDK's Room mirror. Null when the SDK store is not started or the bound
 * wallet cannot be picked (none, or stale leftovers — see
 * [DashSdkService.loadedWalletIds]).
 *
 * The sending account is the wallet's `dashpayExternalAccount` row (account
 * index 0, the only one the SDK builds) keyed by our id and the contact's; its
 * addresses are its `core_addresses` rows — the same rows
 * [SdkTxContactResolver] reads to recognise payments to contacts.
 */
internal suspend fun readSdkContactSendingAccount(
    db: DashDatabase?,
    loadedWalletIds: Set<String>,
    ourIdentityId: ByteArray,
    contactIdentityId: ByteArray
): SdkContactSendingAccount? {
    db ?: return null
    val walletId = loadedWalletIds.singleOrNull()?.let(::walletIdFromHex) ?: return null
    val incoming = db.dashpayDao().getContactRequestsByOwner(ourIdentityId)
        .filter { !it.isOutgoing && it.contactIdentityId.contentEquals(contactIdentityId) }
        .maxByOrNull { it.createdAtMillis }
    val accounts = db.accountDao()
        .observeByWalletAndType(walletId, SdkTxContactResolver.ACCOUNT_TYPE_DASHPAY_EXTERNAL)
        .first()
        .filter {
            it.accountIndex == 0 &&
                it.userIdentityId.contentEquals(ourIdentityId) &&
                it.friendIdentityId.contentEquals(contactIdentityId)
        }
    val account = accounts.singleOrNull()
    val addresses = account?.let { acc ->
        db.coreAddressDao().observeByAccount(acc.id).first().map {
            SdkContactPoolAddress(
                address = it.address,
                index = it.addressIndex,
                isUsed = it.isUsed,
                poolTypeTag = it.poolTypeTag
            )
        }
    }
    return SdkContactSendingAccount(
        incomingRequestFound = incoming != null,
        paymentChannelBroken = incoming?.paymentChannelBroken ?: false,
        externalAccountReference = incoming?.externalAccountReference,
        addresses = addresses
    )
}
