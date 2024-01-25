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

import android.content.Context
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.dash.wallet.integrations.uphold.data.UpholdCard
import org.dash.wallet.integrations.uphold.data.UpholdCardAddressList
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine

class ExchangeIntegrationListProvider @Inject constructor(
    private val context: Context,
    private val coinBaseRepository: CoinBaseRepository,
    private val coinbaseConfig: CoinbaseConfig,
    private val securityFunctions: SecurityFunctions
) : ExchangeIntegrationProvider {

    private val exchangeList = MutableStateFlow(listOf<ExchangeIntegration>())
    companion object {
        private val networks = mapOf(
            "DASH" to "dash",
            "BTC" to "bitcoin",
            "ETH" to "ethereum",
            "USDC" to "ethereum",
            "USDT" to "ethereum"
        )
    }

    init {
        val list = listOf(
            ExchangeIntegration("coinbase", false, null, null, R.string.coinbase, R.drawable.ic_coinbase),
            ExchangeIntegration("uphold", false, null, null, R.string.uphold_account, R.drawable.ic_uphold)
        )
        exchangeList.value = list
    }

    override suspend fun getDepositAddresses(currency: String): List<ExchangeIntegration> {
        val exchangeIntegrations = arrayListOf<ExchangeIntegration>()
        // coinbase
        try {
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
                    ExchangeIntegration("coinbase", false, null, currency, R.string.coinbase, R.drawable.ic_coinbase)
                )
            }
        } catch (e: IllegalStateException) {
            // no account for this currency
            e.printStackTrace()
        } catch (e: Exception) {
            // another error? not connected?
            e.printStackTrace()

        }
        // uphold

        try {
            // put this in security functions

            // Uses Sha256 hash of excerpt of xpub as Uphold authentication salt

            val client = UpholdClient.init(context, securityFunctions.getAuthenticationHash())
            // determine if we are connected

            if (client.isAuthenticated) {
                val cardsResponse: List<UpholdCard>? = suspendCoroutine {
                    client.getAllCards(
                        object : UpholdClient.Callback<List<UpholdCard>> {
                            override fun onSuccess(data: List<UpholdCard>?) {
                                it.resumeWith(Result.success(data))
                            }

                            override fun onError(e: java.lang.Exception?, otpRequired: Boolean) {
                                it.resumeWith(Result.failure(e!!))
                            }
                        }
                    )
                }
                if (cardsResponse != null) {
                    val cards = cardsResponse
                    val card = cards.find { it.currency == currency }
                    if (card != null) {
                        val addresses = suspendCoroutine {
                            client.listCardAddress(card.id, object : UpholdClient.Callback<List<UpholdCardAddressList>?> {
                                override fun onSuccess(data: List<UpholdCardAddressList>?) {
                                    it.resumeWith(Result.success(data))
                                }

                                override fun onError(e: java.lang.Exception?, otpRequired: Boolean) {
                                    it.resumeWith(Result.failure(e!!))
                                }
                            })
                        }

                        val address = if (addresses == null || addresses.isEmpty()) {
                            suspendCoroutine {
                                client.createCardAddress(
                                    card.id,
                                    networks[currency]!!,
                                    object : UpholdClient.Callback<String> {
                                        override fun onSuccess(data: String?) {
                                            it.resumeWith(Result.success(data))
                                        }

                                        override fun onError(e: java.lang.Exception?, otpRequired: Boolean) {
                                            it.resumeWith(Result.failure(e!!))
                                        }
                                    }
                                )
                            }
                        } else {
                            addresses.find { it.type == networks[currency] }?.firstOrNull()?.value
                        }
                        exchangeIntegrations.add(
                            ExchangeIntegration(
                                "uphold",
                                true,
                                address,
                                null,
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
            //exchangeIntegrations.add(
            //    ExchangeIntegration("uphold", false, null, null, R.string.uphold_account, R.drawable.ic_uphold)
            //)
        }

        return exchangeIntegrations
    }

//    override fun observeDepositAddresses(currency: String): Flow<List<ExchangeIntegration>> {
//        val list = listOf(
//            ExchangeIntegration(
//                "coinbase",
//                false,
//                "183axN6F7ZjwayiJPjjwJgWGas6J9mtfi",
//                currency,
//                R.string.coinbase,
//                R.drawable.ic_coinbase
//            ),
//            ExchangeIntegration(
//                "uphold",
//                false,
//                "bc1qxhgnnp745zryn2ud8hm6k3mygkkpkm35020js0",
//                currency,
//                R.string.uphold_account,
//                R.drawable.ic_uphold
//            )
//        )
//        exchangeList.value = list
//        return exchangeList
//    }

    override fun connectToIntegration(name: String) {
        TODO("Not yet implemented")
    }
}
