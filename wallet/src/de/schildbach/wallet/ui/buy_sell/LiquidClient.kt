/*
 * Copyright 2022 Dash Core Group.
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
package de.schildbach.wallet.ui.buy_sell

import android.content.Context
import android.content.SharedPreferences
import com.securepreferences.SecurePreferences


class LiquidClient private constructor(context: Context, private val encryptionKey: String) {
    private val prefs: SharedPreferences

    init {
        prefs = SecurePreferences(context, encryptionKey, LIQUID_PREFS)
    }

    companion object {
        private var instance: LiquidClient? = null
        private const val LIQUID_PREFS = "liquid_prefs.xml"
        private const val LIQUID_SESSION_ID = "session_id"
        private const val LIQUID_SESSION_SECRET = "session_secret"
        private const val LIQUID_USER_ID = "user_id"

        fun init(context: Context, prefsEncryptionKey: String): LiquidClient? {
            instance = LiquidClient(context, prefsEncryptionKey)
            return instance
        }

        fun getInstance(): LiquidClient {
            checkNotNull(instance) { "You must call LiquidClient#init() first" }
            return instance!!
        }
    }

    fun clearLiquidData() {
        prefs.edit().clear().apply()
    }

    private val storedSessionId: String? get() = prefs.getString(LIQUID_SESSION_ID, "")
    private val storedUserId: String? get() = prefs.getString(LIQUID_USER_ID, "")
    private val storedSessionSecret: String? get() = prefs.getString(LIQUID_SESSION_SECRET, "")

    val isAuthenticated: Boolean get() = storedUserId != "" && storedSessionId != "" && storedSessionSecret != ""
}