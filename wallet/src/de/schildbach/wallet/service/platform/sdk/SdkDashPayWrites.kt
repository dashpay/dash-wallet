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

import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of an attempted Kotlin-SDK DashPay write — the Phase 3e
 * no-double-broadcast contract (`docs/kotlin-sdk-migration-plan.md`).
 *
 * Writes are real Platform state transitions, so unlike the read seams the
 * caller may NOT blindly fall back to dashj on failure: re-broadcasting a
 * state transition that in fact landed would create a duplicate document.
 * The three cases partition every possible outcome:
 *
 * - [Broadcast] — the SDK confirmed the state transition. The caller must
 *   NOT run the dashj broadcast; it performs its post-broadcast bookkeeping
 *   (database rows, keychains, listeners) instead.
 * - [NotBroadcast] — the SDK path DEFINITIVELY did not submit anything
 *   (flag off, preflight failure, or a pre-broadcast validation error).
 *   Falling back to the unchanged dashj path is safe.
 * - [Ambiguous] — the SDK attempt failed in a way that cannot be proven
 *   pre-broadcast (network error, timeout, internal error, …: the
 *   transition MAY have been submitted). The caller must surface this as an
 *   error — exactly the way the dashj path surfaces its own broadcast
 *   failures — and must NOT retry via dashj in the same call.
 */
sealed class SdkWriteResult<out T> {
    data class Broadcast<T>(val value: T) : SdkWriteResult<T>()
    data class NotBroadcast(val reason: String, val cause: Throwable? = null) : SdkWriteResult<Nothing>()
    data class Ambiguous(val cause: Throwable) : SdkWriteResult<Nothing>()
}

/**
 * The no-double-broadcast decision table for a throwable raised by the
 * SDK's native write call itself (preflight failures never reach here —
 * they are [SdkWriteResult.NotBroadcast] by construction).
 *
 * Definitively pre-broadcast — input/state validation the FFI performs
 * before building or submitting a state transition:
 * - [DashSdkError.InvalidParameter], [DashSdkError.InvalidState],
 *   [DashSdkError.NotFound], [DashSdkError.NotImplemented],
 *   [DashSdkError.PlatformWallet.InvalidHandle];
 * - plus the two platform-wallet codes the SDK documents as definitive
 *   non-execution with reservations released
 *   ([DashSdkError.PlatformWallet.ShieldedBroadcastFailed],
 *   [DashSdkError.PlatformWallet.ShieldedNoRecordedAnchor]) — shielded-op
 *   codes that shouldn't appear on a document transition, classified
 *   correctly in case they ever do.
 *
 * NOTE [DashSdkError.isRetryable] is deliberately NOT consulted: it means
 * "retrying can plausibly succeed" (true for NetworkError/Timeout), which
 * is exactly the opposite of proof that nothing was submitted.
 *
 * Everything else is [SdkWriteResult.Ambiguous] — including
 * [DashSdkError.PlatformWallet.TransactionBroadcastUnconfirmed] /
 * [DashSdkError.PlatformWallet.ShieldedSpendUnconfirmed], which the SDK
 * itself documents as "may already be on chain, do NOT retry", and
 * deliberately including cases that are *probably* pre-broadcast but not
 * provably so:
 * - [DashSdkError.NetworkError] / [DashSdkError.Timeout] — the connection
 *   may have dropped after the transition was submitted;
 * - [DashSdkError.SerializationError] — response parsing after a
 *   successful submit also raises this;
 * - [DashSdkError.CryptoError] — usually signing (pre-broadcast) but also
 *   raised by post-submit proof verification;
 * - [DashSdkError.ProtocolError] / [DashSdkError.DriveInternalError] /
 *   [DashSdkError.InternalError] / wallet-operation and generic errors /
 *   any non-SDK throwable — no way to know which side of the submit they
 *   occurred on.
 *
 * Kept as a top-level pure function so the table is unit-testable on the
 * host JVM without any native or Android dependency.
 */
