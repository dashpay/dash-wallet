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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.service.platform.TopUpRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Utils
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.wallet.OneTimeOrchardKey
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of [SdkShieldedInviteCreation.createShieldedInvite]: the raw
 * shielded deep link [linkData] — the source of the invite PREVIEW
 * (user/displayName/avatar params) — plus the [shareLink] the user actually
 * shares/copies. [shareLink] is the AppsFlyer OneLink wrapping that deep link
 * (OG preview + install redirect, H1), or the raw deep-link string when
 * OneLink generation was unavailable.
 */
data class ShieldedInvite(
    val linkData: InvitationLinkData,
    val shareLink: String
)

// ── Pure helpers ──────────────────────────────────────────────────────────

/**
 * The fixed Type-20 exit denomination (in Platform credits) a SHIELDED
 * invitation funds, for a username of the given contested-ness — the same
 * fee → denomination mapping the from-pool username creation uses
 * ([chooseShieldedIdentityDenominationCredits]): non-contested → 0.03 DASH,
 * contested → 0.25 DASH (the 0.25 lets the claimer's identity cover the ~0.2
 * prefunded voting balance a contested DPNS registration debits). Null when
 * no denomination covers the fee (should not happen for the fixed fees).
 *
 * Funding a note at an ALLOWED EXIT DENOMINATION is what makes the whole note
 * claimable: an invite minted at 0.3 (the pre-v13 contested mapping) can only
 * ever exit at 0.25, so its last 0.05 lands in the CLAIMER's own shielded
 * change rather than the new identity's credits. Live 0.3 links still claim
 * correctly (see [inviteClaimDenominationLadder]); new ones no longer split.
 */
internal fun shieldedInviteDenominationCredits(feeCreditsForKind: Long): Long? =
    chooseShieldedIdentityDenominationCredits(feeCreditsForKind)

/**
 * How an invite's [totalCredits] denomination is laid out on the wire: TWO
 * even sub-target notes to the one-time address rather than one full-target
 * note (3_000_000_000 → 1.5e9 + 1.5e9; 25_000_000_000 → 12.5e9 + 12.5e9).
 * The TOTAL is unchanged, so the link's `amt`/`fundingCredits` and the exit
 * denomination ladder are untouched — only the note layout differs.
 *
 * WHY two notes, not one: Orchard pads every bundle to a 2-action minimum,
 * and a padding action's dummy nullifier is RANDOMLY generated. An identity
 * id derived from the published nullifiers is therefore only reproducible
 * offline when at least TWO REAL notes are spent — with a single real note a
 * retry builds a different dummy, hence a different id, and the claimer can
 * no longer recognise the identity its own earlier attempt created.
 *
 * Two sub-target notes structurally FORCE the claim-side spend to select
 * both: greedy largest-first selection cannot stop on a note that does not
 * cover the target. That keeps the padding action — and its random nullifier
 * — out of the bundle. See `shielded_identity_id_is_reproducible` in rs-dpp,
 * which states the same rule next to the id derivation it guards.
 *
 * Odd totals put the extra credit in the SECOND note; both notes stay below
 * the target either way, which is the only property selection depends on.
 */
internal fun inviteFundingSplit(totalCredits: Long): List<Long> {
    require(totalCredits >= 2) { "invite denomination too small to split: $totalCredits" }
    val first = totalCredits / 2
    return listOf(first, totalCredits - first)
}

/**
 * Fee margin, in Platform credits, the inviter's pool must hold ON TOP of the
 * invite denomination before a shielded invite is attempted.
 *
 * The invite mint is a Type-16 shielded transfer, and its consensus fee is
 * carved from the pool IN ADDITION to the two funded notes — unlike the
 * Type-20 identity create, whose metered fee is taken out of the denomination
 * itself. A pool holding exactly the denomination therefore cannot mint an
 * invite: it passes a bare `>= denomination` check and then fails opaquely at
 * the FFI's note selection.
 *
 * The SDK exposes the exact fee only as a runtime FFI call
 * (`ShieldedProver.estimateFee`), so the margin is pinned app-side. Derivation
 * from the consensus constants (rs-platform-version / rs-dpp
 * `compute_minimum_shielded_fee`, current values):
 *
 *     min_fee(n_actions) = 100_000_000                    (proof verification)
 *                        + n × 22_000_000                 (per-action processing)
 *                        + n × 344 × 27_400 = n × 9_425_600 (per-action storage)
 *                        = 100_000_000 + n × 31_425_600 credits
 *
 * The mint's bundle has at least 3 actions (two invite notes + change on the
 * output side), so the floor is 194_276_800 credits ≈ 0.00194 DASH; each
 * additional pool note the spend has to select adds one action
 * (31_425_600 credits). 0.003 DASH covers bundles up to 6 actions — the same
 * 0.003 margin [de.schildbach.wallet.Constants.SHIELDED_FEE_MARGIN] uses for
 * the shield-first guidance (equality pinned by `InviteFeeGateTest`).
 */
