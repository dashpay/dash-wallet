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

package de.schildbach.wallet.service.platform

import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import kotlinx.coroutines.CancellationException
import org.bitcoinj.core.Address
import org.bitcoinj.core.Context
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.HDUtils
import org.bitcoinj.evolution.EvolutionContact
import org.bitcoinj.wallet.FriendKeyChain
import org.bitcoinj.wallet.Wallet
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One contact's DIP-15 friendship-derivation evidence for the
 * `ContactDerivationFacts` log line ([contactDerivationFactsLine]).
 *
 * ## Why this exists
 *
 * A field wallet is missing an INCOMING contact payment from a counterparty
 * whose username was created by a third-party client (closed source). The open
 * question is whether OUR DIP-15 derivation produces the same receiving address
 * that client actually paid to. One log pull has to answer it, which means the
 * line must carry every input to the derivation AND the addresses it produces.
 *
 * ## Which direction is logged, and why
 *
 * The verified DIP-15 contract: a contact request `X→Y` carries **X's**
 * incoming-funds xpub for the X/Y chain, and **Y decrypts it to pay X**. So the
 * addresses a contact pays US at come from the chain rooted in the request WE
 * authored to THEM — dashj's `RECEIVING_CHAIN` for
 * `EvolutionContact(ourId, contactId)`, the one whose keys we hold. Those are
 * the [receivingAddresses] here.
 *
 * Both accountReferences are carried because they are not interchangeable and
 * differing values per direction is EXPECTED (a different (key, xpub) pair each
 * way): [accountRefToContact] rides the request we authored — the one the
 * contact's client reads to pay us, and therefore the one that matters for a
 * missing incoming payment — while [accountRefFromContact] rides theirs and
 * drives our sending chain.
 *
 * Every field is nullable: null = that surface could not be read and prints as
 * the literal `unavailable`, never a guess.
 *
 * @property contactIdentityId the contact's identity id (base58).
 * @property username the contact's DPNS username when the profile is cached.
 * @property accountRefToContact `accountReference` on the newest contact
 *   request WE authored to this contact.
 * @property accountRefFromContact `accountReference` on the newest contact
 *   request THIS CONTACT authored to us.
 * @property derivationPath the dashj `RECEIVING_CHAIN` contact path
 *   (`FriendKeyChain.getContactPath`), formatted.
 * @property receivingAddresses the first few addresses derived from our
 *   receiving-chain xpub for this contact, index 0 upward. Capped
 *   ([MAX_LOGGED_ADDRESSES]) so the line stays readable. Empty list = the
 *   chain exists but nothing could be derived; null = no receiving keychain.
 */
internal data class ContactDerivationFacts(
    val contactIdentityId: String,
    val username: String?,
    val accountRefToContact: Int?,
    val accountRefFromContact: Int?,
    val derivationPath: String?,
    val receivingAddresses: List<String>?
)

/** Keeps the per-contact line readable; index 0 upward is what a payer uses first. */
internal const val MAX_LOGGED_ADDRESSES = 5

/**
 * The one-line per-contact summary — greppable on the `ContactDerivationFacts:`
 * tag; unreadable surfaces print the literal `unavailable`. Pure —
 * host-testable.
 */
internal fun contactDerivationFactsLine(f: ContactDerivationFacts): String {
    val addresses = f.receivingAddresses
        ?.joinToString(",")
        ?.let { "[$it]" }
        ?: "unavailable"
    return "ContactDerivationFacts: contact=${f.contactIdentityId} " +
        "username=${f.username ?: "unavailable"} " +
        "accountRefToContact=${f.accountRefToContact ?: "unavailable"} " +
        "accountRefFromContact=${f.accountRefFromContact ?: "unavailable"} " +
        "path=${f.derivationPath ?: "unavailable"} " +
        "receiving=$addresses"
}

