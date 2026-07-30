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

import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.BlockchainStateProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Pure helpers (host-JVM testable) ──────────────────────────────────

/**
 * The ACCOUNT-LEVEL DIP-9 CoinJoin derivation path for [params], account 0,
 * in the exact textual form the SDK's Rust side parses.
 *
 * FORMAT CONTRACT (funds-critical — see the class KDoc for how it was
 * verified): `m/9'/<coinType>'/4'/0'`, i.e. a literal `m` first component,
 * `/` separators, and an apostrophe hardened marker. `coinType` is `5` on
 * mainnet and `1` on every other network.
 */
fun coinJoinAccountPath(params: NetworkParameters): String {
    // key-wallet's AccountType::derivation_path uses `if network == Mainnet { 5 } else { 1 }`;
    // dashj's DerivationPathFactory uses the same coin types, which is why the
    // two engines agree on which keys hold the mixed coins.
    val coinType = if (NetworkParameters.ID_MAINNET == params.id) 5 else 1
    return "m/9'/$coinType'/4'/0'"
}

/**
 * Every unspent output the wallet's DIP-9 CoinJoin keychain owns, or null
 * when that cannot be determined (no CoinJoin extension, or dashj threw).
 *
 * Null means "unknown", NOT "none" — callers must fail closed on it.
 *
 * `CoinJoinExtension.getOutputs()` buckets the wallet's unspents by CoinJoin
 * denomination after a keychain-membership test; the sentinel bucket `-2`
 * holds the outputs whose key is NOT on the CoinJoin keychain, so every
 * other bucket (including `0`, the non-denominated remainder) is exactly the
 * `m/9'` account's UTXO set. Deliberately NOT `WalletEx.getCoinJoinBalance()`,
 * which counts only fully-mixed DENOMINATED coins and therefore understates
 * what the drain/shield would actually move.
 */
fun coinJoinOutputsOrNull(wallet: Wallet): List<TransactionOutput>? = try {
    val coinJoin = (wallet as? WalletEx)?.coinJoin
    coinJoin?.outputs
        ?.filterKeys { it != COIN_JOIN_FOREIGN_BUCKET }
        ?.values
        ?.flatten()
} catch (e: Exception) {
    LoggerFactory.getLogger("CoinJoinFundsMigration")
        .warn("failed to enumerate the CoinJoin keychain outputs", e)
    null
}

/** Total value on the CoinJoin keychain, or null when it cannot be read. */
fun coinJoinBalanceOrNull(wallet: Wallet): Coin? =
    coinJoinOutputsOrNull(wallet)?.fold(Coin.ZERO) { total, output -> total.add(output.value) }

/**
 * The `getOutputs()` bucket key for outputs whose key is NOT on the CoinJoin
 * keychain (dashj `CoinJoinExtension.getOutputs`).
 */
private const val COIN_JOIN_FOREIGN_BUCKET = -2

/**
 * Chain-tip freshness that lets an already-synced wallet qualify without
 * waiting out the post-launch re-sync (`percentageSync` restarts at 0 on
 * every launch). Mirrors the shielded-transfer screen's own gate.
 */
private const val CHAIN_TIP_FRESHNESS_MS = 60L * 60L * 1000L

/**
 * Is the L1 chain view current enough to TRUST a zero/non-zero CoinJoin
 * balance? A mid-sync wallet reports partial UTXO state, so prompting on it
 * would either nag a user with no mixed funds or quote the wrong amount.
 */
internal fun BlockchainState?.isSyncedEnoughForMixedFundsCheck(
    nowMillis: Long = System.currentTimeMillis()
): Boolean {
    if (this == null) return false
    if (isSynced()) return true
    val tipTime = bestChainDate?.time ?: return false
    return !replaying && !syncFailed() && nowMillis - tipTime < CHAIN_TIP_FRESHNESS_MS
}

// ── The migration service ─────────────────────────────────────────────

/** What the user chose, and how it went. */
enum class MixedFundsMigrationOutcome {
    /** Broadcast/submitted; the CoinJoin account is being emptied. */
    STARTED,

    /** Nothing was spent — safe to re-offer immediately. */
    NOT_ATTEMPTED,

    /** The outcome is unknown; the transaction MAY be on the network. */
    UNCONFIRMED
}

