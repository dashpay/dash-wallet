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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.service.platform.IdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.wallet.FriendChainAccess
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
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
 * SDK's `send_payment` uses) — skipping [alreadyPaid], the addresses this
 * process has paid but the mirror may not have flagged yet (the engine writes
 * used-flags behind itself, one persistence pass later).
 */
internal fun selectSdkContactAddress(
    account: SdkContactSendingAccount?,
    expectedAccountReference: Int,
    alreadyPaid: Set<String>
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
    val next = addresses
        .filter { !it.isUsed && it.address !in alreadyPaid }
        .minByOrNull { it.index }
        ?: return unavailable("SDK sending account has no unused address persisted")
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
 * Either way, after a successful send ([sendThenMarkUsed]) the paid key is
 * marked used on dashj's sending chain, so the dashj fallback moves on too.
 *
 * Why the Room mirror and not a live engine read: the Kotlin SDK exposes no
 * call that returns a contact sending account's next address
 * (`platform_wallet_account_address_pools` exists in the Rust FFI but has no
 * JNI binding; `Dashpay.sendPayment` derives one only inside its own send).
 * The mirror trails the engine by one persistence pass, which is covered by
 * remembering what this process has paid ([paidThisProcess]).
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
    private val params: NetworkParameters
) {
    @Inject
    constructor(
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
        params = Constants.NETWORK_PARAMETERS
    )

    /**
     * Base58 addresses paid by this process. The SDK mirror flips an address to
     * used one persistence pass after the engine sees the payment; without this,
     * a second payment inside that window would be handed the same address.
     */
    private val paidThisProcess: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The address to pay [target] on, or null when neither stack can supply one.
     * Logs which source was used and, on a post-cutover fallback, why.
     */
    suspend fun nextAddress(target: ContactPaymentTarget): ContactPaymentAddress? {
        if (!cutoverCommitted()) {
            return dashjAddress(target, "pre-cutover")
        }
        val selection = try {
            val ourId = Identifier.from(target.ourUserId).toBuffer()
            val contactId = Identifier.from(target.contactUserId).toBuffer()
            selectSdkContactAddress(
                sdkAccountReader(ourId, contactId),
                target.accountReference,
                paidThisProcess.toSet()
            )
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
        if (address.toBase58() in paidThisProcess) {
            // Not fatal: the payment still reaches the contact. It means the
            // post-send mark below could not advance dashj's chain.
            log.warn(
                "contact payment address for {}: dashj served an address this process already paid",
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
     * Runs [send] and, only once it has returned (the payment was sent), records
     * [payment]'s address as paid: in this process and on dashj's sending chain.
     * A failed or aborted send throws out of [send] before anything is marked.
     * A marking failure is logged, never thrown — the money has already moved.
     * [payment] null (not a contact payment) just runs [send].
     */
    suspend fun <T> sendThenMarkUsed(payment: ContactPaymentAddress?, send: suspend () -> T): T {
        val result = send()
        if (payment != null) {
            markPaid(payment)
        }
        return result
    }

    private fun markPaid(payment: ContactPaymentAddress) {
        paidThisProcess.add(payment.address.toBase58())
        val outcome = try {
            dashjMarkUsed(payment.target.contactUserId, payment.target.accountReference, payment.address)
        } catch (e: Exception) {
            log.warn("contact payment sent; marking the dashj sending key used failed", e)
            return
        }
        log.info(
            "contact payment to {} sent (address source {}); dashj sending key: {}",
            payment.target.contactUserId, payment.source, outcome ?: "no dashj identity"
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
