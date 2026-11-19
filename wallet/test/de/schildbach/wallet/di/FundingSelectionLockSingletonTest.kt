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

package de.schildbach.wallet.di

import de.schildbach.wallet.payments.FundingSelectionLock
import de.schildbach.wallet.payments.PendingDirectPaymentVerifier
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.service.platform.sdk.SdkL1SendService
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reset Wallet raises the wipe barrier on ONE [FundingSelectionLock]: the Hilt singleton
 * (`WalletApplication.fundingSelectionLock`). Every payment path must hold that same instance; a
 * private one is never raised, so its sends and bridges run on through a wipe.
 */
class FundingSelectionLockSingletonTest {

    private fun field(owner: Any, name: String): Any? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it.get(owner)
            }
            type = type.superclass
        }
        throw AssertionError("${owner.javaClass.name} has no field $name")
    }

    @Test
    fun theLockIsOneHiltSingleton() {
        assertNotNull(FundingSelectionLock::class.java.getAnnotation(Singleton::class.java))
        assertTrue(
            "provided only through its @Inject constructor",
            FundingSelectionLock::class.java.constructors.single().isAnnotationPresent(Inject::class.java)
        )
    }

    @Test
    fun theProvidedSendServiceHoldsTheSingletonLock() {
        val lock = FundingSelectionLock()
        val provided = AppModule.provideSendPaymentService(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), lock
        )
        // non-prod flavors wrap the runner in the DashSpend test double
        val runner = provided as? SendCoinsTaskRunner ?: field(provided, "realService") as SendCoinsTaskRunner
        assertSame(lock, field(runner, "fundingSelection"))
    }

    @Test
    fun theRunnerTheVerifierAndTheSdkServiceShareTheInjectedInstance() {
        val lock = FundingSelectionLock()
        val verifier = PendingDirectPaymentVerifier(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), lock
        )
        val sdk = SdkL1SendService(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), { verifier }, lock
        )
        val provided = AppModule.provideSendPaymentService(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), sdk, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), verifier, lock
        )
        val runner = provided as? SendCoinsTaskRunner ?: field(provided, "realService") as SendCoinsTaskRunner

        assertSame(lock, field(verifier, "fundingSelection"))
        assertSame(lock, field(sdk, "fundingSelection"))
        assertSame(lock, field(runner, "fundingSelection"))
    }

    /**
     * The systemic guard: no production source constructs a lock of its own or declares a
     * default instance for one. The singleton's @Inject constructor is the only production
     * construction; tests pass theirs explicitly.
     */
    @Test
    fun noProductionSourceMakesALockOfItsOwn() {
        // unit tests run in the wallet module's directory
        val roots = listOf("src", "../common/src/main", "../features", "../integrations")
            .map { File(it) }
            .filter { it.isDirectory }
        assertTrue("wallet sources not found from ${File(".").absolutePath}", roots.first().path == "src")
        val construction = Regex("""\bFundingSelectionLock\s*\(\s*\)""")
        val defaultValue = Regex("""FundingSelectionLock\s*=""")
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filterNot { it.path.contains("/test/") || it.path.contains("/androidTest/") || it.path.contains("/build/") }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        val code = line.trimStart()
                        if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return@mapIndexedNotNull null
                        if (construction.containsMatchIn(line) || defaultValue.containsMatchIn(line)) {
                            "${file.path}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }
                .toList()
        }
        assertEquals("a private FundingSelectionLock is one no wipe raises", emptyList<String>(), offenders)
    }
}
