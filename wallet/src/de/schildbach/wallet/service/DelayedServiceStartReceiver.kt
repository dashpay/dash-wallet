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
        // MUST NOT be startBlockchainService(): that method only calls
        // startService() when the process importance is at or better than
        // IMPORTANCE_FOREGROUND, and a process woken by this alarm broadcast
        // sits well below it. The call then returns having done nothing, with
        // no log and no exception.
        //
        // Measured on the 2026-09-17 cold-boot test: after a reboot this
        // receiver fired and logged "starting delayed blockchain service",
        // and no service was created — the wallet did no block sync at all
        // until the app was opened by hand. See §25 of the upgrade memory and
        // sync plan.
        //
        // Use the guard-bypassing start instead. On Android 15+ the platform
        // may still refuse it (BlockchainServiceImpl is a `dataSync` foreground
        // service and BOOT_COMPLETED cannot launch that type), but a refusal is
        // caught and logged there, which is a diagnosable failure rather than a
        // silent one, and pre-15 devices now actually start syncing.
        log.info("starting delayed blockchain service")
        if (!application.startBlockchainServiceAfterUpgrade()) {
            log.warn(
                "the delayed blockchain service start was refused — this device will not " +
                    "resume syncing until the app is opened"
            )
        }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(
            DelayedServiceStartReceiver::class.java
        )
    }
}