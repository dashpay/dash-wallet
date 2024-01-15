/*
 * Copyright 2023 Dash Core Group.
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

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.google.common.hash.HashCode
import com.google.common.hash.Hashing
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import org.bitcoinj.core.VersionMessage
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackageInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val log = LoggerFactory.getLogger(PackageInfoProvider::class.java)
    }

    val packageInfo: PackageInfo = packageInfoFromContext(context)
    val versionCode: Int = packageInfo.versionCode
    val versionName: String = packageInfo.versionName
    val installerPackageName = resolveInstallerPackageName()
    val databases = context.databaseList().toList()
    val filesDir = context.filesDir

    fun applicationPackageFlavor(): String? {
        val packageName = context.packageName
        val index = packageName.lastIndexOf('_')

        return if (index != -1) packageName.substring(index + 1) else null
    }

    fun httpUserAgent(versionName: String): String {
        val versionMessage = VersionMessage(Constants.NETWORK_PARAMETERS, 0)
        versionMessage.appendToSubVer(Constants.USER_AGENT, versionName, null)

        return versionMessage.subVer
    }

    fun httpUserAgent(): String {
        return httpUserAgent(packageInfo.versionName)
    }

    private fun packageInfoFromContext(context: Context): PackageInfo {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0)!!
        } catch (x: PackageManager.NameNotFoundException) {
            throw RuntimeException(x)
        }
    }

    private fun resolveInstallerPackageName(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(packageInfo.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(packageInfo.packageName)
            }
        } catch (ex: Exception) {
            log.error("Failed to get installer package name", ex)
            null
        }
    }

    @Throws(IOException::class)
    fun apkHash(): HashCode {
        val hasher = Hashing.sha256().newHasher()
        val inputStream: FileInputStream = FileInputStream(context.packageCodePath)
        val buf = ByteArray(4096)
        var read: Int
        while (-1 != inputStream.read(buf).also { read = it }) hasher.putBytes(buf, 0, read)
        inputStream.close()
        return hasher.hash()
    }
}
