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
package de.schildbach.wallet.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * Receiver to handle delayed blockchain service startup for Android 15+ to avoid
 * BOOT_COMPLETED restrictions on dataSync foreground services.
 */
@AndroidEntryPoint
class DelayedServiceStartReceiver : BroadcastReceiver() {
    @Inject
    lateinit var application: WalletApplication

    override fun onReceive(context: Context, intent: Intent) {
        log.info("starting delayed blockchain service")
        application.startBlockchainService(false)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(
            DelayedServiceStartReceiver::class.java
        )
    }
}