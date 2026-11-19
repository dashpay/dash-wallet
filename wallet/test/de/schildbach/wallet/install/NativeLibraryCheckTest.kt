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

import de.schildbach.wallet.install.NativeLibraryCheck.State
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class NativeLibraryCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "/data/app/x/base.apk"
    private val armSplit = "/data/app/x/split_config.arm64_v8a.apk"
    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun libs(abi: String) = NativeLibraryCheck.REQUIRED_LIBRARIES.map { "lib/$abi/$it" }

    private fun check(
        apks: Map<String, Collection<String>?>,
        abis: List<String> = arm64,
        nativeDir: String? = null
    ): State = NativeLibraryCheck.inspect(apks.keys.toList(), nativeDir, abis) { path ->
        apks[path] ?: throw IOException("unreadable")
    }

    @Test
    fun playInstallWithAbiSplit_isPresent() {
        assertEquals(State.PRESENT, check(mapOf(armSplit to libs("arm64-v8a"), base to emptyList())))
    }

    @Test
    fun universalApk_isPresent() {
        assertEquals(State.PRESENT, check(mapOf(base to libs("arm64-v8a") + libs("x86_64"))))
    }

    // The 12.0.2 crash: the base APK of a bundle installed without its config.<abi> split.
    @Test
    fun baseApkOnly_isMissing() {
        assertEquals(State.MISSING, check(mapOf(base to emptyList())))
    }

    @Test
    fun librariesOnlyForAnotherAbi_isMissing() {
        assertEquals(State.MISSING, check(mapOf(base to libs("x86_64"))))
    }

    @Test
    fun oneRequiredLibraryAbsent_isMissing() {
        assertEquals(State.MISSING, check(mapOf(base to libs("arm64-v8a").drop(1))))
    }

    @Test
    fun x86_64Emulator_isPresent() {
        assertEquals(
            State.PRESENT,
            check(mapOf(base to libs("arm64-v8a") + libs("x86_64")), abis = listOf("x86_64", "x86"))
        )
    }

    @Test
    fun unreadableApk_isUnknown_evenWhenOthersLackLibraries() {
        assertEquals(State.UNKNOWN, check(mapOf(armSplit to null, base to emptyList())))
    }

    @Test
    fun unreadableApk_afterLibrariesFound_isPresent() {
        assertEquals(State.PRESENT, check(mapOf(armSplit to libs("arm64-v8a"), base to null)))
    }

    @Test
    fun noAbisOrNoApks_isUnknown() {
        assertEquals(State.UNKNOWN, check(mapOf(base to emptyList()), abis = emptyList()))
        assertEquals(State.UNKNOWN, check(emptyMap()))
    }

    @Test
    fun extractedLibraries_arePresent_withoutReadingApks() {
        val dir = tmp.newFolder("lib")
        NativeLibraryCheck.REQUIRED_LIBRARIES.forEach { java.io.File(dir, it).createNewFile() }
        assertEquals(State.PRESENT, check(mapOf(base to null), nativeDir = dir.path))
    }

    @Test
    fun emptyNativeLibraryDir_fallsBackToApks() {
        val dir = tmp.newFolder("lib")
        assertEquals(State.MISSING, check(mapOf(base to emptyList()), nativeDir = dir.path))
    }
}
