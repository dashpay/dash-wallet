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

import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bitcoinj.wallet.Wallet
import de.schildbach.wallet.data.WalletData
import org.dashfoundation.dashsdk.Sdk
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for the Phase 3f production wiring: the
 * [SdkWalletBinder] eligibility decision table, single-flight /
 * idempotency semantics, partial-progress retries, and — most importantly
 * — the inertness contract: with both `USE_KOTLIN_SDK_*` flags off the
 * binder performs ZERO interactions with the SDK service, the mnemonic
 * provider, and the identity config. No native calls; [DashSdkService]
 * and [PlatformMnemonicProvider] are faked.
 */
class SdkWalletBinderTest {

    private val walletId = "cd".repeat(32)
    private val userId = Identifier.from(ByteArray(32) { 7 }).toString()
    private val words = listOf("abandon", "abandon", "about")
    private val unlock = WalletUnlock.Unencrypted

    /** Programmable [DashSdkService] fake with interaction counters. */
    private class FakeSdkService(
        var onBind: suspend (List<String>, Long?) -> String = { _, _ -> error("unexpected bind") },
        var managed: (String, ByteArray) -> Boolean = { _, _ -> false },
        var onDiscover: suspend (String, Int) -> List<ByteArray> = { _, _ -> emptyList() },
        /** Default: every key already signable (heal settled, latch allowed). */
        var onHealKeys: suspend (String, ByteArray) -> IdentityKeyHealReport = { _, _ ->
            IdentityKeyHealReport(keysChecked = 4, healthy = 4, repaired = 0, watchOnly = 0, failed = 0)
        }
    ) : DashSdkService {
        var bindCalls = 0
        var managedCalls = 0
        var discoverCalls = 0
        var healCalls = 0
        var lastBirthTime: Long? = null
        var lastStartIndex: Int? = null
        var lastIdentityId: ByteArray? = null
        var lastHealedIdentityId: ByteArray? = null
        var lastHealedWalletId: String? = null
        val totalCalls get() = bindCalls + managedCalls + discoverCalls + healCalls

        override val isStarted = false
        override suspend fun ensureStarted() = Unit
        override suspend fun stop() = Unit
        override fun sdkOrNull(): Sdk? = null
        override fun databaseOrNull(): org.dashfoundation.dashsdk.persistence.DashDatabase? = null
        override fun walletManagerOrNull(): PlatformWalletManager? = null
        override suspend fun resolveUsername(name: String): String? = null

        override suspend fun bindAppWallet(seedWords: List<String>, birthTimeSecs: Long?): String {
            bindCalls++
            lastBirthTime = birthTimeSecs
            return onBind(seedWords, birthTimeSecs)
        }

        override suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean {
            managedCalls++
            lastIdentityId = identityId
            return managed(walletIdHex, identityId)
        }

        override suspend fun discoverIdentities(walletIdHex: String, startIndex: Int): List<ByteArray> {
            discoverCalls++
            lastStartIndex = startIndex
            return onDiscover(walletIdHex, startIndex)
        }

        override suspend fun ensureIdentityKeysSignable(
            walletIdHex: String,
            identityId: ByteArray
        ): IdentityKeyHealReport {
            healCalls++
            lastHealedWalletId = walletIdHex
            lastHealedIdentityId = identityId
            return onHealKeys(walletIdHex, identityId)
        }

        var removeCalls = 0
        val removedWallets = mutableListOf<String>()
        override suspend fun removeAppWallet(walletIdHex: String) {
            removeCalls++
            removedWallets.add(walletIdHex)
        }

        /** Loaded-wallet snapshot the orphan prune (bindLocked step 4b) scans. */
        var loadedWallets: Set<String> = emptySet()
        override fun loadedWalletIds(): Set<String> = loadedWallets

        var provisionCalls = 0
        var lastProvisionWalletId: String? = null
        var onProvision: suspend (String) -> DashPayContactProvisionReport = { _ ->
            DashPayContactProvisionReport(
                bound = true, syncSuccess = 0, syncErrors = 0, pendingBefore = 0, drainScheduled = false
            )
        }
        override suspend fun provisionDashPayContactAccounts(
            walletIdHex: String
        ): DashPayContactProvisionReport {
            provisionCalls++
            lastProvisionWalletId = walletIdHex
            return onProvision(walletIdHex)
        }
    }

    private class FakeMnemonicProvider(
        var onGet: suspend (WalletUnlock) -> List<String>
    ) : PlatformMnemonicProvider {
        var calls = 0
        var lastUnlock: WalletUnlock? = null
        override suspend fun getMnemonicWords(unlock: WalletUnlock): List<String> {
            calls++
            lastUnlock = unlock
            return onGet(unlock)
        }
    }

    private fun identityBase(
        creationState: IdentityCreationState = IdentityCreationState.DONE,
        userId: String? = this.userId
    ) = BlockchainIdentityBaseData(
        creationState = creationState,
        creationStateErrorMessage = null,
        username = null,
        usernameSecondary = null,
        userId = userId,
        restoring = false
    )

