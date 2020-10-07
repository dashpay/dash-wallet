/*
 * Copyright 2020 Dash Core Group.
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

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.annotation.RequiresApi
import org.slf4j.LoggerFactory


@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class BlockchainSyncJobService : JobService() {

    private val log = LoggerFactory.getLogger(BlockchainSyncJobService::class.java)

    private val serviceConnection: ServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            log.info("blockchain sync job service connected")
            if (binder is BlockchainServiceImpl.LocalBinder) {
                log.info("blockchain sync job force foreground from ServiceConnection")
                binder.service.forceForeground()
            }
            unbindService(this)
        }

        override fun onServiceDisconnected(name: ComponentName) {

        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        log.info("blockchain sync job started")
        bindService(Intent(this, BlockchainServiceImpl::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        //wait some time just to make sure that the service started
        Handler().postDelayed({ jobFinished(params, false) }, 3000)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        log.info("blockchain sync job cancelled before completion")
        stopService(Intent(this, BlockchainServiceImpl::class.java))
        return false
    }
}