internal const val SHIELDED_INVITE_FEE_MARGIN_CREDITS = 300_000_000L // 0.003 DASH

/**
 * Lowercase hex of a 32-byte scalar — the wire form the shielded invitation
 * link carries the one-time Orchard spending key as (see
 * [InvitationLinkData.oneTimeKey]).
 */
internal fun bytes32ToHex(bytes: ByteArray): String {
    require(bytes.size == 32) { "expected 32 bytes, got ${bytes.size}" }
    return Utils.HEX.encode(bytes)
}

// ── Source seam ─────────────────────────────────────────────────────────

/**
 * Seam over the inviter-side SDK surface a SHIELDED invitation needs — the
 * one-time Orchard keygen ([org.dashfoundation.dashsdk.wallet.generateOneTimeOrchardKey])
 * plus the Type-16 shielded transfer that funds the invite note — so the
 * flag/preflight/no-double-broadcast orchestration in
 * [SdkShieldedInviteCreation] is host-JVM unit-testable without `libdash_sdk`.
 */
interface ShieldedInviteSource {
    /** Same contract as [ShieldedUsernameSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * Generate a fresh single-use Orchard key pair: the 32-byte spending key
     * the invitation carries and the 43-byte default Orchard address the
     * inviter funds. All key material is generated Rust-side.
     */
    suspend fun generateOneTimeOrchardKey(): OneTimeOrchardKey

    /**
     * Type 16: fund one note per entry of [amountsCredits] — all to the SAME
     * [recipientRaw43] (the one-time key's 43-byte Orchard address) — from the
     * inviter's own shielded pool, in a SINGLE multi-output transfer. Orchard
     * derives independent notes for a repeated address, which is the point:
     * see [inviteFundingSplit] for why an invite is funded as two notes rather
     * than one. Blocks for the ~30s Halo 2 proof. Throws on failure
     * (classified by the caller).
     */
    suspend fun fundNotesToRaw43(
        walletIdHex: String,
        recipientRaw43: ByteArray,
        amountsCredits: List<Long>
    )

    /**
     * The current chain-tip height, used as the invitation's advisory
     * funding-height scan hint (`bh`). Null when unknown (the claim FFI then
     * scans without a hint).
     */
    suspend fun currentChainTipHeight(): Int?
}

/** Production [ShieldedInviteSource]. */
internal class DashSdkShieldedInviteSource(
    private val service: DashSdkService,
    private val walletData: WalletData
) : ShieldedInviteSource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    private fun walletId(walletIdHex: String): ByteArray =
        checkNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }

    override suspend fun boundWalletIdOrNull(): String? =
        manager().wallets.value.keys.singleOrNull()

    override suspend fun generateOneTimeOrchardKey(): OneTimeOrchardKey =
        org.dashfoundation.dashsdk.wallet.generateOneTimeOrchardKey()

    override suspend fun fundNotesToRaw43(
        walletIdHex: String,
        recipientRaw43: ByteArray,
        amountsCredits: List<Long>
    ) = manager().shieldedTransferMulti(
        walletId = walletId(walletIdHex),
        outputs = amountsCredits.map { recipientRaw43 to it }
    )

    override suspend fun currentChainTipHeight(): Int? =
        walletData.wallet?.lastBlockSeenHeight?.takeIf { it > 0 }
}

// ── Service ─────────────────────────────────────────────────────────────