internal fun classifyBroadcastFailure(t: Throwable): SdkWriteResult<Nothing> = when {
    t is DashSdkError.InvalidParameter ||
        t is DashSdkError.InvalidState ||
        t is DashSdkError.NotFound ||
        t is DashSdkError.NotImplemented ||
        t is DashSdkError.PlatformWallet.InvalidHandle ||
        t is DashSdkError.PlatformWallet.ShieldedBroadcastFailed ||
        t is DashSdkError.PlatformWallet.ShieldedNoRecordedAnchor ->
        SdkWriteResult.NotBroadcast("pre-broadcast validation failure: ${t.javaClass.simpleName}", t)
    // Signing happens strictly before submission, so nothing was broadcast.
    // Surfaced as a Generic FFI error with this message (observed live on-device
    // when identity private keys weren't derived/stored after discovery);
    // message-matched until the SDK exposes a typed signing error.
    t.message?.contains("no private key stored") == true ->
        SdkWriteResult.NotBroadcast("signing failure (pre-broadcast): no private key stored", t)
    // Android Keystore auth-window expiry: the SDK keeps identity keys
    // AUTH_GATED (decryptable only within ~30 s of a biometric/device-credential
    // unlock), and an expired window surfaces as UserNotAuthenticatedException —
    // message "User not authenticated" — thrown while DECRYPTING the identity
    // key to sign the state transition (KeystoreSigner.retrieveKeyWithAuth →
    // WalletStorage.retrievePrivateKey; only the identity-key alias is
    // auth-gated, so no other SDK path produces this message). Signing happens
    // during transition CONSTRUCTION (dpp `sign_external_with_options`, called
    // from `BatchTransition::new_document_*_transition_from_document` inside
    // rs-sdk `put_to_platform`), strictly BEFORE `transition.broadcast` — the
    // signer error propagates out before the broadcast line is reached, and no
    // post-broadcast step invokes the identity-key signer. Nothing was
    // submitted → safe dashj fallback (app-side keys, no Keystore gate).
    // Message-matched until platform PR #4060 (DEVICE_BOUND key policy)
    // replaces the auth window and gives this a typed error.
    t.message?.contains("User not authenticated") == true ->
        SdkWriteResult.NotBroadcast("signing failure (pre-broadcast): Keystore auth window expired", t)
    // Coin selection / insufficient funds happens during transaction BUILDING,
    // strictly before any broadcast — nothing was submitted. Surfaced as a
    // WalletOperation error carrying the reason in the message (observed live:
    // "Coin selection error: Insufficient funds: available N, required M").
    // Message-matched until the SDK exposes a typed InsufficientFunds error
    // (SDK issue to file). Retryable with a smaller amount.
    t.message?.let { m ->
        m.contains("Insufficient funds") ||
            m.contains("Coin selection error") ||
            m.contains("transaction build failed") ||
            m.contains("set_funding failed")
    } == true ->
        SdkWriteResult.NotBroadcast("pre-broadcast build failure (insufficient funds / coin selection)", t)
    // The SDK's SPV client wasn't running when broadcast was attempted, so the
    // tx never left the device (observed live: the interim shield pipeline
    // broadcasts via the shadow SPV, which our recovery paths stop/reset — the
    // asset-lock funding needs it started first; the caller re-starts SPV and
    // retries). Definitively pre-broadcast → retryable.
    t.message?.let { m ->
        m.contains("SPV client not started") ||
            m.contains("Transaction broadcast failed: SPV")
    } == true ->
        SdkWriteResult.NotBroadcast("pre-broadcast: SPV client not started", t)
    else -> SdkWriteResult.Ambiguous(t)
}

/**
 * Seam over the Kotlin SDK's wallet-bound DashPay write surface
 * ([org.dashfoundation.dashsdk.tokens.Dashpay] +
 * [org.dashfoundation.dashsdk.wallet.PlatformWalletManager]), so the
 * flag/preflight/no-double-broadcast orchestration in [SdkDashPayWrites] is
 * host-JVM unit-testable — the real calls need `libdash_sdk`.
 */
interface SdkDashPayWriteSource {
    /**
     * The SDK wallet id (lowercase hex) the app's seed is bound to, or null
     * when no wallet is bound. The Phase 3b bridge only ever binds ONE
     * wallet (the app's seed via `DashSdkService.bindAppWallet`), so a
     * manager holding anything other than exactly one wallet means the
     * bridge hasn't run (empty) or state is unexpected (>1) — both null.
     */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * True when [identityId] (32 bytes) is a managed identity of the bound
     * SDK wallet — i.e. Rust holds its slot and can derive its keys. Local
     * read, no network.
     */
    suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean

