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

import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.shielded.shieldedMaxFeeAdjustment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.dashfoundation.dashsdk.credits.FundingInput
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Pure helpers ──────────────────────────────────────────────────────

/**
 * Greedily pack Platform-address funding inputs from [candidates] until their
 * combined credits cover [targetCredits], spending only what is needed from
 * the final address (mirrors the SDK example app's `packInputs` /
 * Swift `TopUpIdentityView.buildInputs`). Never selects MORE than the target:
 * the target is the invite overage, and anything else on the addresses is not
 * this flow's to move. When the candidates cannot cover the full target the
 * pack covers as much as they can (partial top-up is better than stranding
 * the whole overage behind an unrelated shortfall); an empty list means the
 * addresses hold nothing at all.
 */
internal fun packOverageInputs(candidates: List<FundingInput>, targetCredits: Long): List<FundingInput> {
    if (targetCredits <= 0) return emptyList()
    var remaining = targetCredits
    val picked = ArrayList<FundingInput>()
    for (input in candidates.sortedByDescending { it.credits }) {
        if (remaining <= 0) break
        if (input.credits <= 0) continue
        val spend = minOf(input.credits, remaining)
        picked.add(FundingInput(input.addressType, input.hash, spend))
        remaining -= spend
    }
    return picked
}

/**
 * The provable, un-topped overage of an ALREADY-COMPLETED shielded invite
 * claim, derived entirely from persisted state — or null when nothing may be
 * reconciled. This is the retro-fit path for claims whose overage record was
 * never written (observed live: the S22's 0.05 overage produced zero worker
 * activity after a successful resume-path claim), and it must be safe to call
 * on EVERY app launch:
 *
 * - [identityIdBase58]/[creationState] prove the claim COMPLETED — the
 *   persisted record carries the on-chain identity id and a state at or past
 *   IDENTITY_REGISTERED;
 * - [usingInvite]/[inviteIsShielded] scope it to shielded invite claims;
 * - [fundingCreditsFromLink] is the persisted INVITE_LINK's `amt`, believed
 *   under the same rule as the claim path (a real invite note value) — the
 *   overage is `amt − largestExitDenominationAtOrBelow(amt)`, the clamped
 *   first-rung spend. Unlike the claim path, the actually-spent denomination
 *   is not persisted, so a descended (lied-amt) claim cannot be excluded
 *   here; the worker's give-up rule is the backstop (an overage that never
 *   existed finds an empty pool and clears itself);
 * - [hasPendingRecord]/[alreadyReconciledIdentity] make it ONE-SHOT: a
 *   pending record is already being drained, and the reconciled-identity
 *   marker (set by every record persist AND every record clear) stops a
 *   drained record from being re-minted forever after.
 */
internal fun reconcilableOverageCredits(
    identityIdBase58: String?,
    creationState: IdentityCreationState,
    usingInvite: Boolean,
    inviteIsShielded: Boolean,
    fundingCreditsFromLink: Long?,
    hasPendingRecord: Boolean,
    alreadyReconciledIdentity: String?
): Long? {
    if (identityIdBase58 == null) return null
    if (creationState < IdentityCreationState.IDENTITY_REGISTERED) return null
    if (!usingInvite || !inviteIsShielded) return null
    if (hasPendingRecord) return null
    if (alreadyReconciledIdentity == identityIdBase58) return null
    val believed = fundingCreditsFromLink?.takeIf { it in SHIELDED_INVITE_NOTE_VALUES_CREDITS }
        ?: return null
    val clampedSpend = largestExitDenominationAtOrBelow(believed) ?: return null
    return (believed - clampedSpend).takeIf { it > 0 }
}

/** One durable pending invite-claim overage (see [ShieldedInviteOverageTopUp]). */
internal data class PendingInviteOverage(
    /** The claimed identity the overage belongs on (base58). */
    val identityIdBase58: String,
    /** Gross overage in Platform credits (note value − exit denomination). */
    val overageCredits: Long,
    /**
     * Set once the unshield step SUCCEEDED: the credits that actually landed
     * on the wallet's own Platform address (gross − the Rust-computed
     * unshield fee). Null = the unshield step has not provably run yet.
     */
    val netCredits: Long?,
    /** True once a top-up broadcast has been ATTEMPTED (set just before it). */
    val topUpStarted: Boolean
)