/**
 * Post-upgrade MIXED-FUNDS migration.
 *
 * CoinJoin mixing was removed from the app, but wallets that mixed in the
 * past still hold coins on the DIP-9 CoinJoin keychain (`m/9'/coin'/4'/0'`).
 * Those coins are invisible to the SDK's default BIP44-only spend paths — the
 * live symptom in `docs/kotlin-sdk-issues-to-file.md:113` (a 1.534 DASH wallet
 * reporting 0.09 DASH available). This service detects them and performs
 * whichever of the two one-time migrations the user picks:
 *
 * - [shieldMixedFunds] — asset-lock them into the SHIELDED (Orchard) balance,
 *   funded strictly from the CoinJoin account via the SDK's `fundingPath`.
 * - [combineIntoUnmixedBalance] — drain them to a fresh BIP44 receive address
 *   of the same wallet, keeping them spendable on L1 but DE-MIXING them.
 *
 * ## Privacy invariant
 *
 * Both operations are SINGLE-SOURCE-ACCOUNT transactions. Neither ever unions
 * CoinJoin UTXOs with BIP44 UTXOs — the design that was rejected in review for
 * "silently crossing privacy domains". Option A keeps the coins private (they
 * land in the shielded pool); option B does not, and the UI says so.
 *
 * ## The `fundingPath` format, and how it was verified
 *
 * `m/9'/5'/4'/0'` (mainnet) / `m/9'/1'/4'/0'` (everything else). Verified
 * against the pinned SDK sources rather than assumed:
 *
 * 1. `rs-platform-wallet-ffi/src/shielded_send.rs` `parse_optional_derivation_path`
 *    parses the UTF-8 bytes with `key_wallet::bip32::DerivationPath::from_str`.
 * 2. That `FromStr` (`key-wallet/src/bip32.rs:1297`) REQUIRES the first
 *    `/`-separated component to be literally `"m"` and otherwise errors, and
 *    `ChildNumber::from_str` (`:887`) treats a trailing `'` or `h` as hardened.
 * 3. `rs-platform-wallet/src/wallet/asset_lock/build.rs:355-382` compares the
 *    parsed path for EQUALITY against each funds account's
 *    `managed_account_type().to_account_type().derivation_path(network)`.
 * 4. `key-wallet/src/account/account_type.rs:344-357` builds exactly
 *    `[9', coin_type', 4', index']` for `AccountType::CoinJoin { index }`,
 *    with `coin_type = 5` on mainnet and `1` otherwise (`:314-318`).
 *
 * The CoinJoin account itself exists on every SDK wallet the app creates:
 * `DashSdkServiceImpl` passes `createDefaultAccounts = true`, which reaches
 * key-wallet's `helper.rs` `add_account(AccountType::CoinJoin { index: 0 })`.
 */
