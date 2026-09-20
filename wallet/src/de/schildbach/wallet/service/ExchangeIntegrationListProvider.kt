/*
 * Copyright 2024 Dash Core Group.
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

package de.schildbach.wallet.service

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.schildbach.wallet_test.R
import org.dash.wallet.common.data.ExchangeConfig
import org.dash.wallet.common.data.ResponseResource
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.integrations.ExchangeIntegration
import org.dash.wallet.common.integrations.ExchangeIntegrationProvider
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepository
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import dagger.Lazy
import org.dash.wallet.integrations.uphold.api.UpholdClient
import org.dash.wallet.integrations.uphold.api.createCardAddress
import org.dash.wallet.integrations.uphold.api.getAllCards
import org.dash.wallet.integrations.uphold.api.isAuthenticated
import org.dash.wallet.integrations.uphold.api.listCardAddress
import org.dash.wallet.integrations.uphold.utils.UpholdConfig
import org.slf4j.LoggerFactory
import javax.inject.Inject

class ExchangeIntegrationListProvider @Inject constructor(
    private val coinBaseRepository: CoinBaseRepository,
    private val coinbaseConfig: CoinbaseConfig,
    private val upholdConfig: UpholdConfig,
    private val upholdClient: Lazy<UpholdClient>
) : ExchangeIntegrationProvider {

    companion object {
        private val log = LoggerFactory.getLogger(ExchangeIntegrationListProvider::class.java)
    }

    override suspend fun clearCachedAddresses() {
        coinbaseConfig.clearCurrencyAddresses()
        upholdConfig.clearCurrencyAddresses()
    }

    override suspend fun clearCachedAddresses(service: String) = when (service) {
        ServiceName.Coinbase -> coinbaseConfig.clearCurrencyAddresses()
        ServiceName.Uphold -> upholdConfig.clearCurrencyAddresses()
        else -> error("$service is not supported")
    }

    override suspend fun getDepositAddresses(currency: String): List<ExchangeIntegration> {
        val exchangeIntegrations = arrayListOf<ExchangeIntegration>()
        // coinbase
        processCoinbase(currency, exchangeIntegrations)
        // uphold
        processUphold(currency, exchangeIntegrations)

        return exchangeIntegrations
    }

    private suspend fun processCoinbase(
        currency: String,
        exchangeIntegrations: ArrayList<ExchangeIntegration>
    ) {
        try {
            if (!lookUpAddress(
                    "coinbase",
                    coinbaseConfig,
                    currency,
                    R.string.coinbase,
                    R.drawable.ic_coinbase,
                    exchangeIntegrations
                )
            ) {
                // determine if we are connected
                if (coinBaseRepository.isAuthenticated) {
                    // A failed account/address lookup must not hide Coinbase entirely: log why
                    // and add the row without an address, so a logged-in user still sees the
                    // integration instead of it silently vanishing from the list.
                    val address = try {
                        val coinbaseAccount = coinBaseRepository.getUserAccount(currency)
                        val coinbaseAddress = coinBaseRepository.createAddress(coinbaseAccount.uuid)
                        ((coinbaseAddress as? ResponseResource.Success)?.value).also {
                            if (it == null) {
                                log.warn("coinbase: createAddress for {} did not return an address", currency)
                            }
                        }
                    } catch (e: IllegalStateException) {
                        // getUserAccount: no usable account for this currency
                        log.warn("coinbase: no {} account found: {}", currency, e.message)
                        null
                    } catch (e: Exception) {
                        log.error("coinbase: failed to look up {} deposit address", currency, e)
                        null
                    }

                    exchangeIntegrations.add(
                        ExchangeIntegration(
                            "coinbase",
                            true,
                            address,
                            currency,
                            R.string.coinbase,
                            R.drawable.ic_coinbase
                        )
                    )
                } else {
                    exchangeIntegrations.add(
                        ExchangeIntegration(
                            "coinbase",
                            false,
                            null,
                            currency,
                            R.string.coinbase,
                            R.drawable.ic_coinbase
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // cached-address or auth-state lookup failed; auth state unknown, so no row
            log.error("coinbase: failed to determine integration state for {}", currency, e)
        }
    }

    private suspend fun processUphold(
        currency: String,
        exchangeIntegrations: ArrayList<ExchangeIntegration>
    ) {
        if (!lookUpAddress(
                "uphold",
                upholdConfig,
                currency,
                R.string.uphold_account,
                R.drawable.ic_uphold,
                exchangeIntegrations
            )
        ) {
            try {
                // determine if we are connected
                if (upholdClient.get().isAuthenticated) {
                    // Same rule as Coinbase: a failed card/address lookup must not hide Uphold
                    // entirely -- log why and add the row without an address.
                    val address = try {
                        val card = upholdClient.get().getAllCards()?.find { it.currency == currency }

                        if (card != null) {
                            upholdClient.get().listCardAddress(card.id, currency)?.value
                                ?: upholdClient.get().createCardAddress(card.id, currency)
                        } else {
                            log.warn("uphold: no {} card found", currency)
                            null
                        }
                    } catch (e: Exception) {
                        log.error("uphold: failed to look up {} deposit address", currency, e)
                        null
                    }

                    exchangeIntegrations.add(
                        ExchangeIntegration(
                            "uphold",
                            true,
                            address,
                            currency,
                            R.string.uphold_account,
                            R.drawable.ic_uphold
                        )
                    )
                } else {
                    exchangeIntegrations.add(
                        ExchangeIntegration("uphold", false, null, null, R.string.uphold_account, R.drawable.ic_uphold)
                    )
                }
            } catch (e: Exception) {
                // auth-state lookup failed; auth state unknown, so no row
                log.error("uphold: failed to determine integration state for {}", currency, e)
            }
        }
    }

    private suspend fun lookUpAddress(
        serviceName: String,
        config: ExchangeConfig,
        currency: String,
        @StringRes nameId: Int,
        @DrawableRes iconId: Int,
        exchangeIntegrations: ArrayList<ExchangeIntegration>
    ): Boolean {
        val address = config.getCurrencyAddress(currency)
        if (address != null) {
            exchangeIntegrations.add(
                ExchangeIntegration(
                    serviceName,
                    true,
                    address,
                    null,
                    nameId,
                    iconId
                )
            )
            return true
        }
        return false
    }
}
