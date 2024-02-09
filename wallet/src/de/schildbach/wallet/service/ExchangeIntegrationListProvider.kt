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
import org.dash.wallet.common.data.unwrap
import org.dash.wallet.common.integrations.ExchangeIntegration
import org.dash.wallet.common.integrations.ExchangeIntegrationProvider
import org.dash.wallet.integrations.coinbase.repository.CoinBaseRepository
import org.dash.wallet.integrations.coinbase.utils.CoinbaseConfig
import org.dash.wallet.integrations.uphold.api.UpholdClient
import org.dash.wallet.integrations.uphold.api.createCardAddress
import org.dash.wallet.integrations.uphold.api.getAllCards
import org.dash.wallet.integrations.uphold.api.isAuthenticated
import org.dash.wallet.integrations.uphold.api.listCardAddress
import org.dash.wallet.integrations.uphold.utils.UpholdConfig
import javax.inject.Inject

class ExchangeIntegrationListProvider @Inject constructor(
    private val coinBaseRepository: CoinBaseRepository,
    private val coinbaseConfig: CoinbaseConfig,
    private val upholdConfig: UpholdConfig,
    private val upholdClient: UpholdClient
) : ExchangeIntegrationProvider {

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
                    val coinbaseAccount = coinBaseRepository.getUserAccount(currency)
                    val coinbaseAddress = coinBaseRepository.createAddress(coinbaseAccount.uuid)

                    exchangeIntegrations.add(
                        ExchangeIntegration(
                            "coinbase",
                            coinBaseRepository.isAuthenticated,
                            coinbaseAddress.unwrap(),
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
        } catch (e: IllegalStateException) {
            // no account for this currency
            e.printStackTrace()
        } catch (e: Exception) {
            // another error? not connected?
            e.printStackTrace()
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

                if (upholdClient.isAuthenticated) {
                    val cards = upholdClient.getAllCards()

                    if (cards != null) {
                        val card = cards.find { it.currency == currency }
                        if (card != null) {
                            val cardAddress = upholdClient.listCardAddress(card.id, currency)

                            val address = cardAddress?.value
                                ?: upholdClient.createCardAddress(
                                    card.id,
                                    currency
                                )

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
                        }
                    }
                } else {
                    exchangeIntegrations.add(
                        ExchangeIntegration("uphold", false, null, null, R.string.uphold_account, R.drawable.ic_uphold)
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
