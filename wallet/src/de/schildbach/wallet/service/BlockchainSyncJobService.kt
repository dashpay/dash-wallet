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
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import de.schildbach.wallet.WalletApplication
import org.slf4j.LoggerFactory

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class BlockchainSyncJobService : JobService() {

    private val log = LoggerFactory.getLogger(BlockchainSyncJobService::class.java)

    private val serviceIntent: Intent
    private val context: Context

    init {
        context = WalletApplication.getInstance()
        serviceIntent = Intent(context, BlockchainServiceImpl::class.java)
        serviceIntent.putExtra(BlockchainServiceImpl.START_AS_FOREGROUND_EXTRA, true)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        log.info("blockchain sync job started")
        ContextCompat.startForegroundService(context, serviceIntent)
        //wait some time just to make sure that the service started
        Handler().postDelayed({ jobFinished(params, false) }, 3000)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        log.info("blockchain sync job cancelled before completion")
        context.stopService(serviceIntent)
        return false
    }
}