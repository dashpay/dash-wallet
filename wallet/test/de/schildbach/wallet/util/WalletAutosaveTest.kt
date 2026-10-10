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

package de.schildbach.wallet.util

import de.schildbach.wallet.util.WalletAutosave.Companion.DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS
import org.bitcoinj.core.Context
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WalletAutosaveTest {

    @get:Rule val directory = TemporaryFolder()

    private val params = TestNet3Params.get()
    private lateinit var wallet: Wallet
    private lateinit var walletFile: File
    private lateinit var autosave: WalletAutosave

    @Before
    fun setUp() {
        Context.propagate(Context.getOrCreate(params))
        wallet = Wallet.createDeterministic(params, Script.ScriptType.P2PKH)
        walletFile = File(directory.root, "wallet-protobuf")
        autosave = WalletAutosave()
    }

    // ── the debounce decision ────────────────────────────────────────

    @Test
    fun `diagnostic constant is ten minutes`() {
        assertEquals(TimeUnit.MINUTES.toMillis(10), DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS)
    }

    @Test
    fun `pre-cutover and held keep every size tier unchanged`() {
        for (tier in sizeTiers) {
            assertEquals(tier, WalletAutosave.autosaveDelayMs(tier, dashjDiagnosticSync = false))
        }
    }

    @Test
    fun `diagnostic raises every size tier to ten minutes`() {
        for (tier in sizeTiers) {
            val delayMs = WalletAutosave.autosaveDelayMs(tier, dashjDiagnosticSync = true)
            assertEquals(DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS, delayMs)
        }
    }

    @Test
    fun `diagnostic never shortens a debounce that is already longer`() {
        val longer = DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS * 2
        assertEquals(longer, WalletAutosave.autosaveDelayMs(longer, dashjDiagnosticSync = true))
    }

    // ── arming ───────────────────────────────────────────────────────

    @Test
    fun `nothing armed reports no debounce and reconcile is a no-op`() {
        assertNull(autosave.armedDelayMillis)
        autosave.requestDashjDiagnostic(true)
        assertFalse(autosave.reconcile())
        assertNull(autosave.armedDelayMillis)
    }

    @Test
    fun `arm without the diagnostic uses the size tier`() {
        assertEquals(RISKY_TIER, autosave.arm(wallet, walletFile, RISKY_TIER))
        assertEquals(RISKY_TIER, autosave.armedDelayMillis)
    }

    @Test
    fun `arm after the diagnostic was requested uses the diagnostic debounce`() {
        autosave.requestDashjDiagnostic(true)
        assertEquals(DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS, autosave.arm(wallet, walletFile, RISKY_TIER))
        assertFalse("already right — nothing to switch", autosave.reconcile())
    }

    // ── switching at runtime ─────────────────────────────────────────

    @Test
    fun `turning the diagnostic on and off switches the debounce, with exactly one autosaver`() {
        autosave.arm(wallet, walletFile, RISKY_TIER)

        autosave.requestDashjDiagnostic(true)
        assertTrue(autosave.reconcile())
        assertEquals(DASHJ_DIAGNOSTIC_AUTOSAVE_DELAY_MS, autosave.armedDelayMillis)
        assertOneAutosaverArmed()

        assertFalse("same mode again is a no-op", autosave.reconcile())

        autosave.requestDashjDiagnostic(false)
        assertTrue(autosave.reconcile())
        assertEquals(RISKY_TIER, autosave.armedDelayMillis)
        assertOneAutosaverArmed()
    }

    @Test
    fun `the last request wins`() {
        autosave.arm(wallet, walletFile, RISKY_TIER)
        autosave.requestDashjDiagnostic(true)
        autosave.requestDashjDiagnostic(false)
        assertFalse(autosave.reconcile())
        assertEquals(RISKY_TIER, autosave.armedDelayMillis)
    }

    @Test
    fun `a switch re-queues the save it may have cancelled`() {
        // Diagnostic armed: any save the wallet queues waits ten minutes.
        autosave.requestDashjDiagnostic(true)
        autosave.arm(wallet, walletFile, SHORT_TIER)
        assertFalse(walletFile.exists())

        // Leaving the diagnostic cancels that pending save; the re-armed
        // autosaver must write the wallet on its own short debounce.
        autosave.requestDashjDiagnostic(false)
        assertTrue(autosave.reconcile())

        assertTrue("the wallet must be saved after the switch", awaitFile(walletFile, 10_000))
    }

    @Test
    fun `switching while the wallet saves continuously neither deadlocks nor leaves two autosavers`() {
        autosave.arm(wallet, walletFile, SHORT_TIER)
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        // freshReceiveKey() makes dashj save immediately (saveNow) on this
        // thread, under the wallet lock — racing every switch below.
        val context = Context.get()
        val writer = Thread {
            Context.propagate(context)
            try {
                while (!stop.get()) wallet.freshReceiveKey()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        writer.start()
        val switcher = Thread {
            Context.propagate(context)
            try {
                repeat(20) { i ->
                    autosave.requestDashjDiagnostic(i % 2 == 0)
                    autosave.reconcile()
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        switcher.start()
        switcher.join(60_000)
        stop.set(true)
        writer.join(10_000)

        assertFalse("switching deadlocked", switcher.isAlive)
        assertFalse("wallet writer deadlocked", writer.isAlive)
        failure.get()?.let { throw AssertionError("concurrent switch failed", it) }
        // 20 switches starting with "on" end on "off".
        assertEquals(SHORT_TIER, autosave.armedDelayMillis)
        assertOneAutosaverArmed()
    }

    // ── stopping ─────────────────────────────────────────────────────

    @Test
    fun `shutdown stops the armed autosave and forgets it`() {
        autosave.arm(wallet, walletFile, RISKY_TIER)
        autosave.shutdown(wallet)

        assertNull(autosave.armedDelayMillis)
        autosave.requestDashjDiagnostic(true)
        assertFalse("nothing to re-arm after shutdown", autosave.reconcile())
        // dashj itself no longer autosaves this wallet.
        assertThrows(IllegalStateException::class.java) { wallet.shutdownAutosaveAndWait() }
    }

    @Test
    fun `shutdown of a wallet that never autosaved throws as dashj does`() {
        assertThrows(IllegalStateException::class.java) { autosave.shutdown(wallet) }
    }

    /** dashj refuses a second autosaver: the probe arm must fail while ours is live. */
    private fun assertOneAutosaverArmed() {
        assertThrows(IllegalStateException::class.java) {
            wallet.autosaveToFile(File(directory.root, "probe"), 1, TimeUnit.SECONDS, null)
        }
    }

    private fun awaitFile(file: File, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.length() > 0) return true
            Thread.sleep(50)
        }
        return false
    }

    private companion object {
        const val SHORT_TIER = 100L
        const val RISKY_TIER = WalletFileSizeGuard.AUTOSAVE_DELAY_RISKY_MS
        val sizeTiers = listOf(
            WalletFileSizeGuard.AUTOSAVE_DELAY_DEFAULT_MS,
            WalletFileSizeGuard.AUTOSAVE_DELAY_LARGE_MS,
            WalletFileSizeGuard.AUTOSAVE_DELAY_RISKY_MS
        )
    }
}
