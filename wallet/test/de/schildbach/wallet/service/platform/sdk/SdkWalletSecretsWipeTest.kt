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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ── Wipe fence: old-wallet secret producers vs the erase ─────────────

    /** The new-key entry an outstanding old-wallet producer tries to write. */
    private val lateKey = "privkey.02" + "ef".repeat(32)

    /**
     * Reset Wallet's SDK-secrets step with the removal cascade FAILING (the
     * old wallet stays loaded, no tombstone): only the fenced deleteAll path
     * can erase. Returns whether the marker was cleared.
     */
    private suspend fun fencedWipeWithFailedCascade(
        fence: WalletSecretWriteFence,
        store: FakeStore,
        quiesceTimeoutMs: Long = 5_000L,
        outcomes: MutableList<SdkSecretsWipeOutcome> = mutableListOf(),
        quiesceSdk: suspend () -> Boolean = { true }
    ): Boolean {
        var markedComplete = false
        try {
            WalletWipeSequence.finish(
                pending = { true },
                detachWallet = { },
                destroy = {
                    val outcome = fencedSdkSecretsWipe(
                        fence = fence,
                        quiesceTimeoutMs = quiesceTimeoutMs,
                        teardown = { clearForWalletWipe { throw IllegalStateException("removeWallet failed") } },
                        quiesceSdk = quiesceSdk,
                        erase = { SdkWalletSecretsWipe.erase({ store }, RawFile()::delete) }
                    )
                    outcomes += outcome
                    WalletApplicationExt.confirmWipeDestroyed(outcome) { }
                },
                markComplete = { markedComplete = true }
            )
        } catch (expected: IOException) {
            // marker retained
        }
        return markedComplete
    }

    @Test
    fun `an untracked old-wallet key write released after the erase is refused and the store stays empty`() =
        runBlocking {
            val fence = WalletSecretWriteFence()
            val store = oldWalletStore()
            val reachedWrite = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            // E.g. a creation suspended in its preview / auto-resume back-off,
            // not registered with the fence: it holds no permit yet.
            val producer = async(Dispatchers.Default) {
                reachedWrite.complete(Unit)
                release.await()
                runCatching {
                    fence.withSecretWritePermit("identity private key store") { store.entries[lateKey] = "ciphertext" }
                }
            }
            reachedWrite.await()
            val outcomes = mutableListOf<SdkSecretsWipeOutcome>()

            val markerCleared = fencedWipeWithFailedCascade(fence, store, outcomes = outcomes)
            release.complete(Unit)
            val write = producer.await()

            assertEquals(listOf(SdkSecretsWipeOutcome.DELETED_ALL), outcomes)
            assertTrue("the fence held, so the wipe may complete", markerCleared)
            assertTrue(
                "the late write must be refused by the fence, got $write",
                write.exceptionOrNull() is WalletWipeInProgressException
            )
            assertTrue("no old-wallet secret may land after the erase", store.entries.isEmpty())
        }

    @Test
    fun `a tracked old-wallet producer is cancelled and joined before the erase`() = runBlocking {
        val fence = WalletSecretWriteFence()
        val store = oldWalletStore()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val producer = launch(Dispatchers.Default) {
            started.complete(Unit)
            release.await() // e.g. the auto-resume back-off
            fence.withSecretWritePermit("identity private key store") { store.entries[lateKey] = "ciphertext" }
        }
        fence.track(producer)
        started.await()

        val markerCleared = fencedWipeWithFailedCascade(fence, store)
        release.complete(Unit)
        producer.join()

        assertTrue(producer.isCancelled)
        assertTrue(markerCleared)
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `a key write already in flight when the wipe starts finishes before the erase`() = runBlocking {
        val fence = WalletSecretWriteFence()
        val store = oldWalletStore()
        val holdingPermit = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val producer = async(Dispatchers.Default) {
            fence.withSecretWritePermit("identity private key store") {
                holdingPermit.complete(Unit)
                // A native write that does not observe cancellation.
                withContext(NonCancellable) { release.await() }
                store.entries[lateKey] = "ciphertext"
            }
        }
        holdingPermit.await()

        val wipe = async(Dispatchers.Default) { fencedWipeWithFailedCascade(fence, store) }
        // The wipe waits on the held permit rather than erasing under it.
        assertNull(withTimeoutOrNull(200) { wipe.await() })
        release.complete(Unit)
        producer.await()

        assertTrue(wipe.await())
        assertTrue("the in-flight write landed BEFORE the erase, so it is gone", store.entries.isEmpty())
    }

    @Test
    fun `a producer that ignores cancellation past the budget keeps the wipe marker`() = runBlocking {
        val fence = WalletSecretWriteFence()
        val store = oldWalletStore()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val stubborn = launch(Dispatchers.Default) {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
            }
        }
        // Inside its non-cancellable section before the wipe cancels it: a job
        // cancelled before it ever ran would complete at once (test race).
        started.await()
        fence.track(stubborn)
        val outcomes = mutableListOf<SdkSecretsWipeOutcome>()

        val markerCleared = fencedWipeWithFailedCascade(fence, store, quiesceTimeoutMs = 100L, outcomes = outcomes)

        assertEquals("an unfenced erase counts as not erased", listOf(SdkSecretsWipeOutcome.FAILED), outcomes)
        assertFalse("the wipe marker must be retained", markerCleared)
        assertTrue("the erase still ran", store.deleteAllCalls == 1 && store.entries.isEmpty())
        assertTrue("the fence stays raised", fence.isRaised)
        release.complete(Unit)
        stubborn.join()
    }

    // ── SDK quiescence: SDK-owned persistence vs the erase ───────────────

    /**
     * Stand-in for the SDK runtime, stopped through the PRODUCTION
     * [stopSdkRuntimeForWalletWipe] exactly as DashSdkServiceImpl
     * .quiesceForWalletWipe drives it.
     *
     * Persistence model (int27 `PlatformWalletPersistenceHandler` +
     * `WalletStorage.storeIfAbsent`): the deriver can persist for a wallet as
     * long as the manager is open and the wallet id is not tombstoned —
     * whether or not the wallet is still in the loaded map (removeWallet drops
     * the map entry BEFORE its fallible secret delete and tombstone). It
     * derives outside the private-key lock and re-checks the tombstone under
     * it. Closing the manager drains every in-flight callback (native
     * shutdown waits for them) and no callback fires afterwards.
     */
    private class FakeSdkRuntime(
        walletLoaded: Boolean,
        private val walletIdHex: String,
        private val events: MutableList<String>,
        private val stopSucceeds: Boolean = true,
        private val closeThrows: Boolean = false,
        private val quiesceTimeoutMs: Long = 5_000L
    ) {
        val loadedWallets = mutableSetOf<String>().apply { if (walletLoaded) add(walletIdHex) }
        val removalTargets = WalletWipeRemovalTargets()
        var tombstoned = mutableSetOf<String>()
        var running = true
        var managerOpen = true
        var restartsRefused = false
        val closeStarted = CompletableDeferred<Unit>()
        private val inFlight = MutableStateFlow(0)
        private val lock = Mutex()

        /** Mirrors DashSdkServiceImpl.quiesceForWalletWipe (+ its locked body). */
        suspend fun quiesceForWalletWipe(): Boolean {
            events += "sdk-quiesce"
            restartsRefused = true
            return withTimeoutOrNull(quiesceTimeoutMs) {
                if (!running) return@withTimeoutOrNull true
                val targets = LinkedHashSet<String>(loadedWallets).apply { addAll(removalTargets.snapshot()) }
                running = false
                val closed = stopSdkRuntimeForWalletWipe(
                    walletIdsHex = targets,
                    tombstone = { id ->
                        lock.withLock { tombstoned += id.joinToString("") { "%02x".format(it) } }
                        events += "tombstone"
                    },
                    closeManager = {
                        events += "close"
                        closeStarted.complete(Unit)
                        if (closeThrows) throw IllegalStateException("native destroy failed")
                        if (!stopSucceeds) return@stopSdkRuntimeForWalletWipe false
                        inFlight.first { it == 0 } // native shutdown drains the callbacks
                        managerOpen = false
                        true
                    },
                    closeRest = { }
                )
                if (closed) removalTargets.clear()
                closed
            } ?: false
        }

        /** The SDK cascade (PlatformWalletManager.removeWallet), called through removeAppWallet. */
        fun removeWallet(store: FakeStore, secretDeleteFails: Boolean) {
            removalTargets.record(walletIdHex) // removeAppWallet records it before the cascade
            loadedWallets -= walletIdHex // wrapper map dropped first
            if (secretDeleteFails) throw IllegalStateException("secret deletion failed")
            store.entries.keys.removeAll { it != "masterkeylockdefect" } // the removed wallet's secrets
            tombstoned += walletIdHex
        }

        /**
         * The persistence deriver's `storeIfAbsent` for [ownerHex]: false when
         * refused (manager closed, or the wallet tombstoned before or while
         * [derive] ran outside the lock).
         */
        suspend fun deriverStore(
            store: FakeStore,
            key: String,
            ownerHex: String = walletIdHex,
            derive: suspend () -> Unit = { }
        ): Boolean {
            if (!managerOpen) return false // no callback fires after the native destroy
            inFlight.update { it + 1 }
            try {
                if (lock.withLock { ownerHex in tombstoned }) return false
                derive()
                return lock.withLock {
                    if (ownerHex in tombstoned) {
                        false
                    } else {
                        store.entries[key] = "ciphertext"
                        true
                    }
                }
            } finally {
                inFlight.update { it - 1 }
            }
        }

        fun restart(): Boolean {
            if (restartsRefused) return false
            if (!running) {
                // A fresh bootstrap: new manager, new WalletStorage (tombstones
                // are per instance, process-lifetime only).
                running = true
                managerOpen = true
                tombstoned = mutableSetOf()
            }
            return true
        }

        /** WalletSecretWriteFence.lower() after a completed wipe. */
        fun lower() {
            restartsRefused = false
        }
    }

    private suspend fun fencedWipe(
        store: FakeStore,
        runtime: FakeSdkRuntime,
        events: MutableList<String>,
        cascadeSucceeds: Boolean,
        quiesceSdk: suspend () -> Boolean = { runtime.quiesceForWalletWipe() }
    ): Pair<SdkSecretsWipeOutcome?, Boolean> {
        var outcome: SdkSecretsWipeOutcome? = null
        var markedComplete = false
        try {
            WalletWipeSequence.finish(
                pending = { true },
                detachWallet = { },
                destroy = {
                    outcome = fencedSdkSecretsWipe(
                        fence = WalletSecretWriteFence(),
                        quiesceTimeoutMs = 5_000L,
                        teardown = {
                            events += "teardown"
                            clearForWalletWipe {
                                runtime.removeWallet(store, secretDeleteFails = !cascadeSucceeds)
                            }
                        },
                        quiesceSdk = quiesceSdk,
                        erase = {
                            events += "erase"
                            SdkWalletSecretsWipe.erase({ store }, RawFile()::delete)
                        }
                    )
                    WalletApplicationExt.confirmWipeDestroyed(checkNotNull(outcome)) { }
                },
                markComplete = { markedComplete = true }
            )
        } catch (expected: IOException) {
            // marker retained
        }
        return outcome to markedComplete
    }

    @Test
    fun `the fake models the hazard - a wallet dropped from the loaded map still persists until tombstoned or closed`() =
        runBlocking {
            val store = oldWalletStore()
            val runtime = FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = mutableListOf())

            runCatching { runtime.removeWallet(store, secretDeleteFails = true) }

            assertTrue("no wallet loaded any more", runtime.loadedWallets.isEmpty())
            assertTrue(
                "an unloaded, un-tombstoned wallet's deriver can still persist while the manager is open",
                runtime.deriverStore(store, lateKey)
            )
        }

    @Test
    fun `a removal that fails after dropping the wallet from the map still tombstones and drains before the erase`() =
        runBlocking {
            val events = mutableListOf<String>()
            val store = oldWalletStore()
            val runtime = FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = events)
            // A deriver that acquired its scalar before the wipe and is about to
            // store it (the delayed storeIfAbsent the review describes).
            val derived = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val deriver = async(Dispatchers.Default) {
                runtime.deriverStore(store, lateKey) {
                    derived.complete(Unit)
                    release.await()
                }
            }
            derived.await()

            val wipe = async(Dispatchers.Default) { fencedWipe(store, runtime, events, cascadeSucceeds = false) }
            // The close is draining: the wallet is already tombstoned, so the
            // deriver's store — released now — is refused, and the close
            // cannot finish (nor the erase start) before it returned.
            runtime.closeStarted.await()
            assertFalse("the erase waits for the drain", "erase" in events)
            release.complete(Unit)
            val (outcome, markerCleared) = wipe.await()

            assertFalse("the outstanding deriver's store was refused", deriver.await())
            assertEquals(listOf("teardown", "sdk-quiesce", "tombstone", "close", "erase"), events)
            assertEquals(SdkSecretsWipeOutcome.DELETED_ALL, outcome)
            assertTrue("the close succeeded, so the wipe may complete", markerCleared)
            assertFalse("the manager was closed", runtime.managerOpen)
            assertFalse("no SDK callback fires after the close", runtime.deriverStore(store, lateKey))
            assertFalse("SDK restarts are refused during the wipe", runtime.restart())
            assertTrue("no old-wallet secret may land after the erase", store.entries.isEmpty())
        }

    @Test
    fun `a failed cascade stops the still-running SDK before the erase so its deriver cannot write after it`() =
        runBlocking {
            val events = mutableListOf<String>()
            val store = oldWalletStore()
            val runtime = FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = events)

            val (outcome, markerCleared) = fencedWipe(store, runtime, events, cascadeSucceeds = false)

            assertEquals(listOf("teardown", "sdk-quiesce", "tombstone", "close", "erase"), events)
            assertEquals(SdkSecretsWipeOutcome.DELETED_ALL, outcome)
            assertTrue(markerCleared)
            assertFalse("the runtime was stopped", runtime.running)
            assertFalse(runtime.deriverStore(store, lateKey))
            assertFalse("SDK restarts are refused", runtime.restart())
            assertFalse(runtime.deriverStore(store, lateKey))
            assertTrue("no old-wallet secret may land after the erase", store.entries.isEmpty())
        }

    @Test
    fun `an SDK runtime that cannot be stopped keeps the wipe marker`() = runBlocking {
        for (runtimeOf in listOf<(MutableList<String>) -> FakeSdkRuntime>(
            { FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = it, stopSucceeds = false) },
            { FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = it, closeThrows = true) }
        )) {
            val events = mutableListOf<String>()
            val store = oldWalletStore()
            val runtime = runtimeOf(events)

            val (outcome, markerCleared) = fencedWipe(store, runtime, events, cascadeSucceeds = false)

            assertEquals(SdkSecretsWipeOutcome.FAILED, outcome)
            assertFalse("the wipe marker must be retained", markerCleared)
            assertTrue("the erase still ran", store.deleteAllCalls == 1)
        }
    }

    @Test
    fun `a manager close that cannot drain within the budget keeps the wipe marker`() = runBlocking {
        val events = mutableListOf<String>()
        val store = oldWalletStore()
        val runtime =
            FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = events, quiesceTimeoutMs = 200L)
        val release = CompletableDeferred<Unit>()
        val derived = CompletableDeferred<Unit>()
        // A callback stuck in native code: shutdown cannot drain it in time.
        val stuck = async(Dispatchers.Default) {
            runtime.deriverStore(store, lateKey) {
                derived.complete(Unit)
                release.await()
            }
        }
        derived.await()

        val (outcome, markerCleared) = fencedWipe(store, runtime, events, cascadeSucceeds = false)

        assertEquals(SdkSecretsWipeOutcome.FAILED, outcome)
        assertFalse(markerCleared)
        release.complete(Unit)
        assertFalse("tombstoned before the close, so even the straggler is refused", stuck.await())
    }

    @Test
    fun `an SDK quiesce that times out or throws keeps the wipe marker`() = runBlocking {
        for (quiesce in listOf<suspend () -> Boolean>({ false }, { throw IllegalStateException("close failed") })) {
            val events = mutableListOf<String>()
            val store = oldWalletStore()
            val runtime = FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = events)

            val (outcome, markerCleared) =
                fencedWipe(store, runtime, events, cascadeSucceeds = false, quiesceSdk = quiesce)

            assertEquals(SdkSecretsWipeOutcome.FAILED, outcome)
            assertFalse(markerCleared)
        }
    }

    @Test
    fun `a clean cascade still closes the SDK and keeps the store and the defect record`() = runBlocking {
        val events = mutableListOf<String>()
        val store = oldWalletStore()
        val runtime = FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = events)

        val (outcome, markerCleared) = fencedWipe(store, runtime, events, cascadeSucceeds = true)

        assertEquals(listOf("teardown", "sdk-quiesce", "tombstone", "close", "erase"), events)
        assertEquals(SdkSecretsWipeOutcome.VERIFIED_CLEAN_KEPT, outcome)
        assertTrue(markerCleared)
        assertFalse("the runtime is always closed during a wipe", runtime.running)
        assertFalse(runtime.managerOpen)
        assertEquals(setOf("masterkeylockdefect"), store.entries.keys)
        assertEquals(0, store.deleteAllCalls)
    }

    @Test
    fun `after a completed wipe the next wallet setup re-bootstraps a fresh SDK runtime`() = runBlocking {
        val events = mutableListOf<String>()
        val store = oldWalletStore()
        val runtime = FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = events)

        val (_, markerCleared) = fencedWipe(store, runtime, events, cascadeSucceeds = false)
        assertTrue(markerCleared)
        assertFalse("halted until the fence is lowered", runtime.restart())

        runtime.lower()

        assertTrue("a fresh runtime is built", runtime.restart())
        assertTrue(runtime.managerOpen)
        assertTrue("the removal record was consumed by the successful close", runtime.removalTargets.snapshot().isEmpty())
        // Restoring the same seed reuses the same deterministic wallet id: the
        // fresh WalletStorage carries no tombstone for it.
        assertTrue(runtime.deriverStore(store, lateKey))
    }

    @Test
    fun `stopSdkRuntimeForWalletWipe tombstones every distinct id before the close and reports the close`() =
        runBlocking {
            val order = mutableListOf<String>()
            val other = "cd".repeat(32)
            val closed = stopSdkRuntimeForWalletWipe(
                walletIdsHex = listOf(walletId, walletId.uppercase(), other, "not-hex"),
                tombstone = { id ->
                    val hex = id.joinToString("") { "%02x".format(it) }
                    if (hex == other) throw IllegalStateException("tombstone failed")
                    order += "tombstone:$hex"
                },
                closeManager = {
                    order += "close"
                    true
                },
                closeRest = { order += "rest" }
            )

            assertTrue("a tombstone failure is not fatal — the drained close proves quiescence", closed)
            assertEquals(listOf("tombstone:$walletId", "close", "rest"), order)
        }

    @Test
    fun `stopSdkRuntimeForWalletWipe is false when the close throws or does not confirm, and still closes the rest`() =
        runBlocking {
            for (close in listOf<suspend () -> Boolean>({ false }, { throw IllegalStateException("destroy failed") })) {
                var restClosed = false
                val closed = stopSdkRuntimeForWalletWipe(
                    walletIdsHex = listOf(walletId),
                    tombstone = { },
                    closeManager = close,
                    closeRest = { restClosed = true }
                )
                assertFalse(closed)
                assertTrue(restClosed)
            }
        }

    @Test
    fun `an unproven SDK quiesce also fails a verified-clean outcome`() = runBlocking {
        val events = mutableListOf<String>()
        val store = oldWalletStore()
        val runtime = FakeSdkRuntime(walletLoaded = true, walletIdHex = walletId, events = events)

        val (outcome, markerCleared) =
            fencedWipe(store, runtime, events, cascadeSucceeds = true, quiesceSdk = { false })

        assertEquals(SdkSecretsWipeOutcome.FAILED, outcome)
        assertFalse(markerCleared)
    }
}