    /**
     * Broadcast a DashPay contact request from [senderIdentityId] to
     * [recipientIdentityId] (32 bytes each), signed Rust-side with the
     * manager's signer + mnemonic resolver. Returns only on confirmed
     * broadcast; throws otherwise.
     */
    suspend fun sendContactRequest(
        walletIdHex: String,
        senderIdentityId: ByteArray,
        recipientIdentityId: ByteArray
    )

    /**
     * Broadcast a DashPay profile create ([doCreate]) or update for
     * [identityId], signed Rust-side. Returns only on confirmed broadcast;
     * throws otherwise.
     */
    suspend fun createOrUpdateProfile(
        walletIdHex: String,
        identityId: ByteArray,
        displayName: String?,
        publicMessage: String?,
        avatarUrl: String?,
        doCreate: Boolean
    )
}

/** Production [SdkDashPayWriteSource]: boots the SDK on demand. */
internal class DashSdkDashPayWriteSource(
    private val service: DashSdkService
) : SdkDashPayWriteSource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    private fun wallet(
        manager: org.dashfoundation.dashsdk.wallet.PlatformWalletManager,
        walletIdHex: String
    ): org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet =
        checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }

    override suspend fun boundWalletIdOrNull(): String? =
        manager().wallets.value.keys.singleOrNull()

    override suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean {
        val manager = manager()
        // syncState is a local managed-identity snapshot read; null means
        // the identity is not managed by this wallet.
        return wallet(manager, walletIdHex).dashpay.syncState(identityId) != null
    }

    override suspend fun sendContactRequest(
        walletIdHex: String,
        senderIdentityId: ByteArray,
        recipientIdentityId: ByteArray
    ) {
        val manager = manager()
        wallet(manager, walletIdHex).dashpay.sendContactRequest(
            senderIdentityId = senderIdentityId,
            recipientIdentityId = recipientIdentityId,
            signerHandle = manager.signerHandle,
            coreSignerHandle = manager.mnemonicResolverHandle
        ).close() // the wallet app reconciles from Platform; the native handle is not needed
    }

    override suspend fun createOrUpdateProfile(
        walletIdHex: String,
        identityId: ByteArray,
        displayName: String?,
        publicMessage: String?,
        avatarUrl: String?,
        doCreate: Boolean
    ) {
        val manager = manager()
        wallet(manager, walletIdHex).dashpay.createOrUpdateProfile(
            identityId = identityId,
            displayName = displayName,
            publicMessage = publicMessage,
            avatarUrl = avatarUrl,
            avatarBytes = null,
            doCreate = doCreate,
            signerHandle = manager.signerHandle
        )
    }
}

