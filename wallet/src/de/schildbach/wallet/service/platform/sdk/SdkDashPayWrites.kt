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
 * Also definitively pre-broadcast, message-matched because the SDK exposes no
 * typed error for them: the shielded BUILD-INPUT refusals (exit-denomination
 * membership, denomination-vs-metered-fee, address decode/network, note-value
 * overflow) — see the rules at the bottom of the table for why the match is on
 * the specific refusals and not on the "Shielded build error" family.
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
/**
 * [classifyBroadcastFailure] reasons for the two PRE-BROADCAST funding
 * shortfalls that are retryable with a smaller amount (nothing submitted,
 * selection released). Named so retry logic — e.g. a MAX top-up's one-shot
 * fee-adjusted retry — matches the classifier's own verdict instead of
 * re-matching raw engine messages that differ per build path.
 */
internal const val REASON_PRE_BROADCAST_BUILD_SHORTFALL =
    "pre-broadcast build failure (insufficient funds / coin selection)"
internal const val REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION =
    "pre-broadcast asset-lock coin-selection failure"

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
    // The FFI validates the SENDER identity's key set while BUILDING a
    // contact request — strictly before signing or submission. Identities
    // without an ENCRYPTION-purpose key (every dashj-created identity, and
    // Type-20 shielded-created identities with the canonical 4-key set)
    // fail here as a Generic "Invalid identity data: Identity has no
    // enabled ECDSA_SECP256K1 encryption key" (observed live: the first
    // contact request from a shielded-created identity). Nothing was
    // broadcast → the dashj fallback is safe AND sufficient: the legacy
    // path derives DIP-15 encryption keys from the wallet seed, not from
    // identity keys. Message-matched until the SDK exposes a typed error.
    t.message?.contains("Invalid identity data") == true ->
        SdkWriteResult.NotBroadcast("pre-broadcast identity-key validation failure", t)
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
        SdkWriteResult.NotBroadcast(REASON_PRE_BROADCAST_BUILD_SHORTFALL, t)
    // Shielded note selection (rs-platform-wallet note_selection.rs) runs
    // strictly BEFORE proof generation or broadcast — nothing was submitted
    // and the selected notes are released. Surfaced as a WalletOperation
    // error carrying the reason in the message (observed live on a Max
    // withdraw: "shielded withdraw failed: Insufficient shielded balance:
    // available N, required M" — required = amount + the Rust-computed fee;
    // "No unspent shielded notes available" is raised on the same path).
    // Message-matched until the SDK exposes typed errors. Retryable with a
    // smaller amount.
    t.message?.let { m ->
        m.contains("Insufficient shielded balance") ||
            m.contains("No unspent shielded notes available")
    } == true ->
        SdkWriteResult.NotBroadcast("pre-broadcast shielded note-selection failure", t)
    // Asset-lock coin selection (rs-platform-wallet asset_lock/build.rs
    // `map_builder_error`) rejects the spend while BUILDING the asset-lock
    // transaction — strictly before any broadcast, nothing was submitted
    // (`BuilderError::InsufficientFunds` / a coin-selection
    // `SelectionError::InsufficientFunds` are promoted to the typed
    // `AssetLockInsufficientFunds`, which carries this message). Observed
    // live on a Max shield: "shielded fund-from-asset-lock failed: asset
    // lock coin selection is short: available N duffs, required M duffs" —
    // `required` does NOT include the L1 fee, so available == required is a
    // real shape. Message-matched until the SDK exposes typed errors.
    // Retryable with a smaller amount.
    t.message?.contains("asset lock coin selection is short") == true ->
        SdkWriteResult.NotBroadcast(REASON_PRE_BROADCAST_ASSET_LOCK_SELECTION, t)
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
    // A PlatformWalletError::Persistence gate (e.g. the invitation-capability
    // check: "This operation requires persistence capabilities") is raised
    // while VALIDATING the operation's preconditions — strictly before any
    // state transition is built or submitted, so nothing was spent. Surfaced
    // as a Generic/wallet-operation FFI error carrying this message (the SDK
    // exposes no typed Persistence error at 0.1.0-v41int3). Message-matched
    // until a typed error exists; retry-safe.
    t.message?.contains("requires persistence capabilities") == true ->
        SdkWriteResult.NotBroadcast("pre-broadcast: persistence-capability gate", t)
    // Shielded BUILD-INPUT validation, all raised inside
    // rs-platform-wallet's `select_notes_for_denomination`
    // (shielded/note_selection.rs) or the address decode that precedes the
    // operation — strictly before the Orchard build, the proof, and the
    // broadcast, with no note reservation taken. Surfaced as a
    // WalletOperation error whose message wraps
    // `PlatformWalletError::ShieldedBuildError` (observed live on an invite
    // claim: "shielded identity-create-from-one-time-key failed: Shielded
    // build error: denomination 30000000000 is not a member of the allowed
    // exit-denomination set […]" — a 0.3 legacy invite note against the v13
    // set). Retryable with a denomination the protocol accepts.
    //
    // NOTE the match is on the SPECIFIC refusals, deliberately NOT on the
    // "Shielded build error" prefix: `identity_create_from_one_time_key`
    // ALSO raises ShieldedBuildError AFTER a successful broadcast, when it
    // synthesizes the identity from an unexpected proof-result variant
    // (rs-platform-wallet shielded/operations.rs). Treating the whole family
    // as pre-broadcast would report an on-chain identity as never-submitted.
    t.message?.let { m ->
        m.contains("is not a member of the allowed exit-denomination set") ||
            m.contains("does not exceed the predicted identity-create fee") ||
            m.contains("invalid platform address") ||
            m.contains("not a platform address") ||
            m.contains("platform address network mismatch") ||
            m.contains("invalid core address") ||
            m.contains("core address network mismatch") ||
            m.contains("overflows u64")
    } == true ->
        SdkWriteResult.NotBroadcast("pre-broadcast shielded build-input validation failure", t)
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
     *
     * [avatarBytes] is the RAW avatar image: the SDK computes the document's
     * `avatarHash` (SHA-256) and `avatarFingerprint` (perceptual) from it
     * Rust-side — it does not accept the app's precomputed pair. Null when the
     * profile carries no avatar (or the bytes could not be fetched, in which
     * case the caller must not route an avatar-bearing profile here).
     */
    suspend fun createOrUpdateProfile(
        walletIdHex: String,
        identityId: ByteArray,
        displayName: String?,
        publicMessage: String?,
        avatarUrl: String?,
        avatarBytes: ByteArray?,
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
        avatarBytes: ByteArray?,
        doCreate: Boolean
    ) {
        val manager = manager()
        wallet(manager, walletIdHex).dashpay.createOrUpdateProfile(
            identityId = identityId,
            displayName = displayName,
            publicMessage = publicMessage,
            avatarUrl = avatarUrl,
            avatarBytes = avatarBytes,
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
 * - Profile writes carrying `avatarHash`/`avatarFingerprint` are routed
 *   only when the caller also supplies the RAW avatar bytes (the SDK
 *   recomputes both digests Rust-side and takes no precomputed pair); the
 *   `UpdateProfileWorker` → `broadcastUpdatedProfile` chain fetches them
 *   from the same avatar URL the digests were computed over. If the fetch
 *   fails the profile stays on dashj ([SdkWriteResult.NotBroadcast]), which
 *   carries the precomputed digest.
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
    private val dashPayConfig: DashPayConfig,
    /**
     * The DashPay ENCRYPTION/DECRYPTION key retrofit for identities
     * registered before the six-key registration change (keyIds 0..3 only).
     * Null in host-JVM tests, which leaves the write path byte-for-byte as it
     * was before the retrofit existed.
     */
    private val dashPayKeys: SdkDashPayKeyProvisioning? = null
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        dashPayKeys: SdkDashPayKeyProvisioning
    ) : this(
        source = DashSdkDashPayWriteSource(sdkService),
        dashPayConfig = dashPayConfig,
        dashPayKeys = dashPayKeys
    )

    /**
     * SDK-path replacement for the broadcast step of
     * `PlatformBroadcastService.sendContactRequest` (dashj:
     * `platform.contactRequests.create`). [ownUserId] / [toUserId] are
     * base58 identity ids; the caller has already authenticated the user.
     *
     * This is the one write whose FFI demands an enabled ECDSA_SECP256K1
     * ENCRYPTION key on the SENDING identity (the DIP-15 ECDH root), so it
     * also carries the [SdkDashPayKeyProvisioning] retrofit preflight — see
     * [dashPayEncryptionKeyPreflight].
     */
    suspend fun sendContactRequest(ownUserId: String, toUserId: String): SdkWriteResult<Unit> {
        val recipient = identityBytesOrNull(toUserId)
            ?: return SdkWriteResult.NotBroadcast("malformed recipient identity id")
        return runWrite(
            ownUserId,
            "sendContactRequest",
            extraPreflight = { walletId, ownId -> dashPayEncryptionKeyPreflight(walletId, ownId) }
        ) { walletId, ownId ->
            source.sendContactRequest(walletId, ownId, recipient)
        }
    }

    /**
     * Contact-request-only preflight: does the SENDING identity have the
     * DIP-15 ECDH root (an enabled ECDSA_SECP256K1 ENCRYPTION key)?
     *
     * Returns null to proceed, or a [SdkWriteResult.NotBroadcast] reason that
     * short-circuits this write to the unchanged dashj fallback.
     *
     * Identities registered before the six-key registration change carry only
     * keyIds 0..3, so the FFI would raise "Invalid identity data: Identity has
     * no enabled ECDSA_SECP256K1 encryption key" while BUILDING the request —
     * already classified [SdkWriteResult.NotBroadcast] by
     * [classifyBroadcastFailure]. Rather than take that same failure forever,
     * we detect it up front (a local SDK Room read of the identity's key rows)
     * and kick off the one-time [SdkDashPayKeyProvisioning] retrofit in the
     * BACKGROUND. This request is not blocked and not made slower by the
     * on-chain `IdentityUpdate`: it falls back to dashj exactly as it does
     * today, and the NEXT contact request routes through the SDK.
     *
     * Placement rationale: this is the only DashPay write that needs the key
     * (profile writes do not), and by sitting behind [runWrite]'s existing
     * gates the retrofit can only ever spend credits on a wallet that has
     * `USE_KOTLIN_SDK_DASHPAY_WRITES` enabled, is bound to the SDK, has a
     * managed identity, and is actually trying to add a contact.
     *
     * Fail-open: any detection failure — and [DashPayKeyState.UNKNOWN], which
     * includes every identity whose key layout is not the canonical 0..3 set —
     * proceeds with the write, i.e. pre-retrofit behaviour.
     */
    private suspend fun dashPayEncryptionKeyPreflight(walletIdHex: String, ownId: ByteArray): String? {
        val provisioning = dashPayKeys ?: return null
        return try {
            when (provisioning.state(walletIdHex, ownId)) {
                DashPayKeyState.PRESENT, DashPayKeyState.UNKNOWN -> null
                DashPayKeyState.MISSING -> {
                    val started = provisioning.requestProvisioning(walletIdHex, ownId)
                    "identity has no DashPay ENCRYPTION key; " +
                        if (started) {
                            "provisioning it in the background"
                        } else {
                            "provisioning already in flight or exhausted"
                        }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("DashPay encryption-key preflight failed; continuing with the write", t)
            null
        }
    }

    /**
     * SDK-path replacement for the broadcast step of
     * `PlatformBroadcastService.broadcastUpdatedProfile` (dashj:
     * `BlockchainIdentity.registerProfile` / `updateProfile`).
     *
     * [hasAvatarDigest] must be true when the profile carries an
     * `avatarHash`/`avatarFingerprint`. The SDK recomputes both Rust-side
     * from [avatarBytes] (the raw image) rather than taking the app's
     * precomputed pair, so an avatar-bearing profile can only be routed here
     * WITH those bytes; without them the write stays on dashj, which carries
     * the precomputed digest.
     */
    suspend fun createOrUpdateProfile(
        ownUserId: String,
        displayName: String?,
        publicMessage: String?,
        avatarUrl: String?,
        avatarBytes: ByteArray?,
        hasAvatarDigest: Boolean,
        doCreate: Boolean
    ): SdkWriteResult<Unit> {
        if (hasAvatarDigest && avatarBytes == null) {
            return SdkWriteResult.NotBroadcast(
                "profile has avatarHash/avatarFingerprint but no raw avatar bytes; SDK path needs them"
            )
        }
        return runWrite(ownUserId, "createOrUpdateProfile") { walletId, ownId ->
            source.createOrUpdateProfile(
                walletId, ownId, displayName, publicMessage, avatarUrl, avatarBytes, doCreate
            )
        }
    }

    /**
     * Shared orchestration: flag check → preflight (wallet bound, identity
     * managed; every failure here is definitively pre-broadcast) → the one
     * native broadcast attempt, classified by [classifyBroadcastFailure].
     *
     * [extraPreflight] is an optional per-operation gate that runs LAST in the
     * preflight (so it can rely on the bound wallet id and the managed
     * identity). Returning a non-null reason short-circuits to
     * [SdkWriteResult.NotBroadcast] — i.e. the unchanged dashj path — without
     * submitting anything.
     */
    private suspend fun runWrite(
        ownUserId: String,
        operation: String,
        extraPreflight: suspend (walletIdHex: String, ownIdentityId: ByteArray) -> String? = { _, _ -> null },
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
        extraPreflight(walletId, ownId)?.let { return notBroadcast(operation, it, null) }

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
