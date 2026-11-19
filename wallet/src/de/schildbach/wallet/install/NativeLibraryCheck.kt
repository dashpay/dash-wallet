/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.install

import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Whether the installed APKs carry the native libraries the wallet cannot run without.
 *
 * Google Play installs the app as a base APK plus a `config.<abi>` split that holds
 * every `.so` file. A device that installs only the base APK (copied off another
 * phone, pulled by an APK extractor, installed by an emulator or a "virtual phone"
 * app) has none of them, and the first `System.loadLibrary` kills the process
 * before any UI. [WalletAppComponentFactory] asks this check once per process and,
 * only on [State.MISSING], starts inert components and a reinstall screen instead
 * of the wallet.
 *
 * The check reads the APKs' zip directories; it never loads a library.
 */
object NativeLibraryCheck {

    enum class State {
        /** Every required library is in the APKs (or extracted) for a supported ABI. */
        PRESENT,

        /** Every APK was read, and no supported ABI has all the required libraries. */
        MISSING,

        /** The check could not run or an APK could not be read: start normally. */
        UNKNOWN
    }

    private const val TAG = "NativeLibraryCheck"

    /**
     * Libraries the wallet loads unconditionally: the Platform SDKs and dashj's X11
     * and BLS. They all ship in the same `config.<abi>` split, so they are present
     * or missing together.
     */
    val REQUIRED_LIBRARIES = listOf("libsdklib.so", "libdash_sdk_jni.so", "libx11.so", "libdashjbls.so")

    @Volatile
    private var processState: State? = null

    @Volatile
    private var processDetails: String = ""

    /** True only when this process's check affirmatively found the libraries missing. */
    val isIncompleteInstall: Boolean
        get() = processState == State.MISSING

    /** Non-sensitive description of the install for diagnostics: ABIs and split names. */
    val details: String
        get() = processDetails

    /**
     * Runs the check for this process the first time it is called; later calls are
     * no-ops. Never throws: a failure is [State.UNKNOWN], which starts the app normally.
     */
    @Synchronized
    fun inspectOnce(appInfo: ApplicationInfo) {
        if (processState != null) {
            return
        }
        val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val state = try {
            // Splits first: in a Play install the libraries are in config.<abi>.apk,
            // whose zip directory is much smaller than the base APK's.
            val apkPaths = appInfo.splitSourceDirs.orEmpty().toList() + listOfNotNull(appInfo.sourceDir)
            inspect(apkPaths, appInfo.nativeLibraryDir, supportedAbis, ::libraryEntries)
        } catch (e: Exception) {
            Log.w(TAG, "native library check failed; starting normally", e)
            State.UNKNOWN
        }
        processDetails = "abis=$supportedAbis splits=${appInfo.splitNames?.toList().orEmpty()}"
        processState = state
        if (state != State.PRESENT) {
            Log.w(TAG, "native libraries: $state ($processDetails)")
        }
    }

    /**
     * The check itself, free of Android state so it can be unit-tested.
     *
     * @param apkPaths the installed APKs (splits and base)
     * @param nativeLibraryDir where the installer extracted libraries, if it did
     * @param supportedAbis the device's ABIs ([Build.SUPPORTED_ABIS])
     * @param apkEntries the entry names under `lib/` in one APK; throws [IOException]
     *   when the APK cannot be read
     */
    internal fun inspect(
        apkPaths: List<String>,
        nativeLibraryDir: String?,
        supportedAbis: List<String>,
        apkEntries: (String) -> Collection<String>
    ): State {
        if (supportedAbis.isEmpty() || apkPaths.isEmpty()) {
            return State.UNKNOWN
        }
        // An existing file proves presence; a missing one proves nothing, because
        // libraries normally stay inside the APK (extractNativeLibs=false).
        if (nativeLibraryDir != null && REQUIRED_LIBRARIES.all { File(nativeLibraryDir, it).isFile }) {
            return State.PRESENT
        }
        val found = HashSet<String>()
        var readFailed = false
        for (apk in apkPaths) {
            try {
                found.addAll(apkEntries(apk))
            } catch (e: IOException) {
                readFailed = true
                continue
            } catch (e: SecurityException) {
                readFailed = true
                continue
            }
            if (hasAllForSomeAbi(found, supportedAbis)) {
                return State.PRESENT
            }
        }
        return if (readFailed) State.UNKNOWN else State.MISSING
    }

    private fun hasAllForSomeAbi(entries: Set<String>, supportedAbis: List<String>): Boolean {
        return supportedAbis.any { abi -> REQUIRED_LIBRARIES.all { "lib/$abi/$it" in entries } }
    }

    private fun libraryEntries(apkPath: String): Collection<String> {
        ZipFile(apkPath).use { zip ->
            val names = ArrayList<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("lib/")) {
                    names.add(name)
                }
            }
            return names
        }
    }
}