/**
 * Phase 3e facade: routes the wallet's DashPay WRITES — send contact
 * request and create/update profile — to the Dash Platform Kotlin SDK
 * behind the runtime flag [DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES]
 * (default OFF).
 *
 * ## Key-derivation parity (the Phase 3e Task A gate)
 *
 * Verdict: CONDITIONAL PARITY. dashj registers identity authentication
 * key `i` at `m/9'/coin'/5'/0'/0'/0'/i'` (ECDSA secp256k1, hardened;
 * dashj-core 22.0.3 `DerivationPathFactory.blockchainIdentityECDSADerivationPath`
 * + `BlockchainIdentity.privateKeyAtIndex`, verified from bytecode). The
 * SDK derives `m/9'/coin'/5'/0'(auth)/0'(ECDSA)/identity_index'/key_index'`
 * (rust-dashcore @647fa98 `key-wallet/src/dip9.rs` +
 * `rs-platform-wallet/.../identity_handle.rs`). For `identity_index = 0` —
 * the only identity chain dashj ever creates — the trees coincide, so the
 * SDK CAN derive and sign with the keys of a dashj-registered identity,
 * PROVIDED the identity has been discovered/managed by the SDK wallet
 * (Rust maps on-chain key id → key_index positionally, matching dashj's
 * keyId == chain index registration).
 *
 * ## Contract (differs from the read seams — writes must not double-fire)
 *
 * Every write returns an [SdkWriteResult]:
 * - [SdkWriteResult.NotBroadcast] whenever the SDK path was not or could
 *   not have been used — flag off, SDK bootstrap failure, app wallet not
 *   bound ([DashSdkService.bindAppWallet] has not run), our identity not
 *   managed by the SDK wallet, unsupported inputs, or a provably
 *   pre-broadcast validation error. The call site then runs its existing
 *   dashj path unchanged.
 * - [SdkWriteResult.Broadcast] on confirmed broadcast — the call site
 *   skips the dashj broadcast and reconciles its local state from
 *   Platform.
 * - [SdkWriteResult.Ambiguous] when the failed attempt cannot be proven
 *   pre-broadcast ([classifyBroadcastFailure]) — the call site must throw,
 *   like a dashj broadcast failure, and never auto-retry via dashj.
 *
 * Flag off (the default) leaves the dashj path byte-for-byte intact apart
 * from one local DataStore flag read per write; the flag is re-read every
 * call.
 *
 * ## Preconditions (established by the Phase 3f binder)
 *
 * The preflight requires the app wallet bound to the SDK AND our identity
 * managed by it — both are wired by [SdkWalletBinder] (platform-sync start
 * + the broadcast call sites), which is itself gated on the same flags. If
 * the binder has not completed yet (or failed), every write short-circuits
 * to [SdkWriteResult.NotBroadcast] and dashj behavior is unchanged.
 *
 * ## Known gaps (deliberate, documented for Phase 3f)
 *
 * - Profile writes carrying `avatarHash`/`avatarFingerprint` are NOT
 *   routed: the SDK computes both Rust-side from the raw avatar bytes,
 *   which the wallet no longer holds at broadcast time. Such profiles stay
 *   on dashj ([SdkWriteResult.NotBroadcast]).
 * - Contact requests embed a DIP-15 friendship xpub + accountReference
 *   chosen by the SENDING stack. dashj reads both back from the broadcast
 *   document, but dashj's DIP-15 derivation matching the SDK's has NOT
 *   been verified the way DIP-13 has — until it is, enabling this flag on
 *   a wallet whose L1 is still dashj risks watching the wrong friendship
 *   addresses. Verify before flipping the flag in production.
 *
 * Routed call sites:
 * - [de.schildbach.wallet.service.platform.PlatformDocumentBroadcastService.sendContactRequest]
 * - [de.schildbach.wallet.service.platform.PlatformDocumentBroadcastService.broadcastUpdatedProfile]
 *
 * ## Accepting a contact request (Phase 3g — verified covered, no extra seam)
 *
 * In this app ACCEPTING an incoming contact request IS the reciprocal
 * [sendContactRequest]: every accept entry point (NotificationsFragment
 * `onAcceptRequest`, ContactsFragment, DashPayUserActivity, SendCoinsFragment)
 * funnels through `DashPayViewModel.sendContactRequest` →
 * `SendContactRequestOperation`/`SendContactRequestWorker` → the routed
 * `PlatformDocumentBroadcastService.sendContactRequest(toUserId = requester)`.
 * There is no separate dashj "accept" broadcast, so this facade already
 * routes the accept write. The SDK's dedicated
 * `Dashpay.acceptContactRequest` / `acceptIncomingRequest` is deliberately
 * NOT used:
 * - it needs the incoming request present in the SDK wallet's LOCAL contact
 *   state (it returns false / not-found otherwise), which this app does not
 *   keep in sync — the app's contact source of truth is its own Room DB fed
 *   by dashj reads;
 * - its extra Rust-side bookkeeping (external-account registration for the
 *   new friendship) would duplicate — and could diverge from — the app's
 *   dashj DIP-15 keychains (`PlatformSyncService.checkAndAddReceivedRequest`
 *   for the incoming half, `finalizeSentContactRequest` for the reciprocal
 *   half);
 * - the Platform document it broadcasts is the same reciprocal
 *   `contactRequest` that [sendContactRequest] sends.
 * Direction-dependent bookkeeping (the sending-to-requester DIP-15 keychain
 * and the incoming DB row) is handled by the sync path when the incoming
 * request arrives, independent of which stack broadcasts the reciprocal.
 */
