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
import java.io.File
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

/** A payment address for [target] and the stack that supplied it. */
data class ContactPaymentAddress(
    val target: ContactPaymentTarget,
    val address: Address,
    val source: ContactAddressSource
)

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
 *   which covers the time until dashj's advanced cursor reaches disk.
 */
internal fun selectSdkContactAddress(
    account: SdkContactSendingAccount?,
    expectedAccountReference: Int,
    alreadyPaid: Set<String>,
    dashjCursor: Int? = null
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
    val floor = dashjCursor ?: 0
    val next = addresses
        .filter { !it.isUsed && it.address !in alreadyPaid && it.index >= floor }
        .minByOrNull { it.index }
        ?: return unavailable(
            if (dashjCursor != null) {
                "SDK sending account has no unused address persisted at or above dashj's cursor $dashjCursor"
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
 * - [paidAddresses], a small file written at broadcast time. dashj's cursor
 *   reaches disk only on the next wallet autosave (up to a minute on a large
 *   wallet), and dashj cannot always advance it (no chain, key not found);
 *   the file covers both.
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
        paidAddresses = PaidContactAddressStore(File(context.filesDir, PaidContactAddressStore.FILE_NAME))
    )

    /**
     * The address to pay [target] on, or null when neither stack can supply one.
     * Logs which source was used and, on a post-cutover fallback, why.
     */
    suspend fun nextAddress(target: ContactPaymentTarget): ContactPaymentAddress? = withContext(ioDispatcher) {
        if (!cutoverCommitted()) {
            dashjAddress(target, "pre-cutover")
        } else {
            postCutoverAddress(target)
        }
    }

    private suspend fun postCutoverAddress(target: ContactPaymentTarget): ContactPaymentAddress? {
        val selection = try {
            val ourId = Identifier.from(target.ourUserId).toBuffer()
            val contactId = Identifier.from(target.contactUserId).toBuffer()
            val account = sdkAccountReader(ourId, contactId)
            val cursor = try {
                dashjSendingCursor(target.contactUserId, target.accountReference)
            } catch (e: Exception) {
                log.warn("contact payment address: reading dashj's sending cursor failed", e)
                null
            }
            selectSdkContactAddress(account, target.accountReference, paidAddresses.paid(), cursor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SdkContactAddressSelection.Unavailable("SDK read failed: $e")
        }
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
                    return ContactPaymentAddress(target, address, ContactAddressSource.SDK)
                }
                return dashjAddress(target, "post-cutover fallback (SDK address unparseable)")
            }
            is SdkContactAddressSelection.Unavailable ->
                return dashjAddress(target, "post-cutover fallback (${selection.reason})")
        }
    }

    private fun dashjAddress(target: ContactPaymentTarget, why: String): ContactPaymentAddress? {
        val address = dashjNextAddress(target.contactUserId, target.accountReference)
        if (address == null) {
            log.warn(
                "contact payment address for {} (accountReference {}): dashj has none — {}",
                target.contactUserId, target.accountReference, why
            )
            return null
        }
        if (address.toBase58() in paidAddresses.paid()) {
            // Not fatal: the payment still reaches the contact. It means the
            // post-send mark below could not advance dashj's chain.
            log.warn(
                "contact payment address for {}: dashj served an address already recorded paid",
                target.contactUserId
            )
        }
        log.info(
            "contact payment address for {} (accountReference {}): source=DASHJ — {}",
            target.contactUserId, target.accountReference, why
        )
        return ContactPaymentAddress(target, address, ContactAddressSource.DASHJ)
    }

    /**
     * Runs [send] and records [payment]'s address as paid ([paidAddresses] and
     * dashj's sending chain) as soon as the payment may have gone out:
     * - when [send] calls the `onBroadcast` it is handed — the send path signals
     *   it the moment the network has the transaction, before any later step
     *   that can still throw (post-cutover: bridging the transaction into the
     *   dashj wallet for display, which may fail although the money moved);
     * - when [send] calls `onBroadcast(null)` — the send's outcome is unknown
     *   (post-cutover SDK `Ambiguous`) and it is about to throw. Marking then
     *   costs at most one skipped address; not marking risks reusing an address
     *   that was in fact paid;
     * - otherwise when [send] returns.
     * A send that throws or is cancelled before signalling marks nothing — that
     * is a genuine pre-broadcast failure. Marking happens at most once and never
     * throws: a marking failure is logged, the money may already have moved.
     * [payment] null (not a contact payment) just runs [send].
     */
    suspend fun <T> sendThenMarkUsed(
        payment: ContactPaymentAddress?,
        send: suspend (onBroadcast: (txid: String?) -> Unit) -> T
    ): T {
        if (payment == null) {
            return send { }
        }
        val marked = AtomicBoolean(false)
        val result = send { txid ->
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
        if (marked.compareAndSet(false, true)) {
            // NonCancellable: the payment went out; a cancellation arriving now
            // must not skip recording it.
            withContext(NonCancellable + ioDispatcher) { markPaid(payment, txid = null) }
        }
        return result
    }

    /** Blocking (file write, dashj lock). Never throws. */
    private fun markPaid(payment: ContactPaymentAddress, txid: String?) {
        try {
            paidAddresses.record(payment.address.toBase58())
        } catch (e: Exception) {
            log.warn("contact payment sent; recording the paid address failed", e)
        }
        val outcome = try {
            dashjMarkUsed(payment.target.contactUserId, payment.target.accountReference, payment.address)
        } catch (e: Exception) {
            log.warn("contact payment sent; marking the dashj sending key used failed", e)
            return
        }
        log.info(
            "contact payment to {} sent (tx {}, address source {}); dashj sending key: {}",
            payment.target.contactUserId, txid ?: "-", payment.source, outcome ?: "no dashj identity"
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ContactPaymentAddressProvider::class.java)
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
