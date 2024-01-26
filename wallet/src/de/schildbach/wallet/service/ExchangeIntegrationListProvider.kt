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
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet_test.R
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.dash.wallet.integrations.uphold.data.UpholdCard
import org.dash.wallet.integrations.uphold.data.UpholdCardAddressList
import org.dash.wallet.integrations.uphold.utils.UpholdConfig
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine

class ExchangeIntegrationListProvider @Inject constructor(
    private val context: Context,
    private val coinBaseRepository: CoinBaseRepository,
    private val coinbaseConfig: CoinbaseConfig,
    private val upholdConfig: UpholdConfig,
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
        // uphold
        processUphold(currency, exchangeIntegrations)

        return exchangeIntegrations
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
                val client = UpholdClient.init(context, securityFunctions.getAuthenticationHash())
                // determine if we are connected

                if (client.isAuthenticated) {
                    val cards: List<UpholdCard>? = suspendCoroutine {
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
                    if (cards != null) {
                        val accountMap = hashMapOf<String, String>()
                        // perform associateBy, duplicate keys will use the first value
                        cards.forEach {
                            if (!accountMap.containsKey(it.currency)) {
                                accountMap[it.currency] = it.id
                            }
                        }
                        upholdConfig.setAccounts(accountMap)
                        val card = cards.find { it.currency == currency }
                        if (card != null) {
                            val addresses = suspendCoroutine {
                                client.listCardAddress(
                                    card.id,
                                    object : UpholdClient.Callback<List<UpholdCardAddressList>?> {
                                        override fun onSuccess(data: List<UpholdCardAddressList>?) {
                                            it.resumeWith(Result.success(data))
                                        }

                                        override fun onError(e: java.lang.Exception?, otpRequired: Boolean) {
                                            it.resumeWith(Result.failure(e!!))
                                        }
                                    }
                                )
                            }

                            val addressMap = upholdConfig.getAddressMap().toMutableMap()
                            val address = if (addresses.isNullOrEmpty()) {
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
                            address?.let {
                                addressMap[card.id] = it
                                upholdConfig.setAddressMap(addressMap)
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
                        }
                    }
                } else {
                    exchangeIntegrations.add(
                        ExchangeIntegration("uphold", false, null, null, R.string.uphold_account, R.drawable.ic_uphold)
                    )
                }
            } catch (e: Exception) {
                // exchangeIntegrations.add(
                //    ExchangeIntegration("uphold", false, null, null, R.string.uphold_account, R.drawable.ic_uphold)
                // )
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
        val accountId = config.getAccounts()[currency]
        if (accountId != null) {
            val address = config.getAddressMap()[accountId]
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
        }
        return false
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