/** Outcome of one [ShieldedInviteOverageTopUp.runPending] pass. */
enum class InviteOverageOutcome {
    /** Nothing pending — no record. */
    IDLE,

    /** The pending record was completed (or provably resolved) and cleared. */
    DONE,

    /** Not finished this pass — re-run later (worker backoff). */
    RETRY
}

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over the SDK's Platform-address → identity credits surface
 * ([org.dashfoundation.dashsdk.credits.IdentityCredits.topUpFromAddresses],
 * ID-06, plus the address-balance enumeration feeding it), so
 * [ShieldedInviteOverageTopUp] is host-JVM unit-testable — the real calls
 * need `libdash_sdk`.
 */
interface InviteOverageSource {
    /**
     * The wallet's Platform-payment addresses that currently hold credits
     * ([org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet.addressesWithBalances]).
     * Cached SDK state — refreshed by the shielded/address sync, so a credit
     * an unshield just landed may take a sync pass to appear.
     */
    suspend fun addressesWithBalances(): List<FundingInput>

    /**
     * ID-06: top up [identityId] (32 bytes) from the given Platform-address
     * [inputs], signed per-address by the SDK's own signer. Returns the
     * identity's post-transition credit balance. Throws on failure
     * (classified by the caller via [classifyBroadcastFailure]).
     */
    suspend fun topUpFromAddresses(identityId: ByteArray, inputs: List<FundingInput>): Long
}

/** Production [InviteOverageSource]: boots the SDK on demand. */
internal class DashSdkInviteOverageSource(
    private val service: DashSdkService
) : InviteOverageSource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    private suspend fun wallet(): org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet {
        val manager = manager()
        val walletIdHex = checkNotNull(manager.wallets.value.keys.singleOrNull()) {
            "app wallet not bound to the SDK"
        }
        return checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
    }

    override suspend fun addressesWithBalances(): List<FundingInput> =
        wallet().addressesWithBalances()

    override suspend fun topUpFromAddresses(identityId: ByteArray, inputs: List<FundingInput>): Long {
        val manager = manager()
        return manager.identityCredits.topUpFromAddresses(
            walletHandle = wallet().handle,
            identityId = identityId,
            inputs = inputs,
            signerHandle = manager.signerHandle
        )
    }
}

// ── Service ───────────────────────────────────────────────────────────