/**
 * Shielded (L2) INVITER path — the private-invitation counterpart of the
 * from-pool username creation ([SdkShieldedUsernameCreation]). Instead of
 * pre-creating an L1 asset-lock identity (the unchanged [SendInviteWorker] /
 * [de.schildbach.wallet.service.platform.TopUpRepository.createInviteFundingTransaction]
 * path), it:
 *
 * 1. generates a single-use Orchard key (32-byte spending key + 43-byte
 *    address) Rust-side;
 * 2. funds exactly the fixed exit denomination (0.03 non-contested / 0.25
 *    contested) to that address from the inviter's OWN shielded pool, as TWO
 *    even notes in a single Type 16 multi-output transfer
 *    ([inviteFundingSplit]);
 * 3. builds an [InvitationLinkData.createShielded] link carrying the spending
 *    key (hex) + the funding chain-tip height — the claimer spends that note
 *    to create their identity ([SdkShieldedUsernameCreation.createIdentityFromInvitation]).
 *
 * Behind [de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.USE_KOTLIN_SDK_SHIELDED]
 * (default OFF): while the flag is off every entry point returns
 * [SdkWriteResult.NotBroadcast] without touching the SDK, and the L1 invite
 * path is byte-identical to before.
 */
@Singleton
class SdkShieldedInviteCreation internal constructor(
    private val source: ShieldedInviteSource,
    private val dashPayConfig: de.schildbach.wallet.ui.dashpay.utils.DashPayConfig,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val invitationsDao: InvitationsDao,
    /** Same fee → denomination input as [SdkShieldedUsernameCreation.feeCredits]. */
    private val feeCredits: (contested: Boolean) -> Long,
    /**
     * Wraps the raw shielded deep link in an AppsFlyer OneLink (H1),
     * returning the OneLink short URL — or `null` when generation fails or
     * times out, so the invite falls back to sharing the raw deep link and
     * never hangs. Injected as a function (mirroring [feeCredits]) so the
     * SDK layer stays decoupled from [TopUpRepository] and unit-testable.
     */
    private val generateOneLink: suspend (InvitationLinkData) -> String? = { null }
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: de.schildbach.wallet.ui.dashpay.utils.DashPayConfig,
        shieldedBalanceService: ShieldedBalanceService,
        walletData: WalletData,
        invitationsDao: InvitationsDao,
        topUpRepository: TopUpRepository
    ) : this(
        source = DashSdkShieldedInviteSource(sdkService, walletData),
        dashPayConfig = dashPayConfig,
        shieldedBalanceService = shieldedBalanceService,
        invitationsDao = invitationsDao,
        feeCredits = { contested ->
            val fee = if (contested) Constants.DASH_PAY_FEE_CONTESTED else Constants.DASH_PAY_FEE
            dashToCredits(Dash(fee.value))
        },
        generateOneLink = { link ->
            // Bounded so a stuck AppsFlyer callback can't hang the invite;
            // any failure/timeout → null → raw deep-link fallback.
            withTimeoutOrNull(ONE_LINK_TIMEOUT_MS) {
                runCatching { topUpRepository.createShieldedAppsFlyerLink(link).shortLink }
                    .onFailure { log.warn("shielded invite OneLink generation failed", it) }
                    .getOrNull()
            }
        }
    )

    /**
     * Create a shielded invitation for [username]/[displayName]/[avatarUrl]
     * (the INVITER's profile), funded from the inviter's shielded pool.
     * [contested] selects the exit denomination (the inviter picks the
     * username kind at the fee step, exactly as for an L1 invite). On success
     * returns the ready-to-share [InvitationLinkData] and persists a tracking
     * [Invitation] row. Nothing is spent when any preflight fails; the funding
     * transfer is attempted once and classified via [classifyBroadcastFailure].
     */
    suspend fun createShieldedInvite(
        username: String,
        displayName: String,
        avatarUrl: String,
        contested: Boolean
    ): SdkWriteResult<ShieldedInvite> {
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")

        val fee = try {
            feeCredits(contested)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("invite fee unavailable", t)
        }
        val denominationCredits = shieldedInviteDenominationCredits(fee)
            ?: return notBroadcast("no shielded denomination covers the invite fee", null)

        // The inviter funds the note from their OWN pool, so — unlike the
        // claim path — the pool balance IS the funding source and must cover
        // the denomination with a trustworthy (READY) balance. The Type-16
        // transfer's fee is carved from the pool ON TOP of the funded notes
        // (see [SHIELDED_INVITE_FEE_MARGIN_CREDITS]), so the bar is
        // denomination + fee margin — a pool holding exactly the denomination
        // would pass a bare check and then fail opaquely at the FFI.
        if (!shieldedBalanceService.ensureShieldedReady()) {
            return notBroadcast(SdkShieldedUsernameCreation.REASON_RUNTIME_NOT_READY, null)
        }
        if (shieldedBalanceService.shieldedSyncStatus.value != ShieldedSyncStatus.READY) {
            return notBroadcast(SdkShieldedUsernameCreation.REASON_POOL_STILL_SYNCING, null)
        }
        val balance = try {
            shieldedBalanceService.observeShieldedBalance().first()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("shielded balance unavailable", t)
        }
        val requiredCredits = denominationCredits + SHIELDED_INVITE_FEE_MARGIN_CREDITS
        if (balance < creditsToDash(requiredCredits)) {
            return notBroadcast(
                "shielded balance below the ${creditsToDash(requiredCredits).toPlainString()} " +
                    "DASH invite denomination + transfer-fee margin",
                null
            )
        }

        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast("app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("SDK bootstrap/bind lookup failed", t)
        }

        val key = try {
            source.generateOneTimeOrchardKey()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("one-time key generation failed", t)
        }

        // THE funding transfer — one attempt, ~30s Halo 2 proof. One transfer,
        // TWO notes to the same one-time address (see [inviteFundingSplit]).
        try {
            source.fundNotesToRaw43(walletId, key.address, inviteFundingSplit(denominationCredits))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return when (val classified = classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("shielded invite funding rejected pre-broadcast", t)
                    classified
                }
                else -> {
                    log.error(
                        "shielded invite funding outcome unconfirmed — the note MAY exist; do NOT retry",
                        t
                    )
                    SdkWriteResult.Ambiguous(t)
                }
            }
        }

        // Advisory scan hint; a missing height is fine (claim scans without one).
        val fundingHeight = try {
            source.currentChainTipHeight()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("chain-tip height unavailable for the shielded invite hint", t)
            null
        }

        val link = InvitationLinkData.createShielded(
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            oneTimeKeyHex = bytes32ToHex(key.spendingKey),
            fundingHeight = fundingHeight ?: 0,
            // The note's value — the ONLY way the claimer can learn which
            // tier this invite paid for. A shielded invite has no on-chain
            // asset lock to read the amount off, and the claim FFI takes the
            // denomination as an input rather than reporting the note's, so
            // without this the claim screen cannot tell a 0.25 contested
            // invite from a 0.03 non-contested one.
            fundingCredits = denominationCredits
        )

        // Wrap the raw deep link in an AppsFlyer OneLink so the shared/copied
        // link matches an L1 invite (preview + install redirect, H1); fall
        // back to the raw deep link if generation is unavailable.
        val shareLink = generateOneLink(link) ?: link.link.toString()

        // Track it in the invite history (best-effort — the note is already
        // funded, so a persistence failure must not fail the invite). The
        // Invitation entity assumes a pre-created identity id; a shielded
        // invite has no claimer identity yet, so we key it by a synthetic
        // 32-byte id derived from the one-time address (valid + unique, so the
        // history avatar hash renders) and stash the shareable OneLink.
        try {
            persistTracking(key.address, shareLink)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("shielded invite tracking persist failed — the invite is still valid", t)
        }

        return SdkWriteResult.Broadcast(ShieldedInvite(link, shareLink))
    }

    private suspend fun persistTracking(oneTimeAddress43: ByteArray, shareLink: String) {
        val syntheticUserId = Identifier.from(Sha256Hash.hash(oneTimeAddress43)).toString()
        invitationsDao.insert(
            Invitation(
                fundingAddress = SHIELDED_FUNDING_ADDRESS_PREFIX + Utils.HEX.encode(oneTimeAddress43),
                userId = syntheticUserId,
                txid = null,
                createdAt = System.currentTimeMillis(),
                sentAt = System.currentTimeMillis(),
                shortDynamicLink = shareLink,
                dynamicLink = shareLink
            )
        )
    }

    private fun notBroadcast(reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("shielded invite not created ({})", reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_SHIELDED; treating as off", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkShieldedInviteCreation::class.java)

        /** Cap on the AppsFlyer OneLink callback so a stuck SDK can't hang the invite (H1). */
        private const val ONE_LINK_TIMEOUT_MS = 15_000L

        /**
         * Primary-key prefix marking an [Invitation] row as a SHIELDED (L2)
         * invite — there is no L1 funding address, so the row is keyed by this
         * prefix + the one-time Orchard address hex.
         */
        const val SHIELDED_FUNDING_ADDRESS_PREFIX = "shielded:"
    }
}
