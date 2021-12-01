//package org.dash.wallet.common
//
//import android.content.Context
//import androidx.datastore.core.DataStore
//import androidx.datastore.preferences.core.Preferences
//import androidx.datastore.preferences.core.edit
//import androidx.datastore.preferences.core.stringPreferencesKey
//import androidx.datastore.preferences.preferencesDataStore
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.map
//import javax.inject.Inject
//
//class UserPreferences @Inject constructor(val context: Context) {
//
//    private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
//        name = "org.dash.wallet.user.data.store"
//    )
//
//    val accessToken: Flow<String?>
//        get() = context.userPreferencesDataStore.data.map { preferences ->
//            preferences[ACCESS_TOKEN] ?: "0f555737c1f8847d1e66aee7eabac4f88d03c64972fb010cedbfc590214006ff"
//        }
//
//    val refreshToken: Flow<String?>
//        get() = context.userPreferencesDataStore.data.map { preferences ->
//            preferences[REFRESH_TOKEN] ?: "c7f3fc2de8533e84c669c5620ed8589854e3a18f5fa16869d30647f7214b63d0"
//        }
//
//    val lastCoinBaseBalance: Flow<String?>
//        get() = context.userPreferencesDataStore.data.map { preferences ->
//            preferences[LAST_COINBASE_BALANCE]
//        }
//
//    suspend fun saveAccessTokens(accessToken: String, refreshToken: String) {
//        context.userPreferencesDataStore.edit { preferences ->
//            preferences[ACCESS_TOKEN] = accessToken
//            preferences[REFRESH_TOKEN] = refreshToken
//        }
//    }
//
//    suspend fun saveCoinBaseBalance(balance: String) {
//        context.userPreferencesDataStore.edit { preferences ->
//            preferences[LAST_COINBASE_BALANCE] = balance
//        }
//    }
//
//    suspend fun clear() {
//        context.userPreferencesDataStore.edit { preferences ->
//            preferences.clear()
//        }
//    }
//
//    companion object {
//        private val PREFS_KEY_LAST_COINBASE_ACCESS_TOKEN = "last_coinbase_access_token"
//        private val PREFS_KEY_LAST_COINBASE_REFRESH_TOKEN = "last_coinbase_refresh_token"
//        private val PREFS_KEY_LAST_COINBASE_BALANCE = "last_coinbase_balance"
//
//        private val ACCESS_TOKEN = stringPreferencesKey(PREFS_KEY_LAST_COINBASE_ACCESS_TOKEN)
//        private val REFRESH_TOKEN = stringPreferencesKey(PREFS_KEY_LAST_COINBASE_REFRESH_TOKEN)
//        private val LAST_COINBASE_BALANCE = stringPreferencesKey(PREFS_KEY_LAST_COINBASE_BALANCE)
//    }
//}
