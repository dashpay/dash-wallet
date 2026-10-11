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

package de.schildbach.wallet.service.platform.sdk

import de.schildbach.wallet.WalletApplicationExt
import de.schildbach.wallet.util.WalletWipeSequence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Reset Wallet keeps the SDK's secrets DataStore (and with it the
 * device-scoped false-locked Keystore defect record, MO-1023) ONLY when the
 * SDK wallet-removal cascade verifiably left no wallet secret in it.
 * Otherwise the store is erased, and when nothing can erase it the wipe must
 * not complete.
 */
class SdkWalletSecretsWipeTest {

    private val walletId = "ab".repeat(32)
    private val pubkey = "02" + "cd".repeat(32)

    /** In-memory stand-in for the SDK's WalletStorage DataStore. */
    private class FakeStore(
        initial: Map<String, Any>,
        private val listFails: Boolean = false,
        private val deleteAllFails: Boolean = false
    ) : SdkSecretsStore {
        val entries = initial.toMutableMap()
        var deleteAllCalls = 0

        override suspend fun listEntryNames(): List<String> {
            if (listFails) throw IllegalStateException("datastore read failed")
            return entries.keys.sorted()
        }

        override suspend fun deleteAll() {
            deleteAllCalls++
            if (deleteAllFails) throw IOException("datastore write failed")
            entries.clear()
        }
    }

    /** What the old wallet left in the store before the wipe. */
    private fun oldWalletStore(
        listFails: Boolean = false,
        deleteAllFails: Boolean = false
    ) = FakeStore(
        mapOf(
            "masterkeylockdefect" to true,
            "mnemonic.$walletId" to "ciphertext",
            "mnemonicalias.$walletId" to "unbound",
            "privkey.$pubkey" to "ciphertext",
            "privkeyfp.$pubkey" to "fp",
            "privkeyalias.$pubkey" to "alias",
            "privkeyowners.$walletId" to setOf(pubkey)
        ),
        listFails,
        deleteAllFails
    )

    /** The SDK cascade's effect on the store: what PlatformWalletManager.removeWallet deletes. */
    private fun FakeStore.removeWalletEntries() {
        entries.keys.removeAll { it != "masterkeylockdefect" }
    }

    /**
     * Mirrors L1ShadowSyncService.clearForWalletWipe: the removal is
     * best-effort and its failure is swallowed, so the wipe goes on.
     */
    private fun clearForWalletWipe(remove: () -> Unit) {
        runCatching { remove() }
    }

    private class RawFile(private val deletable: Boolean = true) {
        var present = true
        var calls = 0
        fun delete(): Boolean {
            calls++
            if (deletable) present = false
            return !present
        }
    }

    private fun erase(store: () -> SdkSecretsStore, file: RawFile) = runBlocking {
        SdkWalletSecretsWipe.erase(openStore = store, rawDeleteFile = file::delete)
    }

    // ── removal succeeded ──────────────────────────────────────────────

    @Test
    fun `a clean SDK removal keeps the store and the defect record survives`() {
        val store = oldWalletStore()
        val file = RawFile()
        clearForWalletWipe { store.removeWalletEntries() }

        val outcome = erase({ store }, file)

        assertEquals(SdkSecretsWipeOutcome.VERIFIED_CLEAN_KEPT, outcome)
        assertTrue(outcome.erased)
        assertEquals(mapOf<String, Any>("masterkeylockdefect" to true), store.entries)
        assertEquals("verified clean must not clear the store", 0, store.deleteAllCalls)
        assertEquals("verified clean must not touch the file", 0, file.calls)
    }

    @Test
    fun `a store that never held a wallet is kept`() {
        val store = FakeStore(emptyMap())
        val outcome = erase({ store }, RawFile())
        assertEquals(SdkSecretsWipeOutcome.VERIFIED_CLEAN_KEPT, outcome)
        assertEquals(0, store.deleteAllCalls)
    }

    // ── removal failed or skipped ──────────────────────────────────────

    @Test
    fun `a removal that threw leaves secrets, so the whole store is erased through the live instance`() {
        val store = oldWalletStore()
        val file = RawFile()
        clearForWalletWipe { throw IllegalStateException("removeWallet failed") }

        val outcome = erase({ store }, file)

        assertEquals(SdkSecretsWipeOutcome.DELETED_ALL, outcome)
        assertTrue(outcome.erased)
        assertTrue("nothing of the old wallet may remain", store.entries.isEmpty())
        assertEquals("a live instance is cleared through its API, not the file", 0, file.calls)
    }

