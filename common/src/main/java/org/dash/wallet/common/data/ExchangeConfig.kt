package org.dash.wallet.common.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.util.security.EncryptionProvider

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
        val hashMapString = get(ACCOUNT_LIST) ?: ""
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
}
