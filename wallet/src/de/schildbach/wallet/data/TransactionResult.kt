/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.data

import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.ExchangeRate
import java.io.Serializable
import java.util.*

/**
 * @author Samuel Barbosa
 */
data class TransactionResult(val dashAmount: Coin,
                             val exchangeRate: ExchangeRate?,
                             val address: String,
                             val feeAmount: Coin?,
                             val transactionHash: String,
                             val date: Date,
                             val purpose: Transaction.Purpose,
                             val primaryStatus: String,
                             val secondaryStatus: String,
                             val errorStatus: String) : Serializable
