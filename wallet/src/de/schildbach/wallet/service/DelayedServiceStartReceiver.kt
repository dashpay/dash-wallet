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
 * Fallback start of the blockchain service, fired by an alarm that
 * [PackageReplacedReceiver] schedules when its direct foreground-service start after an
 * app update is refused. Nothing schedules it from `BOOT_COMPLETED` any more: a boot
 * does not start syncing (see [BootstrapReceiver]).
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
        // Measured on the 2026-09-17 cold-boot test, when the boot path still
        // scheduled this alarm: the receiver fired and logged "starting delayed
        // blockchain service", and no service was created. See §25 of the
        // upgrade memory and sync plan.
        //
        // Use the guard-bypassing start instead. The platform may still refuse
        // it, but a refusal is caught and logged there, which is a diagnosable
        // failure rather than a silent one.
        //
        // A wallet reset does not cancel this alarm, so it can fire after the
        // wallet is gone: never start the service without a wallet. A file
        // check, because the main thread must not wait for a deferred wallet
        // load, and an in-memory check could read "no wallet" while one loads.
        if (application.isWipeInProgress || !application.walletFileExists()) {
            log.info("delayed blockchain service start skipped: no wallet (reset since it was scheduled)")
            return
        }
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