/**
 * Emits the `ContactDerivationFacts` block ONCE per process, as soon as the
 * contact set and its DIP-15 friendship keychains are established (called at
 * the end of a contact-request sync pass — see
 * `PlatformSyncServiceImpl.updateContactRequests`).
 *
 * Read-only by construction: addresses are derived from the chain's already
 * published extended PUBLIC key rather than issued through the wallet, so the
 * diagnostic cannot advance any issuance counter, touch the bloom filter, or
 * otherwise change wallet state.
 *
 * Failure-contained: any failure produces exactly one `warn` and nothing else.
 * A per-contact failure degrades that contact's fields to `unavailable` rather
 * than dropping the block. Coroutine cancellation is the one thing re-thrown,
 * so this never masks a shutdown.
 */
internal object ContactDerivationFactsLogger {
    private val log = LoggerFactory.getLogger(ContactDerivationFactsLogger::class.java)
    private val logged = AtomicBoolean(false)

    suspend fun logOnce(
        ourIdentityId: String,
        wallet: Wallet,
        contactRequestDao: DashPayContactRequestDao,
        profileDao: DashPayProfileDao
    ) {
        if (!logged.compareAndSet(false, true)) return
        try {
            Context.propagate(wallet.context)
            val requests = contactRequestDao.loadAll()
            val toContact = requests.filter { it.userId == ourIdentityId }.groupBy { it.toUserId }
            val fromContact = requests.filter { it.toUserId == ourIdentityId }.groupBy { it.userId }
            val contactIds = (toContact.keys + fromContact.keys).toSortedSet()

            log.info(
                "ContactDerivationFacts: BEGIN identity={} contacts={} (receiving chain = the addresses a " +
                    "contact pays US at; derived read-only from the published xpub, max {} per contact)",
                ourIdentityId, contactIds.size, MAX_LOGGED_ADDRESSES
            )
            for (contactId in contactIds) {
                log.info(
                    contactDerivationFactsLine(
                        collectFor(
                            ourIdentityId = ourIdentityId,
                            contactId = contactId,
                            wallet = wallet,
                            // Newest request each way: a re-issued request supersedes
                            // its predecessor, and the newest is what a current client
                            // reads.
                            toContactRef = toContact[contactId]?.maxByOrNull { it.timestamp }?.accountReference,
                            fromContactRef = fromContact[contactId]?.maxByOrNull { it.timestamp }?.accountReference,
                            username = profileDao.loadByUserId(contactId)?.username
                        )
                    )
                )
            }
            log.info("ContactDerivationFacts: END")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("ContactDerivationFacts: could not collect contact derivation facts", t)
        }
    }

    private fun collectFor(
        ourIdentityId: String,
        contactId: String,
        wallet: Wallet,
        toContactRef: Int?,
        fromContactRef: Int?,
        username: String?
    ): ContactDerivationFacts {
        // The receiving chain is keyed on the (us, contact) pair alone — the
        // accountReference scopes the SENDING (watch-only) chain, not the path
        // we derive our own incoming addresses on.
        val contact = EvolutionContact(ourIdentityId, contactId)
        val path = try {
            HDUtils.formatPath(
                FriendKeyChain.getContactPath(wallet.params, contact, FriendKeyChain.KeyChainType.RECEIVING_CHAIN)
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
        val addresses = try {
            if (!wallet.hasReceivingKeyChain(contact)) {
                null
            } else {
                val accountKey = wallet.getReceivingExtendedPublicKey(contact)
                (0 until MAX_LOGGED_ADDRESSES).map { index ->
                    Address.fromKey(
                        wallet.params,
                        HDKeyDerivation.deriveChildKey(accountKey, ChildNumber(index, false))
                    ).toBase58()
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
        return ContactDerivationFacts(
            contactIdentityId = contactId,
            username = username,
            accountRefToContact = toContactRef,
            accountRefFromContact = fromContactRef,
            derivationPath = path,
            receivingAddresses = addresses
        )
    }
}
