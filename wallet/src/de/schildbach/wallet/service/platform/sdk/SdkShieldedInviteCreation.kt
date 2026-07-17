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
import org.dash.wallet.common.WalletDataProvider
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
 * ([chooseShieldedIdentityDenominationCredits]): non-contested → 0.1 DASH,
 * contested → 0.3 DASH (the 0.3 lets the claimer's identity cover the ~0.2
 * prefunded voting balance a contested DPNS registration debits). Null when
 * no denomination covers the fee (should not happen for the fixed fees).
 */
internal fun shieldedInviteDenominationCredits(feeCreditsForKind: Long): Long? =
    chooseShieldedIdentityDenominationCredits(feeCreditsForKind)

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
     * Type 16: fund a note of exactly [amountCredits] to [recipientRaw43]
     * (the one-time key's 43-byte Orchard address) from the inviter's own
     * shielded pool. Blocks for the ~30s Halo 2 proof. Throws on failure
     * (classified by the caller).
     */
    suspend fun fundNoteToRaw43(walletIdHex: String, recipientRaw43: ByteArray, amountCredits: Long)

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
    private val walletData: WalletDataProvider
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

    override suspend fun fundNoteToRaw43(
        walletIdHex: String,
        recipientRaw43: ByteArray,
        amountCredits: Long
    ) = manager().shieldedTransfer(
        walletId = walletId(walletIdHex),
        recipientRaw43 = recipientRaw43,
        amount = amountCredits
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
 * 2. funds a note of exactly the fixed exit denomination (0.1 non-contested /
 *    0.3 contested) to that address from the inviter's OWN shielded pool
 *    (Type 16 transfer);
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
        walletData: WalletDataProvider,
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
        // the denomination with a trustworthy (READY) balance.
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
        if (balance < creditsToDash(denominationCredits)) {
            return notBroadcast(
                "shielded balance below the ${creditsToDash(denominationCredits).toPlainString()} " +
                    "DASH invite denomination",
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

        // THE funding transfer — one attempt, ~30s Halo 2 proof.
        try {
            source.fundNoteToRaw43(walletId, key.address, denominationCredits)
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
            fundingHeight = fundingHeight ?: 0
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
