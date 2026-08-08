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
import org.dash.wallet.common.data.BlockchainServiceConfig
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

        var drainCalls = 0
        var lastDrainWalletId: String? = null
        var onDrain: suspend (String) -> DashPayContactDrainReport = { _ ->
            DashPayContactDrainReport(
                bound = true, queuedBefore = 0, drainScheduled = false, queuedAfter = 0
            )
        }
        override suspend fun drainDashPayContactAccountBuilds(
            walletIdHex: String
        ): DashPayContactDrainReport {
            drainCalls++
            lastDrainWalletId = walletIdHex
            return onDrain(walletIdHex)
        }

        var pendingAccountBuilds: Int? = null
        override suspend fun dashPayPendingAccountBuilds(walletIdHex: String): Int? =
            pendingAccountBuilds

        /**
         * Backfill signals the gate reads. Default UNKNOWN → the gate can
         * prove nothing and always forces the pass, so every pre-existing
         * provisioning test keeps its original expectations.
         */
        var backfillSignals: DashPayBackfillSignals = DashPayBackfillSignals.UNKNOWN
        var backfillSignalReads = 0
        override suspend fun readDashPayBackfillSignals(
            walletIdHex: String,
            ownerIdentityId: ByteArray
        ): DashPayBackfillSignals {
            backfillSignalReads++
            return backfillSignals
        }

        var storeKeyCalls = 0
        override suspend fun storeIdentityPrivateKey(
            pubkeyHex: String,
            privateKey: ByteArray,
            walletId: ByteArray
        ) {
            storeKeyCalls++
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
     * A [WalletData] whose dashj wallet reports [earliestKeyCreationTimeSecs]
     * — the ONLY birth signal the binder used to read, and the one a seed
     * restore stamps with the 2015 [sentinelSecs] sentinel.
     */
    private fun walletDataWithKeyTime(earliestKeyCreationTimeSecs: Long): WalletData {
        val dashjWallet: Wallet = mockk {
            every { watchingKey.pubKeyHash } returns ByteArray(20)
            every { earliestKeyCreationTime } returns earliestKeyCreationTimeSecs
        }
        return mockk { every { wallet } returns dashjWallet }
    }

    /** The persisted wallet creation date; null = the user never chose one. */
    private fun blockchainServiceConfig(creationDateSecs: Long?): BlockchainServiceConfig =
        mockk { coEvery { getWalletCreationDate() } returns creationDateSecs }

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
        serviceConfig: BlockchainServiceConfig = blockchainServiceConfig(null),
        now: () -> Long = { System.currentTimeMillis() },
        backfillGate: DashPayBackfillGate = DashPayBackfillGate.ALWAYS_RUN,
        backfillWatchIntervalMs: Long = 5L,
        scope: CoroutineScope
    ) = SdkWalletBinder(
        sdkService = sdk,
        mnemonicProvider = mnemonic,
        identityConfig = identity,
        dashPayConfig = config,
        walletData = walletData,
        blockchainServiceConfig = serviceConfig,
        scope = scope,
        supportsPlatform = { supportsPlatform },
        now = now,
        backfillGate = backfillGate,
        backfillWatchIntervalMs = backfillWatchIntervalMs
    )

    /** Scriptable [DashPayBackfillGate] with interaction counters. */
    private class FakeBackfillGate(
        var shouldRun: Boolean,
        /** Set to arm a rewind, which is what starts the post-arm watch. */
        var armed: BackfillArmed? = null,
        /** Flipped by a test to end the watch, standing in for the latch. */
        var accountedFor: Boolean = true
    ) : DashPayBackfillGate {
        var evaluateCalls = 0
        var recordCalls = 0
        var accountedForCalls = 0
        var lastWalletId: String? = null
        var lastIdentityId: ByteArray? = null
        var lastUserId: String? = null

        override suspend fun evaluate(
            walletIdHex: String,
            ownerIdentityId: ByteArray,
            ownerUserId: String
        ): BackfillDecision {
            evaluateCalls++
            lastWalletId = walletIdHex
            lastIdentityId = ownerIdentityId
            lastUserId = ownerUserId
            return BackfillDecision(
                shouldRun = shouldRun,
                reason = "test",
                armedToWrite = armed
            )
        }

        override suspend fun recordPassOutcome(
            walletIdHex: String,
            ownerIdentityId: ByteArray,
            report: DashPayContactProvisionReport
        ) {
            recordCalls++
        }

        override suspend fun isRewindAccountedFor(): Boolean {
            accountedForCalls++
            return accountedFor
        }

        /** Set true to have the no-rewind conclusion succeed when the watch reaches it. */
        var concludesNoRewind: Boolean = false
        var concludeCalls = 0

        override suspend fun concludeNoRewindObserved(
            walletIdHex: String,
            ownerIdentityId: ByteArray,
            ownerUserId: String
        ): Boolean {
            concludeCalls++
            if (concludesNoRewind) accountedFor = true
            return concludesNoRewind
        }
    }

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

    // ── Wallet birth time: the chosen restore date must reach the SDK ────

    /**
     * `DashWalletFactory.restoreWalletFromSeed` stamps EVERY restored seed
     * with this 2015 value ("the wallet creation time should always be the
     * oldest possible time"), so a restored wallet's own
     * `earliestKeyCreationTime` says nothing about when the wallet was
     * really created — it is a floor, not a measurement.
     */
    private val sentinelSecs = de.schildbach.wallet.Constants.EARLIEST_HD_SEED_CREATION_TIME

    /** A date a tester could pick on the restore screen: 2023-07-22. */
    private val chosenDateSecs = 1_690_000_000L

    @Test
    fun chosenRestoreDate_reachesBindAppWallet() = runBlocking {
        // The regression: a restore with a date chosen still handed the
        // resolver the 2015 sentinel (observed on mainnet as
        // "resolved birth time 1427610960 … birthHeight=239040"), because
        // the binder read only the wallet and the picked date lives in
        // BlockchainServiceConfig.
        val sdk = readySdk()
        val binder = binder(
            sdk,
            walletData = walletDataWithKeyTime(sentinelSecs),
            serviceConfig = blockchainServiceConfig(chosenDateSecs),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(chosenDateSecs, sdk.lastBirthTime)
    }

    @Test
    fun noRestoreDateChosen_keepsTheEarliestPossibleBirthTime() = runBlocking {
        // "I don't know when I created it": nothing is persisted, so the
        // binder must still hand over the earliest-possible sentinel —
        // a full scan is the correct answer here, and anything LATER
        // would risk hiding transactions.
        val sdk = readySdk()
        val binder = binder(
            sdk,
            walletData = walletDataWithKeyTime(sentinelSecs),
            serviceConfig = blockchainServiceConfig(null),
            scope = this
        )

        binder.bindIfEnabled(unlock)

        assertEquals(sentinelSecs, sdk.lastBirthTime)
    }

    @Test
    fun birthTime_prefersTheChosenDateOverTheRestoreSentinel() {
        assertEquals(chosenDateSecs, sdkWalletBirthTimeSecs(chosenDateSecs, sentinelSecs))
    }

    @Test
    fun birthTime_unknownDateFallsBackToTheSentinel() {
        assertEquals(sentinelSecs, sdkWalletBirthTimeSecs(null, sentinelSecs))
    }

    @Test
    fun birthTime_aSentinelValuedConfigIsNotInformation() {
        // BlockchainServiceConfig already nulls the sentinel, but a caller
        // that passes it raw must not be treated as a real choice either.
        assertEquals(sentinelSecs, sdkWalletBirthTimeSecs(sentinelSecs, sentinelSecs))
    }

    @Test
    fun birthTime_neverSkipsPastARealWalletKeyTime() {
        // Restore-from-backup: the protobuf carries a genuine key creation
        // time (2020). A later user-entered date must NOT move the scan
        // start forward past it — that would silently hide 2020-2023
        // transactions. The earliest real signal wins.
        val realKeyTimeSecs = 1_580_000_000L // 2020-01-26
        assertEquals(realKeyTimeSecs, sdkWalletBirthTimeSecs(chosenDateSecs, realKeyTimeSecs))
    }

    @Test
    fun birthTime_chosenDateWinsWhenItIsEarlierThanTheWalletKeyTime() {
        val realKeyTimeSecs = 1_700_000_000L // 2023-11-14
        assertEquals(chosenDateSecs, sdkWalletBirthTimeSecs(chosenDateSecs, realKeyTimeSecs))
    }

    @Test
    fun birthTime_noWalletAndNoDateStaysUnknown() {
        // Unknown => bindAppWallet maps null to birthHeight 0 (genesis).
        assertNull(sdkWalletBirthTimeSecs(null, null))
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

    // ── DIP-15 coreHeight-backfill gate wiring ──────────────────────────

    @Test
    fun provisioning_backfillGateSaysSkip_doesNotTouchTheSdkSweep() = runBlocking {
        // The tester-facing fix: once the backfill has provably completed,
        // the pass (and with it the SDK's synced_height rewind) is skipped
        // entirely, so the initial sync can finally finish.
        val sdk = readySdk()
        val gate = FakeBackfillGate(shouldRun = false)
        val binder = binder(sdk, backfillGate = gate, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = true)

        assertEquals(1, gate.evaluateCalls)
        assertEquals(0, sdk.provisionCalls)
        assertEquals(0, gate.recordCalls)
    }

    @Test
    fun provisioning_backfillGateSaysSkip_stillDrainsTheDeferredAccountBuilds() = runBlocking {
        // The queue and the sweep are separate concerns: the gate skips the
        // SWEEP because it rewinds the SPV synced height, but a contact's
        // account build left queued keeps that contact's receiving addresses
        // out of the watched script set, so their payments never match a
        // filter and the balance stays short. Observed live: 182 builds
        // deferred, 176 accounts registered, and no drain after the first
        // session because every later launch took this skip path.
        val sdk = readySdk()
        sdk.onDrain = { _ ->
            DashPayContactDrainReport(
                bound = true, queuedBefore = 182, drainScheduled = true, queuedAfter = 6
            )
        }
        val gate = FakeBackfillGate(shouldRun = false)
        val binder = binder(sdk, backfillGate = gate, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = true)

        assertEquals(0, sdk.provisionCalls)
        assertEquals(1, sdk.drainCalls)
        assertEquals(walletId, sdk.lastDrainWalletId)
    }

    @Test
    fun provisioning_backfillGateSaysSkip_drainsEvenWhenTheQueueIsEmpty() = runBlocking {
        // The live S21 log showed the gate's SKIPPING REWIND line and then
        // NOTHING from the drain, which reads identically whether the drain
        // was unwired or merely found an empty queue (deferredContactBuilds=0
        // on that device — it was the latter). The pass must run, and say so,
        // regardless of what it finds.
        val sdk = readySdk()
        sdk.onDrain = { _ ->
            DashPayContactDrainReport(
                bound = true, queuedBefore = 0, drainScheduled = false, queuedAfter = 0
            )
        }
        val gate = FakeBackfillGate(shouldRun = false)
        val binder = binder(sdk, backfillGate = gate, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = true)

        assertEquals(1, sdk.drainCalls)
        assertEquals(walletId, sdk.lastDrainWalletId)
    }

    @Test
    fun drainReport_saysQueuedOnEveryOutcome_soAnAbsentLineMeansTheDrainDidNotRun() {
        val empty = describeContactDrain(
            DashPayContactDrainReport(
                bound = true, queuedBefore = 0, drainScheduled = false, queuedAfter = 0
            )
        )
        val unbound = describeContactDrain(
            DashPayContactDrainReport(
                bound = false, queuedBefore = 0, drainScheduled = false, queuedAfter = 0
            )
        )
        val worked = describeContactDrain(
            DashPayContactDrainReport(
                bound = true, queuedBefore = 182, drainScheduled = true, queuedAfter = 6
            )
        )
        // The grep a tester's log is read with.
        assertTrue(empty.contains("queued="))
        assertTrue(unbound.contains("queued="))
        assertTrue(worked.contains("queued=182"))
        assertTrue(worked.contains("built=176"))
        assertTrue(worked.contains("stillQueued=6"))
        // And the three outcomes must not read alike.
        assertEquals(3, setOf(empty, unbound, worked).size)
    }

    @Test
    fun provisioning_drainFailure_isContainedLikeTheRestOfThePass() = runBlocking {
        val sdk = readySdk()
        sdk.onDrain = { _ -> error("keystore unavailable") }
        val gate = FakeBackfillGate(shouldRun = false)
        val binder = binder(sdk, backfillGate = gate, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = true)

        assertEquals(1, sdk.drainCalls)
    }

    @Test
    fun provisioning_backfillGateSaysRun_leavesTheDrainToTheSweepPass() = runBlocking {
        // provisionDashPayContactAccounts already schedules and observes the
        // drain as its step 2 — the skip path is the only one that needed its
        // own, so a permitted pass must not drain twice.
        val sdk = readySdk()
        val gate = FakeBackfillGate(shouldRun = true)
        val binder = binder(sdk, backfillGate = gate, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = true)

        assertEquals(1, sdk.provisionCalls)
        assertEquals(0, sdk.drainCalls)
    }

    @Test
    fun provisioning_backfillGateSaysRun_provisionsAndReportsTheOutcomeBack() = runBlocking {
        val sdk = readySdk()
        val gate = FakeBackfillGate(shouldRun = true)
        val binder = binder(sdk, backfillGate = gate, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = true)

        assertEquals(1, sdk.provisionCalls)
        assertEquals(1, gate.recordCalls)
        assertEquals(walletId, gate.lastWalletId)
        assertEquals(userId, gate.lastUserId)
        assertEquals(
            Identifier.from(userId).toBuffer().toList(),
            gate.lastIdentityId?.toList()
        )
    }

    @Test
    fun provisioning_armedRewind_keepsPollingUntilTheGateAccountsForIt() = runBlocking {
        // The livelock this closes: the armed rewind is only provable while
        // the synced height sits below the armed target, and the gate is
        // otherwise consulted only when a provisioning trigger fires — in the
        // field, ~32 min later, long after the window shut. So every launch
        // re-ran the whole rescan. The watch keeps looking until it latches.
        val sdk = readySdk()
        val gate = FakeBackfillGate(
            shouldRun = true,
            armed = BackfillArmed(targetHeight = 2_352_092L, contactFingerprint = "fp"),
            accountedFor = false
        )
        val binder = binder(sdk, backfillGate = gate, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = true)
        val evaluatesAfterPass = gate.evaluateCalls

        // The rewind has not landed yet: the watch must keep polling.
        delay(40)
        assertTrue(
            "the watch should re-consult the gate while the rewind is unaccounted for",
            gate.evaluateCalls > evaluatesAfterPass
        )

        // The latch lands; the watch must stop consulting.
        gate.accountedFor = true
        delay(20)
        val evaluatesAtLatch = gate.evaluateCalls
        delay(40)
        assertEquals(
            "the watch should stop once the rewind is accounted for",
            evaluatesAtLatch,
            gate.evaluateCalls
        )
    }

    @Test
    fun provisioning_armedRewindThatNeverLands_concludesNoRewindAndStops() = runBlocking {
        // The wallet that needs no backfill at all. Nothing is ever accounted
        // for, so without a conclusion the watch would spin out its whole
        // budget and every later launch would re-provision forever. The
        // conclusion may only be reached after the quiet-observation window.
        val sdk = readySdk()
        val gate = FakeBackfillGate(
            shouldRun = true,
            armed = BackfillArmed(targetHeight = 1_529_377L, contactFingerprint = "fp"),
            accountedFor = false
        )
        gate.concludesNoRewind = true
        val binder = binder(sdk, backfillGate = gate, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = true)

        // Before the window elapses the watch must NOT conclude.
        delay(15)
        assertEquals(
            "must not conclude before the quiet-observation window elapses",
            0,
            gate.concludeCalls
        )

        delay(60)
        assertTrue("should have concluded once the window elapsed", gate.concludeCalls >= 1)
        val evaluatesAtConclusion = gate.evaluateCalls
        delay(40)
        assertEquals(
            "the watch should stop once the no-rewind conclusion is recorded",
            evaluatesAtConclusion,
            gate.evaluateCalls
        )
    }

    @Test
    fun provisioning_noRewindArmed_startsNoWatch() = runBlocking {
        // A pass that armed nothing has nothing to observe, so the poll must
        // not run at all — it would be pure battery cost on a healthy wallet.
        val sdk = readySdk()
        val gate = FakeBackfillGate(shouldRun = true, armed = null, accountedFor = false)
        val binder = binder(sdk, backfillGate = gate, scope = this)
        binder.bindIfEnabled(unlock)

        binder.provisionContactAccountsIfEnabled(force = true)
        val evaluatesAfterPass = gate.evaluateCalls

        delay(40)
        assertEquals(evaluatesAfterPass, gate.evaluateCalls)
        assertEquals(0, gate.accountedForCalls)
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