    @Test
    fun `a skipped removal (no bound id or unloaded wallet) erases the whole store`() {
        val store = oldWalletStore()
        // clearForWalletWipe never called removeSdkWallet.
        val outcome = erase({ store }, RawFile())
        assertEquals(SdkSecretsWipeOutcome.DELETED_ALL, outcome)
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `a partial removal that left one fingerprint still erases everything`() {
        val store = oldWalletStore()
        store.removeWalletEntries()
        store.entries["privkeyfp.$pubkey"] = "fp"
        assertEquals(SdkSecretsWipeOutcome.DELETED_ALL, erase({ store }, RawFile()))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `an entry the allowlist does not know counts as a secret`() {
        val store = FakeStore(mapOf("masterkeylockdefect" to true, "somefuturekey" to "x"))
        assertEquals(SdkSecretsWipeOutcome.DELETED_ALL, erase({ store }, RawFile()))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `a store that cannot be listed is erased rather than trusted`() {
        val store = oldWalletStore(listFails = true)
        assertEquals(SdkSecretsWipeOutcome.DELETED_ALL, erase({ store }, RawFile()))
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `with no store instance the file is raw-deleted`() {
        val file = RawFile()
        val outcome = erase({ throw IllegalStateException("no WalletStorage") }, file)
        assertEquals(SdkSecretsWipeOutcome.RAW_DELETED, outcome)
        assertTrue(outcome.erased)
        assertFalse(file.present)
    }

    // ── nothing can erase it ───────────────────────────────────────────

    @Test
    fun `no store and an undeletable file fails`() {
        val outcome = erase({ throw IllegalStateException("no WalletStorage") }, RawFile(deletable = false))
        assertEquals(SdkSecretsWipeOutcome.FAILED, outcome)
        assertFalse(outcome.erased)
    }

    @Test
    fun `a failed deleteAll fails even when the stopgap raw delete works`() {
        // Under a live instance its cache can write the old entries back.
        val store = oldWalletStore(deleteAllFails = true)
        val file = RawFile()
        val outcome = erase({ store }, file)
        assertEquals(SdkSecretsWipeOutcome.FAILED, outcome)
        assertEquals("the stopgap raw delete is still attempted", 1, file.calls)
    }

    @Test
    fun `when every erasure path fails the wipe is not completed and its marker stays`() {
        val store = oldWalletStore(listFails = true, deleteAllFails = true)
        val file = RawFile(deletable = false)
        var walletSourcesChecked = false
        var markedComplete = false

        try {
            runBlocking {
                WalletWipeSequence.finish(
                    pending = { true },
                    detachWallet = { },
                    destroy = {
                        clearForWalletWipe { throw IllegalStateException("removeWallet failed") }
                        val outcome = SdkWalletSecretsWipe.erase({ store }, file::delete)
                        WalletApplicationExt.confirmWipeDestroyed(outcome) { walletSourcesChecked = true }
                    },
                    markComplete = { markedComplete = true }
                )
            }
            fail("an unerased SDK secrets store must fail the wipe")
        } catch (expected: IOException) {
            // the marker stays: finish() rethrows and never marks complete
        }
        assertFalse("the wipe marker must be retained", markedComplete)
        assertTrue("the dashj source check still runs", walletSourcesChecked)
        assertTrue(store.entries.containsKey("mnemonic.$walletId"))
    }

    @Test
    fun `a verified-clean wipe completes`() {
        val store = oldWalletStore()
        var markedComplete = false
        val ran = runBlocking {
            WalletWipeSequence.finish(
                pending = { true },
                detachWallet = { },
                destroy = {
                    clearForWalletWipe { store.removeWalletEntries() }
                    val outcome = SdkWalletSecretsWipe.erase({ store }, RawFile()::delete)
                    WalletApplicationExt.confirmWipeDestroyed(outcome) { }
                },
                markComplete = { markedComplete = true }
            )
        }
        assertTrue(ran)
        assertTrue(markedComplete)
        assertEquals(setOf("masterkeylockdefect"), store.entries.keys)
    }

    @Test
    fun `a surviving dashj source still fails the wipe when the SDK secrets were erased`() {
        try {
            WalletApplicationExt.confirmWipeDestroyed(SdkSecretsWipeOutcome.DELETED_ALL) {
                throw IOException("wallet file survives")
            }
            fail("expected the dashj source failure")
        } catch (expected: IOException) {
            assertEquals("wallet file survives", expected.message)
        }
    }
}