/**
 * Moves a shielded invite claim's OVERAGE onto the claimed identity — the
 * product decision "an invite claim should result in all remaining value,
 * after the invitee has created their username(s), to be on their identity".
 *
 * WHY THIS EXISTS: a Type-20 claim can only exit an ALLOWED denomination
 * (consensus validates `denomination` against the versioned set and credits
 * the identity exactly `denomination − metered fee` — there is no channel for
 * more; see [SHIELDED_IDENTITY_DENOMINATIONS_CREDITS]). A legacy 0.3 note
 * therefore exits 0.25 and rs-platform-wallet routes the 0.05 remainder to
 * the CLAIMER's own Orchard change address. This service is the follow-up
 * that carries that remainder the rest of the way:
 *
 *   1. UNSHIELD (Type 17) the overage from the claimer's pool to the
 *      wallet's own Platform receive address
 *      ([ShieldedBalanceService.unshieldToCredits]), with the established
 *      max-spend fee convergence ([shieldedMaxFeeAdjustment]): the change
 *      note holds EXACTLY the overage, so the first attempt is short by the
 *      Rust-computed fee and the one adjusted retry spends the note in full.
 *   2. TOP UP (ID-06) the claimed identity from that address balance
 *      ([InviteOverageSource.topUpFromAddresses]), capped at the unshielded
 *      net ([packOverageInputs]) so nothing but the overage is moved.
 *
 * NON-BLOCKING AND CLAIM-SAFE: the claim itself is unchanged and already
 * final when this runs; a top-up failure never invalidates the identity or
 * its usernames — the record just stays pending and is retried
 * ([ShieldedInviteOverageWorker], enqueued post-claim and re-enqueued at
 * launch while a record exists).
 *
 * CRASH RESILIENCE is a persisted 3-field resume point
 * ([DashPayConfig.INVITE_OVERAGE_IDENTITY_ID] / `_CREDITS` /
 * `_NET_CREDITS` / `_TOPUP_STARTED`, written by
 * [SdkShieldedUsernameCreation.createIdentityFromInvitation] BEFORE the claim
 * returns):
 * - record present, no net → resume at the unshield;
 * - net present → resume at the top-up;
 * - a rerun that finds the pool EMPTY at the unshield stage checks the
 *   address balance: funds there prove a prior unshield broadcast landed
 *   (crash between broadcast and persist) → advance; nothing anywhere means
 *   the overage never existed (or was manually spent) → give up and clear;
 * - a rerun that finds NO address balance at the top-up stage uses
 *   `TOPUP_STARTED` to tell "the top-up consumed it" (done → clear) from
 *   "the unshield hasn't surfaced in the address cache yet" (retry).
 *
 * FUNDS SCOPE: the unshield amount is derived from the RECORDED overage,
 * never from the pool balance, so at most the overage's value ever moves. In
 * the invite-claim reality the claimer's pool holds exactly the claim's
 * change note (a fresh wallet claiming an invite), so the spend IS that note;
 * a pool that also holds other notes still only ever contributes the overage
 * amount (note selection is Rust-side largest-first — pinning the exact
 * nullifier would need a new FFI parameter, flagged for a future AAR if
 * required).
 */
