/*
 * Copyright 2025 Dash Core Group.
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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.util.Constants
import javax.inject.Inject
import javax.inject.Singleton

enum class BlockStoreLastFix {
    NONE,
    COPY_FILES,
    COPY_FROM_STORE,
    RESTORE_FROM_WALLET
}

@Singleton
// Intended for blockchain service settings and configuration.
// Should be used by BlockchainService and related components.
open class BlockchainServiceConfig @Inject constructor(
    context: Context,
    walletDataProvider: WalletDataProvider
): BaseConfig(
    context,
    PREFERENCES_NAME,
    walletDataProvider,
    migrations = listOf()
) {
    companion object {
        private const val PREFERENCES_NAME = "blockchain_service"
        val BLOCKCHAIN_STORE_MEMORY_FAILURE = booleanPreferencesKey("blockchain_store_memory_failure")
        val BLOCKCHAIN_STORE_LAST_FIX = stringPreferencesKey("blockchain_store_last_fix")
        val WALLET_CREATION_DATE = longPreferencesKey("wallet_creation_date")
    }

    suspend fun getBlockStoreLastFix(): BlockStoreLastFix {
        return BlockStoreLastFix.valueOf(get(BLOCKCHAIN_STORE_LAST_FIX) ?: "NONE")
    }

    suspend fun getWalletCreationDate(): Long? {
        val creationDate = get(WALLET_CREATION_DATE)
        return if (creationDate != null && creationDate > Constants.EARLIEST_HD_SEED_CREATION_TIME) {
            creationDate
        } else {
            null
        }
    }

    suspend fun setWalletCreationDate(date: Long?) {
        if (date != null) {
            set(WALLET_CREATION_DATE, date)
        } else {
            set(WALLET_CREATION_DATE, Constants.EARLIEST_HD_SEED_CREATION_TIME)
        }
    }
}
