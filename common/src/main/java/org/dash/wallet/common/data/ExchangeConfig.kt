/*
 * Copyright 2024 Dash Core Group
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

package org.dash.wallet.common.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.util.security.EncryptionProvider

/**
 * Base class for exchange configurations
 */

open class ExchangeConfig(
    context: Context,
    name: String,
    walletDataProvider: WalletDataProvider,
    encryptionProvider: EncryptionProvider? = null,
    migrations: List<DataMigration<Preferences>> = listOf()
): BaseConfig(
    context,
    name,
    walletDataProvider,
    encryptionProvider,
    migrations
) {
    companion object {
        val ACCOUNT_LIST = stringPreferencesKey("crypto_currency_account_list")
        val ACCOUNT_ADDRESS_MAP = stringPreferencesKey("account_address_list")
    }

    suspend fun getAccounts(): Map<String, String> {
        val hashMapString = get(ACCOUNT_LIST) ?: "{}"
        return Gson().fromJson(hashMapString, object : TypeToken<HashMap<String, String>>() {}.type)
    }

    suspend fun setAccounts(accountMap: Map<String, String>) {
        set(ACCOUNT_LIST, Gson().toJson(accountMap))
    }

    suspend fun getAddressMap(): Map<String, String> {
        val hashMapString = get(ACCOUNT_ADDRESS_MAP)
        return if (hashMapString == null) {
            mapOf()
        } else {
            Gson().fromJson(hashMapString, object : TypeToken<HashMap<String, String>>() {}.type)
        }
    }

    suspend fun setAddressMap(accountMap: Map<String, String>) {
        set(ACCOUNT_ADDRESS_MAP, Gson().toJson(accountMap))
    }

    suspend fun getCurrencyAddress(currency: String): String? {
        val accountId = getAccounts()[currency]
        return accountId?.let { getAddressMap()[accountId] }
    }

    suspend fun setAccountAddress(accountId: String, address: String) {
        val accountMap = getAddressMap().toMutableMap()
        accountMap[accountId] = address
        setAddressMap(accountMap)
    }
}
