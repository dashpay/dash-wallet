/*
 * Copyright (c) 2022.
 * Copyright 2022 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package de.schildbach.wallet

import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

object WalletApplicationExt {
    /**
     * Clear databases
     *
     * @param isWalletWipe This is true for Reset Wallet, false for Rescan Blockchain
     */
    fun WalletApplication.clearDatabases(isWalletWipe: Boolean) {
        val scope = CoroutineScope(Dispatchers.IO)
        val context = this
        scope.launch {
            platformSyncService.clearDatabases()
            if (isWalletWipe) {
                transactionMetadataProvider.clear()
            }
            platformRepo.clearDatabase(isWalletWipe)
            WorkManager.getInstance(context).cancelAllWork()
        }
    }
}