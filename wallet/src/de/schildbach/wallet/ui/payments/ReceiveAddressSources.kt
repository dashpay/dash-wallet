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

package de.schildbach.wallet.ui.payments

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Context
import org.dash.wallet.common.services.ReceiveAddressUnavailableException
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * Where one balance tab of the Advanced Receive page gets the value it advertises.
 */
fun interface ReceiveAddressSource {
    /**
     * The address (or ID) to advertise, or null when there is nothing safe to show
     * right now. Null is retryable: the page asks again on its next resume.
     */
    suspend fun address(): String?
}

/**
 * The Core (L1) receive address: the same live read as [PaymentsViewModel.getCurrentAddress].
 * Post-cutover the dashj key chain is held and its "current" pointer frozen on an address
 * the wallet may already have been paid on (SR-03), so this asks the SDK engine and, when
 * the engine cannot answer, advertises nothing rather than any dashj fallback.
 */
class CoreReceiveAddressSource(private val walletData: WalletData) : ReceiveAddressSource {
    private companion object {
        private val log = LoggerFactory.getLogger(CoreReceiveAddressSource::class.java)
    }

    override suspend fun address(): String? = withContext(Dispatchers.IO) {
        val wallet = walletData.wallet ?: return@withContext null
        Context.propagate(wallet.context)
        try {
            walletData.currentReceiveAddressLive().toBase58()
        } catch (ex: ReceiveAddressUnavailableException) {
            log.warn("receive address unavailable; advertising nothing", ex)
            null
        }
    }
}

/** The address sources of the Receive page's balance tabs, except Identity (see [ReceiveViewModel]). */
class ReceiveAddressSources(
    val core: ReceiveAddressSource,
    val shielded: ReceiveAddressSource,
    val platform: ReceiveAddressSource
) {
    @Inject
    constructor(walletData: WalletData, shieldedBalanceService: ShieldedBalanceService) : this(
        core = CoreReceiveAddressSource(walletData),
        shielded = ReceiveAddressSource { shieldedBalanceService.shieldedReceiveAddress() },
        platform = ReceiveAddressSource { shieldedBalanceService.platformReceiveAddress() }
    )
}

/** What [ReceiveFragment]'s Specify amount puts in its request; see [AmountRequestAddressResolver]. */
sealed interface AmountRequestTarget {
    /** Core: [ReceiveFragment] reads a fresh Core address itself, as it always has. */
    data object Core : AmountRequestTarget

    /** [address] was read from [tab]'s wallet source just now. */
    data class Address(val tab: ReceiveBalanceTab, val address: String) : AmountRequestTarget

    /** An unknown balance, or its source has nothing safe to show: no request at all. */
    data object Unavailable : AmountRequestTarget
}

/**
 * Resolves the balance [ReceiveFragment] was opened for to the address its request carries.
 *
 * [ReceiveFragment] is reachable from other apps: MainActivity is exported and navigates to any
 * destination an intent names, with that intent's arguments. So its argument names only a
 * balance (a [ReceiveBalanceTab] name), never an address: the address is always read here,
 * from the same wallet sources Advanced Receive shows. Fail closed: an unknown balance, or a
 * source that has no address or fails, yields [AmountRequestTarget.Unavailable], never Core's
 * address for another balance.
 */
class AmountRequestAddressResolver(
    private val sources: ReceiveAddressSources,
    private val identity: ReceiveAddressSource
) {
    @Inject
    constructor(sources: ReceiveAddressSources, identityConfig: BlockchainIdentityConfig) : this(
        sources,
        // As ReceiveViewModel's Identity tab: the identity ID, only once it has a username.
        ReceiveAddressSource { identityConfig.load()?.takeIf { it.hasUsername }?.userId }
    )

    private companion object {
        private val log = LoggerFactory.getLogger(AmountRequestAddressResolver::class.java)
    }

    /** [balance] is the navigation argument: null (every caller but Advanced Receive) is Core. */
    suspend fun resolve(balance: String?): AmountRequestTarget {
        if (balance == null) return AmountRequestTarget.Core
        val tab = ReceiveBalanceTab.entries.firstOrNull { it.name == balance }
        if (tab == null) {
            // Not logged: the value came from outside and may be anything, an address included.
            log.warn("unknown balance requested for an amount request; showing none")
            return AmountRequestTarget.Unavailable
        }
        val source = when (tab) {
            ReceiveBalanceTab.CORE -> return AmountRequestTarget.Core
            ReceiveBalanceTab.SHIELD -> sources.shielded
            ReceiveBalanceTab.PLATFORM -> sources.platform
            ReceiveBalanceTab.IDENTITY -> identity
        }
        val address = try {
            source.address()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("receive address read failed for {}; showing no request", tab, e)
            null
        }
        return address?.takeIf { it.isNotEmpty() }
            ?.let { AmountRequestTarget.Address(tab, it) }
            ?: AmountRequestTarget.Unavailable
    }
}