@Singleton
class CoinJoinFundsMigrationService @Inject constructor(
    private val walletData: WalletData,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val sdkL1SendService: SdkL1SendService,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val dashPayConfig: DashPayConfig
) {
    /**
     * The mixed-funds balance to offer to migrate, or null when there is
     * nothing to do — no wallet, no CoinJoin keychain, an unreadable
     * keychain, a zero balance, or an L1 view too stale to trust.
     *
     * Never throws: any failure reads as "nothing to migrate", so a broken
     * probe can only under-prompt, never mis-prompt.
     */
    suspend fun mixedFundsToMigrate(): Coin? = withContext(Dispatchers.IO) {
        try {
            val state = blockchainStateProvider.getState()
            if (!state.isSyncedEnoughForMixedFundsCheck()) {
                log.debug("mixed-funds probe skipped: L1 view not current enough to trust")
                return@withContext null
            }
            @Suppress("DEPRECATION")
            val wallet = walletData.wallet ?: return@withContext null
            val balance = coinJoinBalanceOrNull(wallet) ?: return@withContext null
            balance.takeIf { it.isPositive }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("mixed-funds probe failed; treating the wallet as having none", t)
            null
        }
    }

    /**
     * Should the one-time prompt be shown right now? True only when the user
     * has not already completed a migration AND the wallet still holds mixed
     * funds. A FAILED attempt deliberately leaves the completion flag unset,
     * so the prompt returns on the next startup (retry-on-failure); a
     * SUCCESSFUL one empties the account, so this goes false on its own even
     * before the flag lands.
     */
    suspend fun shouldPrompt(): Boolean {
        val alreadyHandled = try {
            dashPayConfig.get(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE) == true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Fail CLOSED on a read error: never nag when we cannot tell.
            log.warn("failed to read the mixed-funds migration flag; not prompting", t)
            true
        }
        if (alreadyHandled) return false
        if (!anyOptionOperable()) {
            // Do NOT prompt when neither option could run: a failed attempt
            // deliberately leaves the flag unset, so offering a choice that
            // is guaranteed to fail would re-nag on every single launch.
            log.debug("mixed-funds prompt suppressed: neither migration path is operable yet")
            return false
        }
        return mixedFundsToMigrate() != null
    }

    /**
     * Is at least ONE of the two migrations currently runnable? Both spend
     * through the SDK engine (dashj has no CoinJoin→shielded path and cannot
     * point the SDK builder at an account), so both are inert while the
     * engine is not the L1 owner and the shielded funding gate is shut.
     * Conservative: [SdkL1SendService.cutoverCommitted] is a subset of the
     * drain's own enablement rule, so this can only under-prompt.
     */
    private suspend fun anyOptionOperable(): Boolean = try {
        shieldedBalanceService.isWalletShieldingAvailable() || sdkL1SendService.cutoverCommitted()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        log.warn("failed to probe the migration paths; not prompting", t)
        false
    }

    /**
     * OPTION A — shield the mixed funds.
     *
     * Locks [amount] from the CoinJoin account ONLY (SDK `fundingPath`) and
     * shields it to the wallet's own Orchard address. The caller passes the
     * balance from [mixedFundsToMigrate] minus [L1_FEE_RESERVE_DUFFS]; the
     * engine's LargestFirst selection then has to reach for every CoinJoin
     * UTXO to cover it, and whatever the fee leaves over returns to BIP44 as
     * change — so the CoinJoin account still ends up empty.
     */
    suspend fun shieldMixedFunds(amount: Dash): MixedFundsMigrationOutcome {
        val path = coinJoinAccountPath(walletData.networkParameters)
        log.info("mixed-funds migration: shielding {} from {}", amount, path)
        val result = shieldedBalanceService.shieldMixedFundsFromWallet(amount, path)
        return finish("shield", result.toOutcome())
    }

    /**
     * OPTION B — combine the mixed funds into the unmixed (BIP44) balance.
     *
     * A single-account CoinJoin → BIP44 drain to a FRESH receive address of
     * this same wallet. Keeps the funds spendable on L1 and DE-MIXES them:
     * the transaction publicly links the previously mixed coins to the
     * destination address.
     */
    suspend fun combineIntoUnmixedBalance(): MixedFundsMigrationOutcome {
        val destination = try {
            // A FRESH address, not the current one: reusing an address the
            // user has already published would link the mixed coins to it.
            walletData.freshReceiveAddressString()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("mixed-funds migration: could not derive an own receive address", t)
            return MixedFundsMigrationOutcome.NOT_ATTEMPTED
        }
        log.info("mixed-funds migration: draining the CoinJoin account to an own BIP44 address")
        val result = sdkL1SendService.drainCoinJoinAccountTo(destination)
        return finish("combine", result.toOutcome())
    }

    /** Record the migration as handled unless it provably did nothing. */
    private suspend fun finish(what: String, outcome: MixedFundsMigrationOutcome): MixedFundsMigrationOutcome {
        if (outcome == MixedFundsMigrationOutcome.NOT_ATTEMPTED) {
            // Nothing was spent — leave the flag unset so the prompt returns
            // on the next startup. This is the retry path.
            log.warn("mixed-funds migration ({}) did not run; the prompt will reappear", what)
            return outcome
        }
        try {
            dashPayConfig.set(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE, true)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Harmless: the account is (being) emptied, so [shouldPrompt]'s
            // balance check already suppresses the prompt.
            log.warn("failed to persist the mixed-funds migration flag", t)
        }
        return outcome
    }

    /** Let the user dismiss the prompt for good without migrating. */
    suspend fun dismissForever() {
        try {
            dashPayConfig.set(DashPayConfig.MIXED_FUNDS_MIGRATION_DONE, true)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to persist the mixed-funds migration dismissal", t)
        }
    }

    private fun SdkWriteResult<*>.toOutcome(): MixedFundsMigrationOutcome = when (this) {
        is SdkWriteResult.Broadcast -> MixedFundsMigrationOutcome.STARTED
        is SdkWriteResult.NotBroadcast -> MixedFundsMigrationOutcome.NOT_ATTEMPTED
        is SdkWriteResult.Ambiguous -> MixedFundsMigrationOutcome.UNCONFIRMED
    }

    companion object {
        private val log = LoggerFactory.getLogger(CoinJoinFundsMigrationService::class.java)

        /**
         * Headroom left unlocked so the L1 asset-lock fee has somewhere to
         * come from. Same figure as the send-all reserve
         * ([SEND_ALL_FEE_RESERVE_DUFFS]); whatever the fee does not consume
         * comes back as BIP44 change, so nothing is stranded on `m/9'`.
         */
        const val L1_FEE_RESERVE_DUFFS = SEND_ALL_FEE_RESERVE_DUFFS
    }
}
