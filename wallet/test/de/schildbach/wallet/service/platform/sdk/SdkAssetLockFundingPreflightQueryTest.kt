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

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SQL-level regression tests for
 * [SdkAssetLockFundingPreflight.queryAssetLockFundingEvidence]: the EXACT
 * production queries ([ELIGIBLE_ASSET_LOCK_DUFFS_SQL] and
 * [UNCLASSIFIED_ASSET_LOCK_DUFFS_SQL]) run against an
 * in-memory instance of the AAR's own Room schema
 * ([org.dashfoundation.dashsdk.persistence.DashDatabase]), seeded with
 * rows shaped like the on-device evidence (S21 `dash-sdk.db`, build
 * 11.10.46):
 *
 * - `txos.isInstantLocked` is 0 on EVERY row the AAR has ever written
 *   while `transactions.context` records the lock (1=instantSend,
 *   3=chainLocked) for the same txids — the per-output flag is DEAD, so
 *   finality must also accept the per-tx context;
 * - `txos.accountId` is NULL on every row — the account only resolves
 *   through the `core_addresses.accountId` address join.
 *
 * Robolectric runner: the query needs a real SQLite (Room in-memory) —
 * the same `sdk = 29` host setup [SdkL1SendMaxSendableParseTest] uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class SdkAssetLockFundingPreflightQueryTest {

    private lateinit var db: DashDatabase

    /** 32-byte wallet id, lowercase hex — the production id format. */
    private val walletIdHex = "11".repeat(32)
    private val walletId = requireNotNull(walletIdFromHex(walletIdHex))

    /** A second wallet, to pin wallet scoping. */
    private val otherWalletIdHex = "22".repeat(32)
    private val otherWalletId = requireNotNull(walletIdFromHex(otherWalletIdHex))

    private companion object {
        const val BIP44_ACCOUNT_ROW_ID = 1L
        const val FOREIGN_ACCOUNT_ROW_ID = 2L
    }

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            DashDatabase::class.java
        ).allowMainThreadQueries().build()
        // The two wallets, the BIP44 standard account 0 the asset lock funds
        // from (accountType=0, standardTag=0, accountIndex=0) and a non-BIP44
        // account (CoinJoin tag) that must never contribute.
        insertWallet(walletId)
        insertWallet(otherWalletId)
        insertAccount(BIP44_ACCOUNT_ROW_ID, walletId, accountType = 0, standardTag = 0, accountIndex = 0)
        insertAccount(FOREIGN_ACCOUNT_ROW_ID, walletId, accountType = 0, standardTag = 1, accountIndex = 0)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun evidence(persistedLocks: List<String> = emptyList()): AssetLockFundingEvidence = runBlocking {
        requireNotNull(
            SdkAssetLockFundingPreflight.queryAssetLockFundingEvidence(db, walletIdHex, persistedLocks)
        )
    }

    private fun eligibleDuffs(): Long = evidence().eligibleDuffs

    private fun unclassifiedDuffs(): Long = evidence().unclassifiedDuffs

    /** Display-order (byte-reversed) lowercase hex of a wire txid — the persisted-lock key format. */
    private fun displayHexOfTxid(wireTxid: ByteArray): String =
        wireTxid.reversedArray().joinToString("") { "%02x".format(it) }

    // ── the defect: IS-locked funds must be eligible within seconds ──────

    @Test
    fun instantSendContext_makesOutputEligible_despiteDeadFlag() {
        // The faucet-receive shape: fresh IS-locked receive, not yet mined.
        // The AAR never sets txos.isInstantLocked, but transactions.context=1
        // recorded the received IS lock — the output must be eligible NOW,
        // not one block later.
        val txid = txid(1)
        insertTx(txid, context = 1)
        insertAddress("addr-is", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(1), amount = 100_000_000L, address = "addr-is", isConfirmed = false, txid = txid)

        assertEquals(100_000_000L, eligibleDuffs())
    }

    @Test
    fun chainLockedContext_makesOutputEligible_despiteDeadFlags() {
        // The S21 change-output shape at mirror lag: transactions.context=3
        // (InChainLockedBlock) while the txo row's finality flags trail.
        val txid = txid(2)
        insertTx(txid, context = 3)
        insertAddress("addr-cl", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(2), amount = 29_999_737L, address = "addr-cl", isConfirmed = false, txid = txid)

        assertEquals(29_999_737L, eligibleDuffs())
    }

    @Test
    fun mempoolContext_staysIneligible() {
        // context=0 (mempool, no lock yet) with dead flags: NOT final — the
        // context term must not fail open into counting unsettled funds.
        val txid = txid(3)
        insertTx(txid, context = 0)
        insertAddress("addr-mem", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(3), amount = 50_000_000L, address = "addr-mem", isConfirmed = false, txid = txid)

        assertEquals(0L, eligibleDuffs())
    }

    @Test
    fun inBlockContext_withoutConfirmedFlag_staysIneligible() {
        // context=2 (inBlock, not chain-locked) is deliberately NOT lock
        // evidence — plain blocks can reorg, and a truly mined output has
        // isConfirmed=1 (maintained correctly on-device).
        val txid = txid(4)
        insertTx(txid, context = 2)
        insertAddress("addr-blk", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(4), amount = 50_000_000L, address = "addr-blk", isConfirmed = false, txid = txid)

        assertEquals(0L, eligibleDuffs())
    }

    @Test
    fun confirmedFlag_stillEligible_withoutAnyTransactionRow() {
        // The flag path must keep working standalone: a confirmed txo whose
        // txid FK is still null (brief insert window) has no transactions
        // row to LEFT-join — the row must not be dropped.
        insertAddress("addr-conf", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(5), amount = 70_000_000L, address = "addr-conf", isConfirmed = true, txid = null)

        assertEquals(70_000_000L, eligibleDuffs())
    }

    @Test
    fun instantLockedFlag_stillEligible_ifAFutureAarSetsIt() {
        insertAddress("addr-flag", BIP44_ACCOUNT_ROW_ID)
        insertTxo(
            outpoint(6),
            amount = 40_000_000L,
            address = "addr-flag",
            isConfirmed = false,
            isInstantLocked = true,
            txid = null
        )

        assertEquals(40_000_000L, eligibleDuffs())
    }

    // ── NULL-accountId blindness: attribution must survive either route ──

    @Test
    fun nullTxoAccountId_resolvesThroughAddressJoin_theOnDeviceShape() {
        // Every on-device row: txos.accountId NULL, core_addresses.accountId
        // carries the account. Pins the live attribution path.
        val txid = txid(7)
        insertTx(txid, context = 3)
        insertAddress("addr-null-acct", BIP44_ACCOUNT_ROW_ID)
        insertTxo(
            outpoint(7),
            amount = 12_345_678L,
            address = "addr-null-acct",
            isConfirmed = true,
            txid = txid,
            accountId = null
        )

        assertEquals(12_345_678L, eligibleDuffs())
    }

    @Test
    fun populatedTxoAccountId_resolvesWithoutAnAddressRow() {
        // The COALESCE direction: an AAR that starts populating
        // txos.accountId must not lose attribution when the core_addresses
        // row is absent (the old INNER address join dropped such rows).
        insertTxo(
            outpoint(8),
            amount = 33_000_000L,
            address = "addr-without-row",
            isConfirmed = true,
            txid = null,
            accountId = BIP44_ACCOUNT_ROW_ID
        )

        assertEquals(33_000_000L, eligibleDuffs())
    }

    @Test
    fun unattributableRow_staysExcluded() {
        // NEITHER accountId nor an address row: the engine can't route what
        // it can't attribute — must not count as ELIGIBLE. It does count as
        // UNCLASSIFIED (the complement invariant: an unattributable row is a
        // mirror gap, not a settled exclusion — dropping it from both sums
        // would let the classified remainder fabricate a false shortfall).
        insertTxo(
            outpoint(9),
            amount = 90_000_000L,
            address = "addr-unattributable",
            isConfirmed = true,
            txid = null,
            accountId = null
        )

        assertEquals(0L, eligibleDuffs())
        assertEquals(90_000_000L, unclassifiedDuffs())
    }

    @Test
    fun nonBip44Account_staysExcluded() {
        insertAddress("addr-foreign", FOREIGN_ACCOUNT_ROW_ID)
        insertTxo(outpoint(10), amount = 80_000_000L, address = "addr-foreign", isConfirmed = true, txid = null)

        assertEquals(0L, eligibleDuffs())
    }

    // ── the pre-block mirror gap (S21 mainnet, 11.10.67) ─────────────────

    @Test
    fun preBlockReceive_isUnclassified_notAProvenShortfall() {
        // The exact on-device shape at 17:20 on 2026-08-08: the engine had
        // reported the 0.09401442 DASH receive AND its InstantSend lock, but
        // the mirror carried only the txo row — no core_addresses row (that
        // arrived with the block at 17:27), no finality flag, and
        // transactions.context still 0 because the AAR never writes context 1
        // for an IS lock. The eligible sum is therefore 0, and reading that 0
        // as a shortfall refused a visibly funded wallet for nine minutes.
        val txid = txid(20)
        insertTx(txid, context = 0)
        insertTxo(
            outpoint(20),
            amount = 9_401_442L,
            address = "Xuka4REzrzDVDxy32zDhR2tk1t3LtNXXdy",
            isConfirmed = false,
            txid = txid,
            accountId = null
        )

        val evidence = evidence()
        assertEquals(0L, evidence.eligibleDuffs)
        assertEquals(9_401_442L, evidence.unclassifiedDuffs)
        // 0.03 DASH username fee: unknown, NOT refused.
        assertEquals(null, assetLockFundingVerdict(evidence, 3_000_000L))
        // 0.25 DASH contested fee: genuinely out of reach either way.
        assertEquals(false, assetLockFundingVerdict(evidence, 25_000_000L))
    }

    @Test
    fun classifiedShortfall_staysAProvenShortfall_theS22Gate() {
        // The S22 repro the preflight exists for: the mirror HAS classified
        // the wallet — 1449 duffs of final BIP44 dust, nothing pending — so
        // the 0.03 fee is provably unfundable and must still gate.
        insertAddress("addr-dust", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(21), amount = 1_449L, address = "addr-dust", isConfirmed = true, txid = null)

        val evidence = evidence()
        assertEquals(1_449L, evidence.eligibleDuffs)
        assertEquals(0L, evidence.unclassifiedDuffs)
        assertEquals(false, assetLockFundingVerdict(evidence, 3_000_000L))
    }

    @Test
    fun unclassifiedExcludesForeignAccountValue() {
        // Scope is a settled fact, not a mirror gap: an unconfirmed output on
        // a non-BIP44-account-0 account is neither eligible NOR a reason to
        // withhold a verdict — the asset lock will never fund from it.
        val txid = txid(22)
        insertTx(txid, context = 0)
        insertAddress("addr-foreign-pending", FOREIGN_ACCOUNT_ROW_ID)
        insertTxo(
            outpoint(22),
            amount = 80_000_000L,
            address = "addr-foreign-pending",
            isConfirmed = false,
            txid = txid
        )

        assertEquals(0L, eligibleDuffs())
        assertEquals(0L, unclassifiedDuffs())
    }

    @Test
    fun eligibleAndUnclassifiedNeverDoubleCount() {
        // The two sums are exact complements over the same rows: a final
        // account-0 output belongs to one bucket only.
        insertAddress("addr-final", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(23), amount = 5_000_000L, address = "addr-final", isConfirmed = true, txid = null)
        insertTxo(outpoint(24), amount = 7_000_000L, address = "addr-final", isConfirmed = false, txid = null)

        assertEquals(5_000_000L, eligibleDuffs())
        assertEquals(7_000_000L, unclassifiedDuffs())
    }

    @Test
    fun spentAndReservedRowsAreInNeitherBucket() {
        // The unclassified sum must not resurrect value the engine cannot
        // spend at all — it only forgives the mirror's classification lag.
        insertAddress("addr-unspendable", BIP44_ACCOUNT_ROW_ID)
        insertTxo(
            outpoint(25),
            amount = 11_000_000L,
            address = "addr-unspendable",
            isConfirmed = false,
            isSpent = true,
            txid = null
        )
        insertTxo(
            outpoint(26),
            amount = 13_000_000L,
            address = "addr-unspendable",
            isConfirmed = false,
            isLocked = true,
            txid = null
        )

        assertEquals(0L, eligibleDuffs())
        assertEquals(0L, unclassifiedDuffs())
    }

    // ── app-persisted IS-lock evidence (the instant_send_locks join) ─────

    @Test
    fun persistedIsLock_makesAttributedPreBlockReceiveEligible() {
        // The pre-block window with the ADDRESS already known (a receive to an
        // already-registered address): the mirror has the account attribution
        // but NO finality signal — context still 0, flags dead. Without the
        // app-persisted lock this value is merely unclassified (fail open);
        // WITH it the coins read as final BIP44 funds: a confident YES.
        val txid = txid(30)
        insertTx(txid, context = 0)
        insertAddress("addr-lock", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(30), amount = 9_401_442L, address = "addr-lock", isConfirmed = false, txid = txid)

        val baseline = evidence()
        assertEquals(0L, baseline.eligibleDuffs)
        assertEquals(9_401_442L, baseline.unclassifiedDuffs)
        assertEquals(null, assetLockFundingVerdict(baseline, 3_000_000L))

        val locked = evidence(persistedLocks = listOf(displayHexOfTxid(txid)))
        assertEquals(9_401_442L, locked.eligibleDuffs)
        assertEquals(0L, locked.unclassifiedDuffs)
        assertEquals(true, assetLockFundingVerdict(locked, 3_000_000L))
    }

    @Test
    fun persistedIsLock_unattributedRowStaysUnclassified_neverAShortfall() {
        // The S21 shape: lock persisted seconds after the receive, but
        // core_addresses (the only attribution route) is only written with
        // the block. Final-but-unattributable must fall to UNCLASSIFIED —
        // under the historical complement shape it satisfied NEITHER query,
        // vanishing from both sums and fabricating a false "proven
        // shortfall" on a visibly funded wallet.
        val txid = txid(31)
        insertTx(txid, context = 0)
        insertTxo(
            outpoint(31),
            amount = 9_401_442L,
            address = "addr-not-registered-yet",
            isConfirmed = false,
            txid = txid,
            accountId = null
        )

        val locked = evidence(persistedLocks = listOf(displayHexOfTxid(txid)))
        assertEquals(0L, locked.eligibleDuffs)
        assertEquals(9_401_442L, locked.unclassifiedDuffs)
        // Fail open (unknown), NEVER a refusal of a funded wallet.
        assertEquals(null, assetLockFundingVerdict(locked, 3_000_000L))
    }

    @Test
    fun persistedIsLock_foreignAccountValueStaysExcluded() {
        // Scope stays a settled fact even with a lock: a locked output on a
        // non-BIP44-account-0 account is neither eligible nor unclassified.
        val txid = txid(32)
        insertTx(txid, context = 0)
        insertAddress("addr-foreign-locked", FOREIGN_ACCOUNT_ROW_ID)
        insertTxo(outpoint(32), amount = 80_000_000L, address = "addr-foreign-locked", isConfirmed = false, txid = txid)

        val locked = evidence(persistedLocks = listOf(displayHexOfTxid(txid)))
        assertEquals(0L, locked.eligibleDuffs)
        assertEquals(0L, locked.unclassifiedDuffs)
    }

    @Test
    fun persistedIsLock_forADifferentTxidChangesNothing() {
        val txid = txid(33)
        insertTx(txid, context = 0)
        insertAddress("addr-other-lock", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(33), amount = 5_000_000L, address = "addr-other-lock", isConfirmed = false, txid = txid)

        val locked = evidence(persistedLocks = listOf(displayHexOfTxid(txid(99))))
        assertEquals(0L, locked.eligibleDuffs)
        assertEquals(5_000_000L, locked.unclassifiedDuffs)
    }

    @Test
    fun persistedIsLock_malformedTxidIsDroppedNotFatal() {
        insertAddress("addr-ok", BIP44_ACCOUNT_ROW_ID)
        insertTxo(outpoint(34), amount = 1_000_000L, address = "addr-ok", isConfirmed = true, txid = null)

        val locked = evidence(persistedLocks = listOf("zz-not-hex", "abcd"))
        assertEquals(1_000_000L, locked.eligibleDuffs)
        assertEquals(0L, locked.unclassifiedDuffs)
    }

    @Test
    fun persistedIsLock_spentRowStaysExcluded() {
        // The lock is finality evidence, never spendability evidence: a spent
        // (or reserved) output must not resurface because its tx was locked.
        val txid = txid(35)
        insertTx(txid, context = 0)
        insertAddress("addr-spent-locked", BIP44_ACCOUNT_ROW_ID)
        insertTxo(
            outpoint(35),
            amount = 2_000_000L,
            address = "addr-spent-locked",
            isConfirmed = false,
            isSpent = true,
            txid = txid
        )

        val locked = evidence(persistedLocks = listOf(displayHexOfTxid(txid)))
        assertEquals(0L, locked.eligibleDuffs)
        assertEquals(0L, locked.unclassifiedDuffs)
    }

    // ── the untouched exclusions stay in force ───────────────────────────

    @Test
    fun spentMidSpendLockedForeignWallet_allExcluded() {
        insertAddress("addr-exc", BIP44_ACCOUNT_ROW_ID)
        val spender = txid(11)
        insertTx(spender, context = 1)
        // spent
        insertTxo(outpoint(11), amount = 1L, address = "addr-exc", isConfirmed = true, isSpent = true, txid = null)
        // mid-spend (spendingTxid linked)
        insertTxo(
            outpoint(12),
            amount = 2L,
            address = "addr-exc",
            isConfirmed = true,
            txid = null,
            spendingTxid = spender
        )
        // engine-locked (reserved)
        insertTxo(outpoint(13), amount = 4L, address = "addr-exc", isConfirmed = true, isLocked = true, txid = null)
        // another wallet's row
        insertTxo(
            outpoint(14),
            amount = 8L,
            address = "addr-exc",
            isConfirmed = true,
            txid = null,
            ownerWalletId = otherWalletId
        )
        // the one countable control row
        insertTxo(outpoint(15), amount = 16L, address = "addr-exc", isConfirmed = true, txid = null)

        assertEquals(16L, eligibleDuffs())
    }

    // ── seeding helpers (raw SQL against the AAR's own schema) ───────────

    private fun exec(sql: String, vararg args: Any?) =
        db.openHelper.writableDatabase.execSQL(sql, args)

    private fun txid(n: Int) = ByteArray(32) { n.toByte() }

    private fun outpoint(n: Int) = ByteArray(36) { (100 + n).toByte() }

    private fun insertWallet(id: ByteArray) = exec(
        "INSERT INTO wallets (walletId, walletGroupId, birthHeight, syncedHeight, lastSynced, " +
            "isImported, createdAt, lastUpdated) VALUES (?, ?, 0, 0, 0, 0, 0, 0)",
        id,
        id
    )

    private fun insertAccount(id: Long, ownerWalletId: ByteArray, accountType: Int, standardTag: Int, accountIndex: Int) = exec(
        "INSERT INTO accounts (id, walletId, accountType, accountIndex, accountTypeName, " +
            "balanceConfirmed, balanceUnconfirmed, externalHighestUsed, internalHighestUsed, " +
            "standardTag, registrationIndex, keyClass, userIdentityId, friendIdentityId, " +
            "createdAt, lastUpdated) VALUES (?, ?, ?, ?, 'test', 0, 0, 0, 0, ?, 0, 0, ?, ?, 0, 0)",
        id,
        ownerWalletId,
        accountType,
        accountIndex,
        standardTag,
        ByteArray(0),
        ByteArray(0)
    )

    private fun insertTx(txid: ByteArray, context: Int) = exec(
        "INSERT INTO transactions (txid, transactionData, context, blockHeight, blockTimestamp, " +
            "blockPosition, hasBlockPosition, direction, transactionType, transactionTypeKind, " +
            "netAmount, label, firstSeen, createdAt, lastUpdated) " +
            "VALUES (?, ?, ?, 0, 0, 0, 0, 0, 'standard', 0, 0, '', 0, 0, 0)",
        txid,
        ByteArray(0),
        context
    )

    private fun insertAddress(address: String, accountId: Long?) = exec(
        "INSERT INTO core_addresses (address, publicKey, poolTypeTag, addressIndex, derivationPath, " +
            "isUsed, firstSeenHeight, lastSeenHeight, balance, createdAt, lastUpdated, accountId) " +
            "VALUES (?, ?, 0, 0, '', 1, 0, 0, 0, 0, 0, ?)",
        address,
        ByteArray(0),
        accountId
    )

    private fun insertTxo(
        outpoint: ByteArray,
        amount: Long,
        address: String,
        isConfirmed: Boolean,
        isInstantLocked: Boolean = false,
        isLocked: Boolean = false,
        isSpent: Boolean = false,
        isCoinbase: Boolean = false,
        txid: ByteArray? = null,
        spendingTxid: ByteArray? = null,
        accountId: Long? = null,
        ownerWalletId: ByteArray = walletId
    ) = exec(
        "INSERT INTO txos (outpoint, vout, amount, address, scriptPubKey, height, isCoinbase, " +
            "isConfirmed, isInstantLocked, isLocked, isSpent, createdAt, lastUpdated, walletId, " +
            "txid, spendingTxid, spendingInputIndex, accountId, coreAddressId) " +
            "VALUES (?, 0, ?, ?, ?, 0, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, NULL, ?, NULL)",
        outpoint,
        amount,
        address,
        ByteArray(0),
        if (isCoinbase) 1 else 0,
        if (isConfirmed) 1 else 0,
        if (isInstantLocked) 1 else 0,
        if (isLocked) 1 else 0,
        if (isSpent) 1 else 0,
        ownerWalletId,
        txid,
        spendingTxid,
        accountId
    )
}
