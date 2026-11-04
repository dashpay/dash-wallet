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
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
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