    private fun identityConfig(base: BlockchainIdentityBaseData): BlockchainIdentityConfig =
        mockk { coEvery { loadBase() } returns base }

    private fun dashPayConfig(
        readsFlag: Boolean?,
        writesFlag: Boolean? = false,
        shieldedFlag: Boolean? = false,
        l1ShadowFlag: Boolean? = false
    ): DashPayConfig = mockk {
        if (readsFlag == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DPNS_READS) } returns readsFlag
        }
        if (writesFlag == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) } returns writesFlag
        }
        if (shieldedFlag == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) } returns shieldedFlag
        }
        if (l1ShadowFlag == null) {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } throws
                IllegalStateException("datastore unavailable")
        } else {
            coEvery { get(DashPayConfig.USE_KOTLIN_SDK_L1_SHADOW) } returns l1ShadowFlag
        }
    }

    private fun walletData(): WalletData = mockk { every { wallet } returns null }

    /**
     * A dashj wallet whose watching key hashes to a fingerprint derived
     * from [seedByte] — two wallets fingerprint equal iff their seed bytes
     * match, mirroring the deterministic seed → watching-key relationship
     * the binder's latch revalidation relies on.
     */
    private fun walletWithFingerprint(seedByte: Byte): Wallet = mockk {
        every { watchingKey.pubKeyHash } returns ByteArray(20) { seedByte }
        every { earliestKeyCreationTime } returns 0L
    }

    /** A binder whose collaborators are all in the happy-path state. */
    private fun binder(
        sdk: FakeSdkService,
        mnemonic: FakeMnemonicProvider = FakeMnemonicProvider { words },
        identity: BlockchainIdentityConfig = identityConfig(identityBase()),
        config: DashPayConfig = dashPayConfig(readsFlag = true),
        supportsPlatform: Boolean = true,
        walletData: WalletData = walletData(),
        now: () -> Long = { System.currentTimeMillis() },
        scope: CoroutineScope
    ) = SdkWalletBinder(
        sdkService = sdk,
        mnemonicProvider = mnemonic,
        identityConfig = identity,
        dashPayConfig = config,
        walletData = walletData,
        scope = scope,
        supportsPlatform = { supportsPlatform },
        now = now
    )

    /** SDK fake in the first-bind happy path: bind ok, discovery attaches. */
    private fun readySdk(): FakeSdkService {
        val sdk = FakeSdkService()
        sdk.onBind = { _, _ -> walletId }
        // Not managed until a discovery scan has run.
        sdk.managed = { _, _ -> sdk.discoverCalls > 0 }
        sdk.onDiscover = { _, _ -> listOf(Identifier.from(userId).toBuffer()) }
        return sdk
    }

    // ── Inertness: the default-off contract ──────────────────────────────

    @Test
    fun bothFlagsOff_zeroInteractions() = runBlocking {
        val sdk = FakeSdkService()
        val mnemonic = FakeMnemonicProvider { error("must not be called") }
        val identity = identityConfig(identityBase())
        val binder = binder(
            sdk, mnemonic, identity,
            config = dashPayConfig(readsFlag = false, writesFlag = false),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(0, sdk.totalCalls)
        assertEquals(0, mnemonic.calls)
        coVerify(exactly = 0) { identity.loadBase() }
    }

    @Test
    fun flagReadFailure_treatedAsOff_zeroInteractions() = runBlocking {
        val sdk = FakeSdkService()
        val mnemonic = FakeMnemonicProvider { error("must not be called") }
        val binder = binder(
            sdk, mnemonic,
            config = dashPayConfig(readsFlag = null, writesFlag = null),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(0, sdk.totalCalls)
        assertEquals(0, mnemonic.calls)
    }

    @Test
    fun platformNotSupported_zeroSdkInteractions() = runBlocking {
        val sdk = FakeSdkService()
        val mnemonic = FakeMnemonicProvider { error("must not be called") }
        val binder = binder(sdk, mnemonic, supportsPlatform = false, scope = this)

        binder.bindIfEnabled(unlock)

        assertEquals(0, sdk.totalCalls)
        assertEquals(0, mnemonic.calls)
    }

    @Test
    fun noPlatformIdentity_zeroSdkInteractions_unlockNeverRequested() = runBlocking {
        val sdk = FakeSdkService()
        val mnemonic = FakeMnemonicProvider { error("must not be called") }
        var unlockRequested = false
        val binder = binder(
            sdk, mnemonic,
            identity = identityConfig(identityBase(IdentityCreationState.NONE, userId = null)),
            scope = this
        )

        binder.bindIfEnabled {
            unlockRequested = true
            unlock
        }

        assertEquals(0, sdk.totalCalls)
        assertEquals(0, mnemonic.calls)
        assertTrue(!unlockRequested)
    }

    @Test
    fun noPlatformIdentity_shieldedFlagOn_bindsWallet_discoveryDeferred() = runBlocking {
        // The fresh-wallet shielded path (fund → shield → create identity
        // from the pool) needs a bound wallet BEFORE any identity exists.
        val sdk = readySdk()
        val binder = binder(
            sdk,
            identity = identityConfig(identityBase(IdentityCreationState.NONE, userId = null)),
            config = dashPayConfig(readsFlag = false, writesFlag = false, shieldedFlag = true),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(1, sdk.bindCalls)
        assertEquals(0, sdk.discoverCalls) // no id to attach — binding-only

        // Not latched: once an identity id lands, a later trigger attaches it.
        binder.bindIfEnabled(unlock)
        assertEquals(1, sdk.bindCalls) // wallet bind is cached in-process
    }

    @Test
    fun noPlatformIdentity_onlyShieldedFlagOn_passesTheFlagGateToo() = runBlocking {
        // The shielded flag alone must open BOTH gates (any-flag + identity)
        // or a shielded-only configuration could never bind at all.
        val sdk = readySdk()
        val mnemonic = FakeMnemonicProvider { words }
        val binder = binder(
            sdk, mnemonic,
            identity = identityConfig(identityBase(IdentityCreationState.NONE, userId = null)),
            config = dashPayConfig(readsFlag = false, writesFlag = false, shieldedFlag = true),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(1, mnemonic.calls)
        assertEquals(1, sdk.bindCalls)
    }

    @Test
    fun noPlatformIdentity_onlyL1ShadowFlagOn_bindsWallet_discoveryDeferred() = runBlocking {
        // The read-only L1 shadow scan requires a bound wallet and must run
        // on wallets with no platform identity at all — the mainnet
        // validation configuration (shadow flag on, everything else off).
        // The shadow flag alone must open BOTH gates (any-flag + identity).
        val sdk = readySdk()
        val mnemonic = FakeMnemonicProvider { words }
        val binder = binder(
            sdk, mnemonic,
            identity = identityConfig(identityBase(IdentityCreationState.NONE, userId = null)),
            config = dashPayConfig(
                readsFlag = false, writesFlag = false, shieldedFlag = false, l1ShadowFlag = true
            ),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(1, mnemonic.calls)
        assertEquals(1, sdk.bindCalls)
        assertEquals(0, sdk.discoverCalls) // no id to attach — binding-only
    }

    @Test
    fun orphanSdkWallets_prunedAfterBind_currentWalletKept() = runBlocking {
        // A Reset Wallet clears the app's stores but not the SDK's own
        // persistence — the manager can come up holding the OLD wallet next
        // to the new one, which nulls every singleOrNull() bound-wallet
        // lookup. The bind pass must remove the orphan and only the orphan.
        val sdk = readySdk()
        sdk.loadedWallets = setOf(walletId, "deadbeef00112233")
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock)

        assertEquals(listOf("deadbeef00112233"), sdk.removedWallets)
    }

    // ── The happy path: bind + discover + attach ─────────────────────────

    @Test
    fun eligible_bindsWallet_discoversFromIndexZero_attachesIdentity() = runBlocking {
        val sdk = readySdk()
        val mnemonic = FakeMnemonicProvider { words }
        val binder = binder(sdk, mnemonic, scope = this)

        binder.bindIfEnabled(unlock)

        assertEquals(1, sdk.bindCalls)
        assertEquals(1, mnemonic.calls)
        assertEquals(unlock, mnemonic.lastUnlock)
        assertEquals(1, sdk.discoverCalls)
        assertEquals(0, sdk.lastStartIndex)
        // No dashj wallet in the fake → unknown birth time.
        assertNull(sdk.lastBirthTime)
        // The identity probed is the one from BlockchainIdentityConfig.
        assertEquals(userId, Identifier.from(sdk.lastIdentityId!!).toString())
    }

    @Test
    fun writesFlagAloneIsSufficient() = runBlocking {
        val sdk = readySdk()
        val binder = binder(
            sdk,
            config = dashPayConfig(readsFlag = false, writesFlag = true),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(1, sdk.bindCalls)
    }

    @Test
    fun successLatch_secondCallIsNoOp() = runBlocking {
        val sdk = readySdk()
        val mnemonic = FakeMnemonicProvider { words }
        val binder = binder(sdk, mnemonic, scope = this)

        binder.bindIfEnabled(unlock)
        val callsAfterFirst = sdk.totalCalls
        binder.bindIfEnabled(unlock)

        assertEquals(callsAfterFirst, sdk.totalCalls)
        assertEquals(1, mnemonic.calls)
    }

    @Test
    fun resetForWalletRecreation_clearsTheLatch_andTheNextPassRebindsFromTheSeed() = runBlocking {
        // The shadow-recovery hook: after removeAppWallet destroyed the SDK
        // wallet, the reset must force the next pass through the FULL
        // first-bind path (seed hand-off + createWallet + discovery) rather
        // than latching on the stale bound id.
        val sdk = FakeSdkService()
        sdk.onBind = { _, _ -> walletId }
        var identityAttached = false // removeWallet's cascade detaches it
        sdk.managed = { _, _ -> identityAttached }
        sdk.onDiscover = { _, _ ->
            identityAttached = true
            listOf(Identifier.from(userId).toBuffer())
        }
        val mnemonic = FakeMnemonicProvider { words }
        val binder = binder(sdk, mnemonic, scope = this)

        binder.bindIfEnabled(unlock)
        assertEquals(1, sdk.bindCalls)
        assertEquals(1, mnemonic.calls)

        identityAttached = false // the wallet (and its identity rows) got removed
        binder.resetForWalletRecreation()
        binder.bindIfEnabled(unlock)

        assertEquals(2, sdk.bindCalls) // re-bound: the seed was re-requested…
        assertEquals(2, mnemonic.calls)
        assertEquals(2, sdk.discoverCalls) // …and discovery re-ran on the fresh wallet

        // And the re-bound state latches again.
        binder.bindIfEnabled(unlock)
        assertEquals(2, sdk.bindCalls)
    }

    // ── In-process wallet replacement (Reset Wallet → restore, no restart) ─

    @Test
    fun isBoundWalletStale_truthTable() {
        // Same fingerprint (a restore of the SAME phrase): latch stays.
        assertTrue(!isBoundWalletStale("aa", "aa"))
        // Different wallet loaded: stale — the live S21 incident.
        assertTrue(isBoundWalletStale("aa", "bb"))
        // Latched without a fingerprintable wallet, one is loaded now:
        // cannot prove the latch covers it — stale (one idempotent rebind).
        assertTrue(isBoundWalletStale(null, "bb"))
        // No fingerprintable wallet right now: no evidence of replacement.
        assertTrue(!isBoundWalletStale("aa", null))
        assertTrue(!isBoundWalletStale(null, null))
    }

    @Test
    fun walletReplacedInProcess_invalidatesTheLatch_fullRebindPrunesTheOldSdkWallet() = runBlocking {
        // The live S21 incident (02:28–02:35): Reset Wallet →
        // restore-from-seed WITHOUT a process restart. The latch must not
        // keep the previous wallet's binding — the next trigger must run
        // the full bind pass against the NEW wallet, whose orphan prune
        // removes the old deterministic SDK wallet.
        val oldSdkWalletId = "ab".repeat(32)
        val sdk = FakeSdkService()
        var currentSdkWalletId = oldSdkWalletId
        sdk.onBind = { _, _ -> currentSdkWalletId }
        sdk.managed = { _, _ -> sdk.discoverCalls > 0 }
        sdk.onDiscover = { _, _ -> listOf(Identifier.from(userId).toBuffer()) }
        val mnemonic = FakeMnemonicProvider { words }
        var currentWallet: Wallet? = walletWithFingerprint(1)
        val walletData: WalletData = mockk { every { wallet } answers { currentWallet } }
        val binder = binder(sdk, mnemonic, walletData = walletData, scope = this)

        binder.bindIfEnabled(unlock)
        binder.bindIfEnabled(unlock) // latched for wallet A
        assertEquals(1, sdk.bindCalls)
        assertEquals(1, mnemonic.calls)

        // Reset Wallet + restore of a DIFFERENT phrase, same process: the
        // app wallet object is replaced; the SDK still holds the old wallet.
        currentWallet = walletWithFingerprint(2)
        currentSdkWalletId = walletId
        sdk.loadedWallets = setOf(oldSdkWalletId, walletId)

        binder.bindIfEnabled(unlock) // trigger revalidates → full rebind
        assertEquals(2, sdk.bindCalls) // seed re-requested from the NEW wallet…
        assertEquals(2, mnemonic.calls)
        assertEquals(listOf(oldSdkWalletId), sdk.removedWallets) // …old binding pruned as orphan

        // And the NEW binding latches again.
        binder.bindIfEnabled(unlock)
        assertEquals(2, sdk.bindCalls)
    }

    @Test
    fun sameWalletRestoredInProcess_keepsTheLatch_noSpuriousRebind() = runBlocking {
        // Restore of the SAME phrase re-derives the SAME deterministic SDK
        // wallet id — the existing binding stays valid, no seed re-request.
        val sdk = readySdk()
        val mnemonic = FakeMnemonicProvider { words }
        var currentWallet: Wallet? = walletWithFingerprint(1)
        val walletData: WalletData = mockk { every { wallet } answers { currentWallet } }
        val binder = binder(sdk, mnemonic, walletData = walletData, scope = this)

        binder.bindIfEnabled(unlock)
        assertEquals(1, sdk.bindCalls)

        currentWallet = walletWithFingerprint(1) // new instance, same seed
        binder.bindIfEnabled(unlock)
        assertEquals(1, sdk.bindCalls)
        assertEquals(1, mnemonic.calls)
    }

    @Test
    fun walletMomentarilyUnloaded_keepsTheLatch() = runBlocking {
        // No wallet loaded is NOT evidence of a replacement — the latch
        // survives and the next trigger (wallet back) re-checks.
        val sdk = readySdk()
        var currentWallet: Wallet? = walletWithFingerprint(1)
        val walletData: WalletData = mockk { every { wallet } answers { currentWallet } }
        val binder = binder(sdk, walletData = walletData, scope = this)

        binder.bindIfEnabled(unlock)
        assertEquals(1, sdk.bindCalls)

        currentWallet = null
        binder.bindIfEnabled(unlock)
        assertEquals(1, sdk.bindCalls) // still latched

        currentWallet = walletWithFingerprint(1)
        binder.bindIfEnabled(unlock)
        assertEquals(1, sdk.bindCalls) // same wallet — still latched
    }

    @Test
    fun identityAlreadyManaged_skipsDiscovery_andLatches() = runBlocking {
        val sdk = FakeSdkService()
        sdk.onBind = { _, _ -> walletId }
        sdk.managed = { _, _ -> true }
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock)
        binder.bindIfEnabled(unlock)

        assertEquals(1, sdk.bindCalls)
        assertEquals(0, sdk.discoverCalls)
        assertEquals(1, sdk.managedCalls)
    }

    @Test
    fun identityNotYetRegistered_bindsWalletOnly_retriesAttachNextCall() = runBlocking {
        // Identity creation in flight: creationState != NONE but no id yet.
        val sdk = readySdk()
        val binder = binder(
            sdk,
            identity = identityConfig(
                identityBase(IdentityCreationState.IDENTITY_REGISTERING, userId = null)
            ),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(1, sdk.bindCalls)
        assertEquals(0, sdk.discoverCalls)

        // Not latched: once the id lands a later trigger completes the attach.
        binder.bindIfEnabled(unlock)
        assertEquals(1, sdk.bindCalls) // wallet bind is cached
    }

    @Test
    fun malformedStoredIdentityId_bindsWalletOnly_noThrow() = runBlocking {
        val sdk = readySdk()
        val binder = binder(
            sdk,
            identity = identityConfig(identityBase(userId = "not-base58!!")),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(1, sdk.bindCalls)
        assertEquals(0, sdk.discoverCalls)
    }

    @Test
    fun discoveryDoesNotFindIdentity_latches_noEndlessRescan() = runBlocking {
        val sdk = FakeSdkService()
        sdk.onBind = { _, _ -> walletId }
        sdk.managed = { _, _ -> false } // never becomes managed
        sdk.onDiscover = { _, _ -> emptyList() }
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock)
        binder.bindIfEnabled(unlock)

        // The deterministic scan ran once; not repeated in-process.
        assertEquals(1, sdk.discoverCalls)
    }

    // ── Failure containment + partial-progress retry ─────────────────────

    @Test
    fun nullUnlock_skipsQuietly_retriesNextCall() = runBlocking {
        val sdk = readySdk()
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled { null }
        assertEquals(0, sdk.totalCalls)

        binder.bindIfEnabled(unlock)
        assertEquals(1, sdk.bindCalls)
    }

    @Test
    fun mnemonicFailure_swallowed_noBind() = runBlocking {
        val sdk = readySdk()
        val mnemonic = FakeMnemonicProvider {
            throw MnemonicBridgeException(MnemonicBridgeException.Reason.BAD_ENCRYPTION_KEY)
        }
        val binder = binder(sdk, mnemonic, scope = this)

        binder.bindIfEnabled(unlock) // must not throw

        assertEquals(0, sdk.bindCalls)
    }

    @Test
    fun bindFailure_swallowed_retriedOnNextCall() = runBlocking {
        val sdk = readySdk()
        var failFirst = true
        sdk.onBind = { _, _ ->
            if (failFirst) {
                failFirst = false
                throw RuntimeException("SDK bootstrap failed")
            }
            walletId
        }
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock) // must not throw
        assertEquals(1, sdk.bindCalls)
        assertEquals(0, sdk.discoverCalls)

        binder.bindIfEnabled(unlock)
        assertEquals(2, sdk.bindCalls)
        assertEquals(1, sdk.discoverCalls)
    }

    @Test
    fun discoveryFailure_swallowed_retryReusesBoundWallet_withoutSeedHandOff() = runBlocking {
        val sdk = readySdk()
        var failDiscovery = true
        sdk.onDiscover = { _, _ ->
            if (failDiscovery) {
                failDiscovery = false
                throw RuntimeException("network error")
            }
            listOf(Identifier.from(userId).toBuffer())
        }
        // managed only after a SUCCESSFUL scan (the second one).
        sdk.managed = { _, _ -> sdk.discoverCalls >= 2 && !failDiscovery }
        val mnemonic = FakeMnemonicProvider { words }
        val binder = binder(sdk, mnemonic, scope = this)

        binder.bindIfEnabled(unlock) // discovery throws; swallowed
        assertEquals(1, sdk.bindCalls)
        assertEquals(1, mnemonic.calls)

        binder.bindIfEnabled(unlock) // retries discovery only
        assertEquals(1, sdk.bindCalls)
        assertEquals(1, mnemonic.calls) // the seed is NOT re-requested
        assertEquals(2, sdk.discoverCalls)
    }

    // ── the missing-mnemonic wedge (already-exists recovery incident) ─────

    /** The live error text from discoverIdentities on a phrase-less wallet. */
    private val missingMnemonicError =
        RuntimeException("mnemonic resolver: no mnemonic stored for the supplied wallet_id")

    @Test
    fun isMissingMnemonicError_matchesTheLiveMessage_directAndInTheCauseChain() {
        assertTrue(isMissingMnemonicError(missingMnemonicError))
        assertTrue(isMissingMnemonicError(RuntimeException("wrapped", missingMnemonicError)))
        assertTrue(!isMissingMnemonicError(RuntimeException("network error")))
        assertTrue(!isMissingMnemonicError(RuntimeException("no private key stored for abc")))
    }

    @Test
    fun discoveryMissingMnemonic_retryable_nextPassRunsTheFullBind_andHeals() = runBlocking {
        // The permanent-wedge incident: a bound wallet with no stored phrase.
        // The first pass fails at discovery; the binder must NOT keep the
        // cached bound id (retrying discovery can never succeed) — the next
        // trigger re-runs the FULL bind, whose already-exists recovery
        // re-stores the phrase, and then discovery proceeds.
        val sdk = FakeSdkService()
        var mnemonicStored = false
        sdk.onBind = { _, _ ->
            // The FIRST bind returned the id WITHOUT persisting the phrase
            // (the incident); a RE-bind lands in bindAppWallet's
            // already-exists recovery, which re-stores it.
            if (sdk.bindCalls > 1) mnemonicStored = true
            walletId
        }
        var identityAttached = false // only a SUCCESSFUL scan attaches it
        sdk.onDiscover = { _, _ ->
            if (!mnemonicStored) throw missingMnemonicError
            identityAttached = true
            listOf(Identifier.from(userId).toBuffer())
        }
        sdk.managed = { _, _ -> identityAttached }
        val mnemonic = FakeMnemonicProvider { words }
        val binder = binder(sdk, mnemonic, scope = this)

        binder.bindIfEnabled(unlock) // pass 1: bind, then discovery throws
        assertEquals(1, sdk.bindCalls)
        assertEquals(1, sdk.discoverCalls)

        binder.bindIfEnabled(unlock) // pass 2: FULL rebind (re-store) + discovery
        assertEquals(2, sdk.bindCalls) // the cached bound id was dropped…
        assertEquals(2, mnemonic.calls) // …and the seed re-requested for the re-store
        assertEquals(2, sdk.discoverCalls)
        assertEquals(1, sdk.healCalls)

        binder.bindIfEnabled(unlock) // pass 3: healed state latches
        assertEquals(2, sdk.bindCalls)
    }

    @Test
    fun healMissingMnemonic_alsoTriggersTheFullRebind() = runBlocking {
        // Same wedge surfacing from the key heal (it derives via the
        // resolver too) on an already-managed identity.
        val sdk = FakeSdkService()
        var mnemonicStored = false
        sdk.onBind = { _, _ ->
            if (sdk.bindCalls > 1) mnemonicStored = true // re-bind recovery re-stores
            walletId
        }
        sdk.managed = { _, _ -> true }
        sdk.onHealKeys = { _, _ ->
            if (!mnemonicStored) throw missingMnemonicError
            IdentityKeyHealReport(keysChecked = 4, healthy = 0, repaired = 4, watchOnly = 0, failed = 0)
        }
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock) // heal throws; swallowed
        assertEquals(1, sdk.bindCalls)
        assertEquals(1, sdk.healCalls)

        binder.bindIfEnabled(unlock) // full rebind re-stores, heal succeeds, latches
        assertEquals(2, sdk.bindCalls)
        assertEquals(2, sdk.healCalls)

        binder.bindIfEnabled(unlock)
        assertEquals(2, sdk.bindCalls) // latched
    }

    @Test
    fun identityConfigFailure_swallowed_zeroSdkInteractions() = runBlocking {
        val sdk = FakeSdkService()
        val identity: BlockchainIdentityConfig = mockk {
            coEvery { loadBase() } throws IllegalStateException("datastore unavailable")
        }
        val binder = binder(sdk, identity = identity, scope = this)

        binder.bindIfEnabled(unlock) // must not throw

        assertEquals(0, sdk.totalCalls)
    }

    // ── Phase 3f-b: key healing after attach ─────────────────────────────

    @Test
    fun afterDiscoveryAttach_healsAppIdentityKeys_onBoundWallet() = runBlocking {
        val sdk = readySdk()
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock)

        assertEquals(1, sdk.healCalls)
        assertEquals(walletId, sdk.lastHealedWalletId)
        assertEquals(userId, Identifier.from(sdk.lastHealedIdentityId!!).toString())
    }

    @Test
    fun alreadyManaged_butKeysNotHealed_retriesHealNextCall_withoutRescan() = runBlocking {
        // The live-observed state: identity attached by an earlier pass,
        // but the private-key store is empty and the first heal fails
        // transiently (expired Keystore auth window).
        val sdk = FakeSdkService()
        sdk.onBind = { _, _ -> walletId }
        sdk.managed = { _, _ -> true }
        sdk.onHealKeys = { _, _ ->
            if (sdk.healCalls == 1) {
                IdentityKeyHealReport(keysChecked = 4, healthy = 0, repaired = 0, watchOnly = 0, failed = 4)
            } else {
                IdentityKeyHealReport(keysChecked = 4, healthy = 0, repaired = 4, watchOnly = 0, failed = 0)
            }
        }
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock) // heal fails transiently → not latched
        binder.bindIfEnabled(unlock) // heal repairs → latched
        binder.bindIfEnabled(unlock) // no-op at the latch

        assertEquals(2, sdk.healCalls)
        assertEquals(1, sdk.bindCalls) // wallet bind cached across retries
        assertEquals(0, sdk.discoverCalls) // heal retry never re-scans
    }

    @Test
    fun healThrow_swallowed_notLatched_retriedNextCall() = runBlocking {
        val sdk = readySdk()
        sdk.onHealKeys = { _, _ ->
            if (sdk.healCalls == 1) throw RuntimeException("Room read failed")
            IdentityKeyHealReport(keysChecked = 4, healthy = 4, repaired = 0, watchOnly = 0, failed = 0)
        }
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock) // must not throw
        binder.bindIfEnabled(unlock)
        binder.bindIfEnabled(unlock)

        assertEquals(2, sdk.healCalls)
        assertEquals(1, sdk.discoverCalls)
    }

    @Test
    fun watchOnlyKeys_areSettled_latches_noEndlessHealRetry() = runBlocking {
        // Deterministic outcome (keys not derivable from this seed):
        // retrying cannot improve it, so the binder must latch.
        val sdk = readySdk()
        sdk.onHealKeys = { _, _ ->
            IdentityKeyHealReport(keysChecked = 4, healthy = 3, repaired = 0, watchOnly = 1, failed = 0)
        }
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock)
        binder.bindIfEnabled(unlock)

        assertEquals(1, sdk.healCalls)
    }

    @Test
    fun zeroPersistedKeyRows_notSettled_healRetriedNextCall() = runBlocking {
        // Key rows may still be landing via the persistence bridge.
        val sdk = readySdk()
        sdk.onHealKeys = { _, _ ->
            IdentityKeyHealReport(keysChecked = 0, healthy = 0, repaired = 0, watchOnly = 0, failed = 0)
        }
        val binder = binder(sdk, scope = this)

        binder.bindIfEnabled(unlock)
        binder.bindIfEnabled(unlock)

        assertEquals(2, sdk.healCalls)
        assertEquals(1, sdk.discoverCalls) // identity stays managed; only the heal reruns
    }

    // ── Single-flight + background variant ───────────────────────────────

    @Test
    fun concurrentCalls_singleFlight_bindRunsOnce() = runBlocking {
        val sdk = readySdk()
        val baseBind = sdk.onBind
        sdk.onBind = { w, b ->
            delay(50) // hold the mutex so the second caller queues behind
            baseBind(w, b)
        }
        val mnemonic = FakeMnemonicProvider { words }
        val binder = binder(sdk, mnemonic, scope = this)

        val first = launch { binder.bindIfEnabled(unlock) }
        val second = launch { binder.bindIfEnabled(unlock) }
        first.join()
        second.join()

        assertEquals(1, sdk.bindCalls)
        assertEquals(1, mnemonic.calls)
        assertEquals(1, sdk.discoverCalls)
    }

    @Test
    fun bindInBackground_runsTheSamePass_andNeverThrowsIntoCaller() = runBlocking {
        val sdk = readySdk()
        val binder = binder(sdk, scope = this)

        binder.bindInBackground(unlock).join()

        assertEquals(1, sdk.bindCalls)
        assertEquals(1, sdk.discoverCalls)
    }

    @Test
    fun bindInBackground_lazyProvider_notInvokedWhenIneligible() = runBlocking {
        val sdk = FakeSdkService()
        var unlockRequested = false
        val binder = binder(
            sdk,
            config = dashPayConfig(readsFlag = false, writesFlag = false),
            scope = this
        )

        binder.bindInBackground {
            unlockRequested = true
            unlock
        }.join()

        assertEquals(0, sdk.totalCalls)
        assertTrue(!unlockRequested)
    }

    // ── DIP-15 friend-chain provisioning (contact-payment capture) ───────
    //
    // The gap `scratchpad/txdiff/FINDINGS.md` found: the app keeps DashPay
    // contacts on dashj and never drives the SDK's contact-sync path, so the
    // bound SDK L1 wallet derives NO m/9'/coin'/15' friend chains and misses
    // contact/username payments. The binder drives the (already-published)
    // SDK provisioning under the SAME eligibility gate as bind.

    @Test
    fun provisioning_flagsOff_noSdkCall() = runBlocking {
        val sdk = readySdk()
        val binder = binder(
            sdk,
            config = dashPayConfig(readsFlag = false, writesFlag = false),
            scope = this
        )
        binder.provisionContactAccountsIfEnabled(force = true)
        assertEquals(0, sdk.provisionCalls)
    }

    @Test
    fun provisioning_platformNotSupported_noSdkCall() = runBlocking {
        val sdk = readySdk()
        val binder = binder(sdk, supportsPlatform = false, scope = this)
        binder.provisionContactAccountsIfEnabled(force = true)
        assertEquals(0, sdk.provisionCalls)
    }

    @Test
    fun provisioning_notBoundYet_noSdkCall() = runBlocking {
        // Flags on, but no bind pass has produced a bound wallet id — there
        // is nothing to provision (a later bind trigger will, then this).
        val sdk = readySdk()
        val binder = binder(sdk, scope = this)
        binder.provisionContactAccountsIfEnabled(force = true)
        assertEquals(0, sdk.provisionCalls)
    }

    @Test
    fun provisioning_boundButNoIdentity_noSdkCall() = runBlocking {
        // The shielded-only path binds a wallet before any identity exists;
        // with no identity there are no contacts / friend chains to build.
        val sdk = readySdk()
        val binder = binder(
            sdk,
            identity = identityConfig(identityBase(IdentityCreationState.NONE, userId = null)),
            config = dashPayConfig(readsFlag = false, writesFlag = false, shieldedFlag = true),
            scope = this
        )
        binder.bindIfEnabled(unlock) // binds the wallet, no identity attach
        assertEquals(1, sdk.bindCalls)

        binder.provisionContactAccountsIfEnabled(force = true)
        assertEquals(0, sdk.provisionCalls)
    }

    @Test
    fun provisioning_eligibleAndBound_callsSdkWithTheBoundWalletId() = runBlocking {
        val sdk = readySdk()
        val binder = binder(sdk, scope = this)
        binder.bindIfEnabled(unlock) // establishes the bound wallet id
        assertEquals(walletId, sdk.lastHealedWalletId) // sanity: bind completed

        binder.provisionContactAccountsIfEnabled(force = true)

        assertEquals(1, sdk.provisionCalls)
        assertEquals(walletId, sdk.lastProvisionWalletId)
    }

    @Test
    fun provisioning_nonForced_throttledWithinWindow_forcedBypasses() = runBlocking {
        val sdk = readySdk()
        var clock = 10_000_000L
        val binder = binder(sdk, now = { clock }, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = false) // first pass runs
        assertEquals(1, sdk.provisionCalls)

        clock += 30_000L // inside the 60 s floor
        binder.provisionContactAccountsIfEnabled(force = false) // throttled → skipped
        assertEquals(1, sdk.provisionCalls)

        binder.provisionContactAccountsIfEnabled(force = true) // forced bypasses the floor
        assertEquals(2, sdk.provisionCalls)

        clock += 70_000L // past the floor since the last pass
        binder.provisionContactAccountsIfEnabled(force = false) // runs again
        assertEquals(3, sdk.provisionCalls)
    }

    @Test
    fun provisioning_singleFlight_concurrentCallsRunOnce() = runBlocking {
        val sdk = readySdk()
        sdk.onProvision = { _ ->
            delay(50) // hold the single-flight slot so the second caller drops
            DashPayContactProvisionReport(bound = true, syncSuccess = 1, syncErrors = 0, pendingBefore = 0, drainScheduled = false)
        }
        val binder = binder(sdk, scope = this)
        binder.bindIfEnabled(unlock)

        val a = launch { binder.provisionContactAccountsIfEnabled(force = true) }
        val b = launch { binder.provisionContactAccountsIfEnabled(force = true) }
        a.join()
        b.join()

        assertEquals(1, sdk.provisionCalls)
    }

    @Test
    fun provisioning_background_neverThrowsIntoCaller_andRunsThePass() = runBlocking {
        val sdk = readySdk()
        sdk.onProvision = { _ -> throw RuntimeException("DashPay sweep failed") }
        val binder = binder(sdk, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsInBackground(force = true).join() // must not throw

        assertEquals(1, sdk.provisionCalls)
    }
}