@Singleton
class ShieldedInviteOverageTopUp internal constructor(
    private val dashPayConfig: DashPayConfig,
    private val shieldedBalanceService: ShieldedBalanceService,
    private val source: InviteOverageSource,
    /**
     * The persisted identity record the completed-claim RECONCILE reads
     * ([reconcileCompletedClaim]). Null (host-test default) disables the
     * reconcile only — the drain pipeline is unaffected.
     */
    private val identityConfig: BlockchainIdentityConfig? = null
) {
    @Inject
    constructor(
        dashPayConfig: DashPayConfig,
        shieldedBalanceService: ShieldedBalanceService,
        sdkService: DashSdkService,
        identityConfig: BlockchainIdentityConfig
    ) : this(
        dashPayConfig = dashPayConfig,
        shieldedBalanceService = shieldedBalanceService,
        source = DashSdkInviteOverageSource(sdkService),
        identityConfig = identityConfig
    )

    companion object {
        private val log = LoggerFactory.getLogger(ShieldedInviteOverageTopUp::class.java)

        /**
         * Persist a fresh pending record — the claim path's write
         * ([SdkShieldedUsernameCreation]'s `recordInviteOverage` seam), kept
         * here so the key layout has one owner. Idempotent per claim: a
         * record for a NEW identity overwrites any stale one (there is at
         * most one in-flight invite claim, and a fresh claim supersedes an
         * abandoned older record).
         */
        suspend fun persistPendingRecord(
            dashPayConfig: DashPayConfig,
            identityIdBase58: String,
            overageCredits: Long
        ) {
            require(overageCredits > 0) { "overage must be positive, got $overageCredits" }
            dashPayConfig.remove(DashPayConfig.INVITE_OVERAGE_NET_CREDITS)
            dashPayConfig.remove(DashPayConfig.INVITE_OVERAGE_TOPUP_STARTED)
            dashPayConfig.set(DashPayConfig.INVITE_OVERAGE_CREDITS, overageCredits)
            // IDENTITY_ID last: its presence is the "record exists" marker, so
            // a crash mid-write can never yield a record without an amount.
            dashPayConfig.set(DashPayConfig.INVITE_OVERAGE_IDENTITY_ID, identityIdBase58)
            // One-shot reconcile guard: this identity's overage now has (or
            // has had) a record — the completed-claim reconcile must never
            // re-mint it (see reconcilableOverageCredits).
            dashPayConfig.set(DashPayConfig.INVITE_OVERAGE_RECONCILED_IDENTITY, identityIdBase58)
        }
    }

    /** The pending record, or null when there is none (or it is malformed). */
    internal suspend fun pendingRecord(): PendingInviteOverage? {
        val identityId = dashPayConfig.get(DashPayConfig.INVITE_OVERAGE_IDENTITY_ID) ?: return null
        val overage = dashPayConfig.get(DashPayConfig.INVITE_OVERAGE_CREDITS)
        if (overage == null || overage <= 0) {
            log.warn("invite overage record for {}… has no amount — clearing", identityId.take(8))
            clearRecord()
            return null
        }
        return PendingInviteOverage(
            identityIdBase58 = identityId,
            overageCredits = overage,
            netCredits = dashPayConfig.get(DashPayConfig.INVITE_OVERAGE_NET_CREDITS),
            topUpStarted = dashPayConfig.get(DashPayConfig.INVITE_OVERAGE_TOPUP_STARTED) == true
        )
    }

    /** Whether a pending overage record exists (the launch re-enqueue gate). */
    suspend fun hasPending(): Boolean = pendingRecord() != null

    /**
     * ONE-SHOT retro-fit for a COMPLETED shielded invite claim whose overage
     * was never recorded (observed live: the S22's legacy-0.3 claim at 0.25
     * produced no worker activity): re-derive the provable overage entirely
     * from persisted state (the identity record's state + id, the persisted
     * INVITE_LINK's `amt` — see [reconcilableOverageCredits]) and mint the
     * pending record the drain pipeline expects. Idempotent per identity via
     * the reconciled-identity marker; safe to call on every launch. Returns
     * true when a record was minted (the caller enqueues the worker).
     */
    suspend fun reconcileCompletedClaim(): Boolean {
        val config = identityConfig ?: return false
        val base = try {
            config.loadBase()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("invite overage reconcile: identity record unavailable", t)
            return false
        }
        val marker = dashPayConfig.get(DashPayConfig.INVITE_OVERAGE_RECONCILED_IDENTITY)
        val pending = hasPending()
        val overage = reconcilableOverageCredits(
            identityIdBase58 = base.userId,
            creationState = base.creationState,
            usingInvite = base.usingInvite,
            inviteIsShielded = base.invite?.isShielded == true,
            fundingCreditsFromLink = base.invite?.shieldedFundingCredits,
            hasPendingRecord = pending,
            alreadyReconciledIdentity = marker
        )
        val claimedIdentity = base.userId
        if (overage == null) {
            // ONE-LINE DIAGNOSTIC (observed live: the S22 retro-fit was a
            // silent no-op and cost an hour of on-device forensics): a
            // COMPLETED invite claim that was never recorded/reconciled but
            // whose amt source is unreadable — the persisted link is gone or
            // carries no note value — can never be reconciled. Say so once
            // per launch instead of declining silently.
            val invite = base.invite
            if (claimedIdentity != null &&
                base.creationState >= IdentityCreationState.IDENTITY_REGISTERED &&
                base.usingInvite && !pending && marker != claimedIdentity &&
                (invite == null || (invite.isShielded && invite.shieldedFundingCredits == null))
            ) {
                log.warn(
                    "completed invite claim for {}… has no reconcilable overage source " +
                        "(persisted link {}); any claim overage is not recoverable",
                    claimedIdentity.take(8),
                    if (invite == null) "is gone" else "carries no note value (amt)"
                )
            }
            return false
        }
        log.info(
            "invite overage reconcile: completed claim for {}… has a provable un-topped overage of " +
                "{} credits — minting the pending record",
            base.userId?.take(8),
            overage
        )
        persistPendingRecord(dashPayConfig, checkNotNull(base.userId), overage)
        return true
    }

    private suspend fun clearRecord() {
        // Stamp the reconcile guard BEFORE removing the record: a drained
        // record must never be re-minted by the completed-claim reconcile
        // (and records minted before the marker key existed get their marker
        // here). A crash between the stamp and the removes leaves the record
        // pending — retried, never duplicated.
        dashPayConfig.get(DashPayConfig.INVITE_OVERAGE_IDENTITY_ID)?.let {
            dashPayConfig.set(DashPayConfig.INVITE_OVERAGE_RECONCILED_IDENTITY, it)
        }
        dashPayConfig.remove(DashPayConfig.INVITE_OVERAGE_IDENTITY_ID)
        dashPayConfig.remove(DashPayConfig.INVITE_OVERAGE_CREDITS)
        dashPayConfig.remove(DashPayConfig.INVITE_OVERAGE_NET_CREDITS)
        dashPayConfig.remove(DashPayConfig.INVITE_OVERAGE_TOPUP_STARTED)
    }

    /**
     * One resume-aware pass over the pending record. Never throws for flow
     * reasons — every outcome maps to [InviteOverageOutcome] and transient
     * failures are [InviteOverageOutcome.RETRY] for the worker's backoff.
     */
    suspend fun runPending(): InviteOverageOutcome {
        val record = pendingRecord() ?: return InviteOverageOutcome.IDLE

        var netCredits = record.netCredits
        if (netCredits == null) {
            when (val unshielded = unshieldOverage(record)) {
                is UnshieldStep.Landed -> {
                    netCredits = unshielded.netCredits
                    dashPayConfig.set(DashPayConfig.INVITE_OVERAGE_NET_CREDITS, netCredits)
                }
                UnshieldStep.GiveUp -> {
                    log.error(
                        "invite overage of {} credits for {}… is not present anywhere " +
                            "(pool empty, no address balance) — abandoning the top-up",
                        record.overageCredits,
                        record.identityIdBase58.take(8)
                    )
                    clearRecord()
                    return InviteOverageOutcome.DONE
                }
                UnshieldStep.Retry -> return InviteOverageOutcome.RETRY
            }
        }

        return topUpIdentity(record, netCredits)
    }

    private sealed class UnshieldStep {
        /** Credits provably on the wallet's own Platform address. */
        data class Landed(val netCredits: Long) : UnshieldStep()
        object Retry : UnshieldStep()
        object GiveUp : UnshieldStep()
    }

    /**
     * Stage 1 — unshield the recorded overage to the wallet's own Platform
     * address. The change note holds exactly the overage, so the full-amount
     * attempt is expected to be short by the Rust-computed fee; the ONE
     * fee-adjusted retry ([shieldedMaxFeeAdjustment]) then spends the note in
     * full. The amount always derives from the RECORD, never the pool.
     */
    private suspend fun unshieldOverage(record: PendingInviteOverage): UnshieldStep {
        if (!shieldedBalanceService.ensureShieldedReady()) {
            log.info("invite overage: shielded runtime not ready — retrying later")
            return UnshieldStep.Retry
        }
        val requested = creditsToDash(record.overageCredits)
        when (val first = shieldedBalanceService.unshieldToCredits(requested)) {
            is SdkWriteResult.Broadcast -> return UnshieldStep.Landed(record.overageCredits)
            is SdkWriteResult.Ambiguous -> {
                // MAY be on chain: never re-broadcast in this pass. The next
                // pass self-heals — a landed unshield empties the pool and the
                // empty-pool arm below finds the address balance.
                log.warn("invite overage unshield outcome unconfirmed — deferring to the next pass")
                return UnshieldStep.Retry
            }
            is SdkWriteResult.NotBroadcast -> {
                val adjustment = shieldedMaxFeeAdjustment(requested, first)
                if (adjustment != null) {
                    when (val second = shieldedBalanceService.unshieldToCredits(adjustment.adjustedAmount)) {
                        is SdkWriteResult.Broadcast ->
                            return UnshieldStep.Landed(record.overageCredits - adjustment.deficitCredits)
                        is SdkWriteResult.Ambiguous -> {
                            log.warn("invite overage unshield (fee-adjusted) unconfirmed — deferring")
                            return UnshieldStep.Retry
                        }
                        is SdkWriteResult.NotBroadcast -> return classifyUnshieldRefusal(second)
                    }
                }
                return classifyUnshieldRefusal(first)
            }
        }
    }

    /**
     * A pre-broadcast unshield refusal that could not be fee-converged.
     * An EMPTY pool here is evidence, not just failure: either a previous
     * pass's unshield broadcast landed (crash before the net persist) — the
     * address balance proves it, advance — or the overage never existed
     * (a lying link value that slipped the claim-side guard, or funds moved
     * manually), in which case retrying forever helps no one: give up.
     */
    private suspend fun classifyUnshieldRefusal(failure: SdkWriteResult.NotBroadcast): UnshieldStep {
        val poolCredits = try {
            dashToCredits(shieldedBalanceService.observeShieldedBalance().first())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("invite overage: pool balance unavailable after unshield refusal — retrying", t)
            return UnshieldStep.Retry
        }
        if (poolCredits > 0) {
            log.info(
                "invite overage unshield refused pre-broadcast ({}) with {} credits still pooled — retrying later",
                failure.reason,
                poolCredits
            )
            return UnshieldStep.Retry
        }
        val addressCredits = try {
            source.addressesWithBalances().sumOf { it.credits }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("invite overage: address balances unavailable after unshield refusal — retrying", t)
            return UnshieldStep.Retry
        }
        return if (addressCredits > 0) {
            log.info(
                "invite overage: pool empty but {} credits on the wallet's Platform address — " +
                    "a prior unshield landed; advancing to the top-up",
                addressCredits
            )
            UnshieldStep.Landed(minOf(addressCredits, currentOverageOrMax()))
        } else {
            UnshieldStep.GiveUp
        }
    }

    private suspend fun currentOverageOrMax(): Long =
        dashPayConfig.get(DashPayConfig.INVITE_OVERAGE_CREDITS) ?: Long.MAX_VALUE

    /**
     * Stage 2 — top up the claimed identity from the wallet's own Platform
     * address balance, capped at [netCredits] so only the overage moves.
     */
    private suspend fun topUpIdentity(record: PendingInviteOverage, netCredits: Long): InviteOverageOutcome {
        val candidates = try {
            source.addressesWithBalances()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("invite overage: address balance enumeration failed — retrying", t)
            return InviteOverageOutcome.RETRY
        }
        val inputs = packOverageInputs(candidates, netCredits)
        if (inputs.isEmpty()) {
            return if (record.topUpStarted) {
                // A prior pass already attempted the broadcast and the credits
                // are gone from the address — the top-up consumed them (the
                // crash hit between broadcast and clear). Done.
                log.info(
                    "invite overage: no address balance and a top-up was already attempted — " +
                        "treating the {}-credit top-up for {}… as completed",
                    netCredits,
                    record.identityIdBase58.take(8)
                )
                clearRecord()
                InviteOverageOutcome.DONE
            } else {
                // The unshield broadcast landed but the SDK's address cache
                // hasn't caught up (it refreshes on the sync tick). Retry.
                log.info("invite overage: unshielded credits not yet visible on the address — retrying")
                InviteOverageOutcome.RETRY
            }
        }

        val identityId = try {
            Identifier.from(record.identityIdBase58).toBuffer()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error("invite overage record carries a malformed identity id — clearing", t)
            clearRecord()
            return InviteOverageOutcome.DONE
        }

        // Mark the attempt BEFORE the broadcast so a crash inside it is
        // recognized by the empty-address arm above instead of re-paying.
        dashPayConfig.set(DashPayConfig.INVITE_OVERAGE_TOPUP_STARTED, true)
        return try {
            val newBalance = source.topUpFromAddresses(identityId, inputs)
            log.info(
                "invite overage: topped up identity {}… with {} credits (new balance {})",
                record.identityIdBase58.take(8),
                inputs.sumOf { it.credits },
                newBalance
            )
            clearRecord()
            InviteOverageOutcome.DONE
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            when (classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("invite overage top-up rejected pre-broadcast — retrying later", t)
                    InviteOverageOutcome.RETRY
                }
                else -> {
                    // MAY be on chain. Never re-broadcast blindly: the next
                    // pass re-reads the address balance — consumed credits +
                    // the started flag resolve to DONE, intact credits retry.
                    log.warn("invite overage top-up outcome unconfirmed — deferring to the next pass", t)
                    InviteOverageOutcome.RETRY
                }
            }
        }
    }
}
