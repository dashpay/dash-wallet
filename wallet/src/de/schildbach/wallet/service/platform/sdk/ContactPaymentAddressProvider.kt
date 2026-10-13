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
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.wallet.FriendChainAccess
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
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
 * its address is already recorded paid, or another send is paying it. Nothing
 * was sent; resolve the contact's address again
 * ([ContactPaymentAddressProvider.nextAddress]) for a new one.
 */
class ContactAddressAlreadyPaidException(val payment: ContactPaymentAddress) :
    IllegalStateException(
        "contact payment address for ${payment.target.contactUserId} was already paid or is being paid; " +
            "nothing was sent — resolve the contact's payment address again"
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        sdkL1SendService: SdkL1SendService,
        sdkService: DashSdkService,
        identityRepository: IdentityRepository
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
        paidAddresses = PaidContactAddressStore.app(context.filesDir)
    )

    /**
     * The address to pay [target] on, or null when neither stack can supply one.
     * Logs which source was used and, on a post-cutover fallback, why.
     */
    suspend fun nextAddress(target: ContactPaymentTarget): ContactPaymentAddress? = withContext(ioDispatcher) {
        reapplyDashjMarks(target)
        reapplyFoldedFrontier(target)
        if (!cutoverCommitted()) {
            dashjAddress(target, "pre-cutover")
        } else {
            postCutoverAddress(target)
        }
    }

    private suspend fun postCutoverAddress(target: ContactPaymentTarget): ContactPaymentAddress? {
        var account: SdkContactSendingAccount? = null
        val selection = try {
            val ourId = Identifier.from(target.ourUserId).toBuffer()
            val contactId = Identifier.from(target.contactUserId).toBuffer()
            account = sdkAccountReader(ourId, contactId)
            val cursor = try {
                dashjSendingCursor(target.contactUserId, target.accountReference)
            } catch (e: Exception) {
                log.warn("contact payment address: reading dashj's sending cursor failed", e)
                null
            }
            selectSdkContactAddress(
                account,
                target.accountReference,
                paidAddresses.paid(),
                cursor,
                paidAddresses.paidFrontier(target.contactUserId, target.accountReference)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SdkContactAddressSelection.Unavailable("SDK read failed: $e")
        }
        // What the SDK mirror (durable) flags used on this channel: the dashj
        // fallback never serves those either — a chain dashj built or reloaded
        // without seeing those payments starts below them.
        val sdkUsed = account
            ?.takeIf { it.externalAccountReference == target.accountReference }
            ?.addresses
            ?.filter { it.isUsed }
            ?.mapTo(HashSet()) { it.address }
            .orEmpty()
        when (selection) {
            is SdkContactAddressSelection.Selected -> {
                val address = try {
                    Address.fromString(params, selection.address)
                } catch (e: Exception) {
                    log.warn("contact payment address: SDK address unparseable for this network", e)
                    null
                }
                if (address != null) {
                    log.info(
                        "contact payment address for {} (accountReference {}): source=SDK, index {}",
                        target.contactUserId, target.accountReference, selection.index
                    )
                    return ContactPaymentAddress(target, address, ContactAddressSource.SDK, selection.index)
                }
                return dashjAddress(target, "post-cutover fallback (SDK address unparseable)", sdkUsed)
            }
            is SdkContactAddressSelection.Unavailable ->
                return dashjAddress(target, "post-cutover fallback (${selection.reason})", sdkUsed)
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
     * or claimed ([paidAddresses]) and never one in [sdkUsed] (flagged used by
     * the SDK mirror).
     *
     * dashj can serve such an address: marking it used ([dashjMarkUsed]) only
     * schedules a wallet save, so a process that died before that save comes
     * back at the old current key; a chain dashj built after the payment (or a
     * restored wallet's) starts below it; and a send in flight has not marked
     * it yet. Such an address is detected here and dashj is advanced past it
     * (the same mark, which issues a fresh current key) and asked again — at
     * most [MAX_DASHJ_PAID_SKIPS] times, never more often than there are such
     * addresses, and never when the mark does not move dashj on. If dashj cannot
     * be advanced to a usable address, null: the send screen reports no address
     * rather than paying a used one. Likewise null when the channel has a
     * folded frontier and the served address's index is at or below it (dashj
     * could not be advanced past it by [reapplyFoldedFrontier]) or unknown
     * ([dashjServedIndex] null: the frontier keeps only its highest address,
     * so an earlier paid one would pass [paidAddresses]). Blocking (dashj lock);
     * runs on [ioDispatcher].
     */
    private fun dashjAddress(
        target: ContactPaymentTarget,
        why: String,
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
        val maxSkips = minOf(MAX_DASHJ_PAID_SKIPS, excluded.size)
        var skipped = 0
        while (address != null && address.toBase58() in excluded) {
            if (skipped >= maxSkips) {
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
     */
    suspend fun <T> sendThenMarkUsed(
        payment: ContactPaymentAddress?,
        send: suspend (signals: ContactSendSignals) -> T
    ): T {
        if (payment == null) {
            return send(ContactSendSignals { })
        }
        val base58 = payment.address.toBase58()
        val claim = withContext(ioDispatcher) { paidAddresses.claim(base58, payment.target, payment.index) }
        when (claim) {
            PaidContactAddressStore.ClaimResult.CLAIMED -> Unit
            PaidContactAddressStore.ClaimResult.ALREADY_PAID -> {
                log.warn(
                    "contact payment to {} (accountReference {}, address source {}) refused: the address was " +
                        "already paid or another send is paying it",
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
         * Bound on how many paid addresses [dashjAddress] advances dashj past in
         * one request. Each is a payment whose dashj save was lost; more than a
         * handful means something else is wrong, and no address is safer.
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
