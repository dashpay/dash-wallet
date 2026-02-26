/*
 * Copyright 2013-2015 the original author or authors.
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
package de.schildbach.wallet.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import de.schildbach.wallet.service.BlockchainService
import de.schildbach.wallet.service.BlockchainServiceImpl
import de.schildbach.wallet.service.BlockchainServiceImpl.LocalBinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @author Andreas Schildbach
 */
abstract class AbstractBindServiceActivity : LockScreenActivity() {
    var blockchainService: BlockchainService? = null
        private set

    private var shouldUnbind = false

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            blockchainService = (binder as LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName) {
            blockchainService = null
        }
    }

    override fun onResume() {
        super.onResume()

        doBindService()
    }

    private fun doBindService() {
        if (bindService(
                Intent(this, BlockchainServiceImpl::class.java),
                serviceConnection,
                BIND_AUTO_CREATE
            )
        ) {
            shouldUnbind = true
        } else {
            log.error("error: the requested service doesn't exist, or this client isn't allowed access to it.")
        }
    }

    override fun onPause() {
        doUnbindService()

        super.onPause()
    }

    fun doUnbindService() {
        if (shouldUnbind) {
            unbindService(serviceConnection)
            shouldUnbind = false
        }
    }

    fun unbindServiceServiceConnection() {
        doUnbindService()
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(
            AbstractBindServiceActivity::class.java
        )
    }
}