@Singleton
class SdkDashPayWrites internal constructor(
    private val source: SdkDashPayWriteSource,
    private val dashPayConfig: DashPayConfig
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig
    ) : this(
        source = DashSdkDashPayWriteSource(sdkService),
        dashPayConfig = dashPayConfig
    )

    /**
     * SDK-path replacement for the broadcast step of
     * `PlatformBroadcastService.sendContactRequest` (dashj:
     * `platform.contactRequests.create`). [ownUserId] / [toUserId] are
     * base58 identity ids; the caller has already authenticated the user.
     */
    suspend fun sendContactRequest(ownUserId: String, toUserId: String): SdkWriteResult<Unit> {
        val recipient = identityBytesOrNull(toUserId)
            ?: return SdkWriteResult.NotBroadcast("malformed recipient identity id")
        return runWrite(ownUserId, "sendContactRequest") { walletId, ownId ->
            source.sendContactRequest(walletId, ownId, recipient)
        }
    }

    /**
     * SDK-path replacement for the broadcast step of
     * `PlatformBroadcastService.broadcastUpdatedProfile` (dashj:
     * `BlockchainIdentity.registerProfile` / `updateProfile`).
     *
     * [hasAvatarDigest] must be true when the profile carries an
     * `avatarHash`/`avatarFingerprint` — the SDK path cannot reproduce
     * them (see the class KDoc), so such writes stay on dashj.
     */
    suspend fun createOrUpdateProfile(
        ownUserId: String,
        displayName: String?,
        publicMessage: String?,
        avatarUrl: String?,
        hasAvatarDigest: Boolean,
        doCreate: Boolean
    ): SdkWriteResult<Unit> {
        if (hasAvatarDigest) {
            return SdkWriteResult.NotBroadcast(
                "profile has avatarHash/avatarFingerprint; SDK path needs raw avatar bytes"
            )
        }
        return runWrite(ownUserId, "createOrUpdateProfile") { walletId, ownId ->
            source.createOrUpdateProfile(
                walletId, ownId, displayName, publicMessage, avatarUrl, doCreate
            )
        }
    }

    /**
     * Shared orchestration: flag check → preflight (wallet bound, identity
     * managed; every failure here is definitively pre-broadcast) → the one
     * native broadcast attempt, classified by [classifyBroadcastFailure].
     */
    private suspend fun runWrite(
        ownUserId: String,
        operation: String,
        block: suspend (walletIdHex: String, ownIdentityId: ByteArray) -> Unit
    ): SdkWriteResult<Unit> {
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")
        val ownId = identityBytesOrNull(ownUserId)
            ?: return SdkWriteResult.NotBroadcast("malformed own identity id")

        // Preflight — nothing has been submitted if any of this fails.
        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast(operation, "app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(operation, "SDK bootstrap/bind lookup failed", t)
        }
        try {
            if (!source.isIdentityManaged(walletId, ownId)) {
                return notBroadcast(operation, "identity not managed by the SDK wallet", null)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(operation, "identity-managed preflight failed", t)
        }

        // The single broadcast attempt.
        return try {
            block(walletId, ownId)
            SdkWriteResult.Broadcast(Unit)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val classified = classifyBroadcastFailure(t)
            when (classified) {
                is SdkWriteResult.NotBroadcast ->
                    log.warn("SDK {} rejected pre-broadcast; falling back to dashj", operation, t)
                is SdkWriteResult.Ambiguous ->
                    log.error(
                        "SDK {} failed with an outcome that may already be broadcast; " +
                            "surfacing the error WITHOUT retrying via dashj",
                        operation,
                        t
                    )
                is SdkWriteResult.Broadcast -> Unit // unreachable
            }
            classified
        }
    }

    private fun notBroadcast(operation: String, reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("SDK {} not attempted ({}); using dashj", operation, reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    private fun identityBytesOrNull(base58: String): ByteArray? = try {
        Identifier.from(base58).toBuffer()
    } catch (e: Exception) {
        null
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_DASHPAY_WRITES) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_DASHPAY_WRITES; keeping dashj path", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkDashPayWrites::class.java)
    }
}
