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

import android.net.Uri
import androidx.core.net.toUri
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.service.platform.TopUpRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Utils
import kotlinx.coroutines.flow.first
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashfoundation.dashsdk.identity.RegistrationKeySet
import org.dashfoundation.dashsdk.identity.RegistrationKeys
import org.dashj.platform.dpp.identifier.Identifier
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of [SdkL1InviteCreation.createL1Invite]: the raw invitation deep
 * link [linkData] — the source of the invite PREVIEW
 * (user/displayName/avatar params) — plus the [shareLink] the user actually
 * shares/copies. [shareLink] is the AppsFlyer OneLink wrapping that deep link
 * (OG preview + install redirect), or the raw deep-link string when OneLink
 * generation was unavailable. Mirrors [ShieldedInvite].
 */
data class L1Invite(
    val linkData: InvitationLinkData,
    val shareLink: String
)

// ── Source seam ─────────────────────────────────────────────────────────

/**
 * Seam over the inviter/invitee-side SDK surface a TRANSPARENT (L1) DIP-13
 * invitation needs — the DIP-13 invitation wrappers
 * ([org.dashfoundation.dashsdk.identity.IdentityRegistration.createInvitation]
 * / [org.dashfoundation.dashsdk.identity.IdentityRegistration.claimInvitation])
 * plus the key-row plumbing the claim path shares with
 * [TransparentUsernameSource] (previewRegistrationKeySet →
 * storeIdentityPrivateKey → claimInvitation) — so the flag/cutover/preflight/
 * no-double-broadcast orchestration in [SdkL1InviteCreation] is host-JVM
 * unit-testable without `libdash_sdk`.
 */
interface L1InviteSource {
    /** Same contract as [TransparentUsernameSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * Create a DIP-13 invitation: fund a one-time asset-lock voucher of
     * [amountDuffs] from BIP-44 [fundingAccountIndex] and return the funding
     * outpoint plus the shareable `dashpay://invite` bearer link. No identity
     * is registered. [inviterIdentityId] (32 bytes) + [inviterUsername] enable
     * the contact-bootstrap opt-in; both null for a pure funding voucher. The
     * Core funding spend is signed with the wallet's `MnemonicResolverHandle`
     * (the SAME handle the funded-registration path uses).
     */
    suspend fun createInvitation(
        walletIdHex: String,
        amountDuffs: Long,
        fundingAccountIndex: Int,
        inviterIdentityId: ByteArray?,
        inviterUsername: String?,
        nowUnix: Long
    ): CreatedL1Invitation

    /**
     * Number of identities the bound SDK wallet already manages — the next
     * free identity index on the DIP-9 identity chain for the invitee's new
     * identity. Same contract as [TransparentUsernameSource.managedIdentityCount].
     */
    suspend fun managedIdentityCount(walletIdHex: String): Int

    /**
     * Derive the full canonical registration key SET for [identityIndex].
     * Same contract as [TransparentUsernameSource.previewRegistrationKeySet],
     * with the DashPay choice fixed: this seam serves only [claimInvitation],
     * which registers a BRAND-NEW identity (see its KDoc), so the set always
     * includes the DashPay ENCRYPTION/DECRYPTION pair.
     */
    suspend fun previewRegistrationKeySet(walletIdHex: String, identityIndex: Int): List<IdentityKeyPreview>

    /**
     * Persist ONE registration key's private scalar into the SDK's
     * Keystore-backed `WalletStorage`. Precondition the FFI identity signer
     * requires before [claimInvitation]. Same contract as
     * [TransparentUsernameSource.storeIdentityPrivateKey].
     */
    suspend fun storeIdentityPrivateKey(walletIdHex: String, pubkeyHex: String, privateKey: ByteArray)

    /**
     * Claim a DIP-13 invitation: register a NEW identity for the invitee at
     * [identityIndex], funded by the imported voucher carried in [uri]. The
     * asset-lock's outer signature comes from the voucher key, so only the
     * identity-key `SignerHandle` is needed. Returns the new 32-byte identity id.
     *
     * This is a FIRST commit, not a resume: the inviter's `createInvitation`
     * registers no identity at all (it only funds the voucher), and the voucher
     * is single-use, so the claim is the only transition that ever commits this
     * identity's key set. [keys] is therefore the SIX-key set including the
     * DashPay ENCRYPTION/DECRYPTION pair.
     */
    suspend fun claimInvitation(
        walletIdHex: String,
        uri: String,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>,
        nowUnix: Long
    ): ByteArray
}

/**
 * App-side carrier of a freshly created L1 invite.
 *
 * Since AAR v42int5 the SDK's `Dashpay.createInvitation` returns only the
 * bearer `dashpay://invite` link (matching iOS); the funding outpoint the app
 * keys its invite-history tracking row and funding-tx "Invitation" label on is
 * recovered from the SDK's persisted invitation row (guaranteed written to the
 * SDK Room BEFORE `createInvitation` returns), matched by the link's
 * `assetlocktx` funding txid. [outPoint] keeps the pre-v42int5
 * `CreatedInvitation` shape: raw 36 bytes, `txid_wire[32] ‖ vout_le[4]`.
 */
data class CreatedL1Invitation(val outPoint: ByteArray, val uri: String)

/** Production [L1InviteSource]: boots the SDK on demand (mirrors [DashSdkTransparentUsernameSource]). */
internal class DashSdkL1InviteSource(
    private val service: DashSdkService
) : L1InviteSource {

    private suspend fun manager(): org.dashfoundation.dashsdk.wallet.PlatformWalletManager {
        service.ensureStarted()
        return checkNotNull(service.walletManagerOrNull()) {
            "SDK wallet manager missing after ensureStarted()"
        }
    }

    private suspend fun wallet(walletIdHex: String): org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet =
        checkNotNull(manager().wallets.value[walletIdHex]) { "SDK wallet not loaded" }

    private fun walletId(walletIdHex: String): ByteArray =
        checkNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }

    override suspend fun boundWalletIdOrNull(): String? =
        manager().wallets.value.keys.singleOrNull()

    override suspend fun createInvitation(
        walletIdHex: String,
        amountDuffs: Long,
        fundingAccountIndex: Int,
        inviterIdentityId: ByteArray?,
        inviterUsername: String?,
        nowUnix: Long
    ): CreatedL1Invitation {
        val manager = manager()
        // Invitation creation moved from `identityRegistration` to the
        // `dashpay` token surface (dashpay/platform#4284 line). v42int5 returns
        // the bearer uri only; the funding outpoint the app keys its
        // invite-history row + funding-tx "Invitation" label on comes from the
        // SDK's invitation row, persisted before createInvitation returns.
        val uri = wallet(walletIdHex).dashpay.createInvitation(
            amountDuffs = amountDuffs,
            fundingAccountIndex = fundingAccountIndex,
            inviterIdentityId = inviterIdentityId,
            inviterUsername = inviterUsername,
            // The funding-spend signature uses the SAME resolver handle the
            // funded-registration path takes as coreSignerHandle.
            coreSignerHandle = manager.mnemonicResolverHandle,
            nowUnix = nowUnix
        )
        return CreatedL1Invitation(recoverFundingOutPoint(walletIdHex, uri), uri)
    }

    /**
     * Recover the created invite's raw 36-byte funding outpoint from the SDK's
     * persisted invitation row, matched by the link's `assetlocktx` funding
     * txid (big-endian display hex; the row stores the wire/LE txid). The SDK
     * guarantees the row is in Room before `createInvitation` returns, so a
     * single read suffices; a miss means the SDK broke that contract.
     */
    private suspend fun recoverFundingOutPoint(walletIdHex: String, uri: String): ByteArray {
        val fundingTxidHex = invitationLinkParam(uri, "assetlocktx")
            ?: error("created invite link carries no assetlocktx param")
        val walletId = walletId(walletIdHex)
        val row = checkNotNull(service.databaseOrNull())
            .invitationDao()
            .observeAll()
            .first()
            .firstOrNull { entity ->
                entity.walletId.contentEquals(walletId) &&
                    entity.rawOutPoint.size == 36 &&
                    displayHexOf(entity.rawOutPoint.copyOfRange(0, 32))
                        .equals(fundingTxidHex, ignoreCase = true)
            }
        return checkNotNull(row) {
            "created invitation's SDK row missing for its funding tx — persist-before-return contract broken"
        }.rawOutPoint
    }

    /** Value of ONE query param of a `dashpay://invite?…` link, URL-decoded; null when absent. */
    private fun invitationLinkParam(uri: String, key: String): String? =
        uri.substringAfter('?', "")
            .split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.isNotEmpty() }

    override suspend fun managedIdentityCount(walletIdHex: String): Int =
        wallet(walletIdHex).inMemoryIdentityIds().size

    override suspend fun previewRegistrationKeySet(
        walletIdHex: String,
        identityIndex: Int
    ): List<IdentityKeyPreview> {
        val manager = manager()
        return manager.identityRegistration.previewRegistrationKeySet(
            walletHandle = wallet(walletIdHex).handle,
            mnemonicResolverHandle = manager.mnemonicResolverHandle,
            identityIndex = identityIndex,
            // The claim is a fresh key-set commit, so derive the DashPay
            // ENCRYPTION/DECRYPTION pair too — the SDK default (-1) would give
            // only the base four and leave the claimed identity unable to send
            // contact requests through the SDK.
            count = RegistrationKeys.keyCount(includeDashPayKeys = true)
        )
    }

    override suspend fun storeIdentityPrivateKey(
        walletIdHex: String,
        pubkeyHex: String,
        privateKey: ByteArray
    ) {
        service.storeIdentityPrivateKey(pubkeyHex, privateKey, walletId(walletIdHex))
    }

    override suspend fun claimInvitation(
        walletIdHex: String,
        uri: String,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>,
        nowUnix: Long
    ): ByteArray {
        val manager = manager()
        return manager.identityRegistration.claimInvitation(
            walletHandle = wallet(walletIdHex).handle,
            uri = uri,
            identityIndex = identityIndex,
            // claimInvitation now takes a RegistrationKeySet (identityIndex +
            // rows) rather than a bare row list — mirror the transparent
            // username path's wrapping (SdkTransparentUsernameCreation).
            keys = RegistrationKeySet(identityIndex, registrationRowsFor(keys)),
            // No Core resolver: the asset-lock's outer signature comes from the
            // voucher key. The identity-key SignerHandle signs the identity
            // create transition.
            signerHandle = manager.signerHandle,
            nowUnix = nowUnix
        )
    }
}

// ── Service ─────────────────────────────────────────────────────────────

/**
 * TRANSPARENT (L1) DIP-13 invitation path — the transparent counterpart of
 * [SdkShieldedInviteCreation] and the invitation sibling of
 * [SdkTransparentUsernameCreation]. Post-cutover the dashj L1 engine is HELD
 * (0 UTXOs), so building an invite's asset-lock voucher (or claiming one) with
 * dashj fails — the funds live in the SDK. This routes:
 *
 * - CREATE ([createL1Invite]) through
 *   [org.dashfoundation.dashsdk.identity.IdentityRegistration.createInvitation]
 *   (fund a one-time asset-lock voucher from the wallet's transparent Core
 *   UTXOs, held by the SDK post-cutover), wraps the returned bearer `uri` in
 *   the existing AppsFlyer OneLink builder, and persists a tracking
 *   [Invitation] row (the same store the invite-history UI reads) — instead of
 *   the dashj [de.schildbach.wallet.service.platform.TopUpRepository.createInviteFundingTransaction]
 *   path in [de.schildbach.wallet.ui.dashpay.work.SendInviteWorker].
 * - CLAIM ([claimL1Invite]) through
 *   [org.dashfoundation.dashsdk.identity.IdentityRegistration.claimInvitation]
 *   (register the invitee's new identity funded by the imported voucher) —
 *   instead of the dashj `obtainAssetLockTransaction` + `registerIdentity`
 *   path in [de.schildbach.wallet.ui.dashpay.CreateIdentityService].
 *
 * Gating (structurally the shielded service's flag gate PLUS the transparent
 * username service's cutover gate, since L1 funds live in the SDK only
 * post-cutover): while
 * [de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.USE_KOTLIN_SDK_L1_INVITE]
 * is off OR the cutover is not committed, every entry point returns
 * [SdkWriteResult.NotBroadcast] without touching the SDK, and the dashj
 * create/claim paths are byte-identical to before. The funding spend is
 * attempted once and classified via [classifyBroadcastFailure] (the
 * three-valued no-double-broadcast contract).
 */
@Singleton
class SdkL1InviteCreation internal constructor(
    private val source: L1InviteSource,
    private val dashPayConfig: de.schildbach.wallet.ui.dashpay.utils.DashPayConfig,
    /**
     * True once the cutover is COMMITTED (persisted CUT_OVER / SETTLED — the
     * dashj engine is held). Same gate as
     * [SdkTransparentUsernameCreation.cutoverCommitted]: pre-cutover the dashj
     * path owns invite funding, so this path fails closed.
     */
    private val cutoverCommitted: suspend () -> Boolean,
    /**
     * Invitation-voucher funding amount in Core DUFFS for the given
     * contested-ness — the same fee the dashj path funds the voucher with
     * (contested → `DASH_PAY_FEE_CONTESTED`, non-contested → `DASH_PAY_FEE`).
     */
    private val feeDuffs: (contested: Boolean) -> Long,
    /**
     * Wraps the raw invite deep link in an AppsFlyer OneLink, returning the
     * OneLink short URL — or `null` when generation fails/times out (raw
     * deep-link fallback). Injected as a function (mirroring
     * [SdkShieldedInviteCreation.generateOneLink]) so the SDK layer stays
     * decoupled from [TopUpRepository] and unit-testable.
     */
    private val generateOneLink: suspend (InvitationLinkData) -> String? = { null },
    /** Current unix time in SECONDS — injectable so host-JVM tests are deterministic. */
    private val nowUnixSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * Invite-history tracking store. Null on the internal (test) constructor's
     * default so persistence is a no-op (host-JVM tests have no Room); the
     * @Inject path always supplies it. Mirrors how the shielded invite service
     * persists its tracking row via [InvitationsDao].
     */
    private val invitationsDao: InvitationsDao? = null,
    /**
     * Seed the asset-lock kind ([AssetLockKind.INVITE]) in-memory the instant
     * the funding txid is known, so the FIRST time the engine feed classifies
     * this L1 asset lock it already reads as "Invitation" instead of
     * momentarily showing "Internal" until the durable probe lands (mirrors
     * the transparent paths' [SdkTransparentUsernameCreation.seedAssetLockKind]).
     * No-op default keeps the host-JVM tests inert.
     */
    private val seedAssetLockKind: (displayHex: String, kind: AssetLockKind) -> Unit = { _, _ -> }
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: de.schildbach.wallet.ui.dashpay.utils.DashPayConfig,
        sdkL1SendService: SdkL1SendService,
        invitationsDao: InvitationsDao,
        topUpRepository: TopUpRepository,
        assetLockKindResolver: AssetLockKindResolver
    ) : this(
        source = DashSdkL1InviteSource(sdkService),
        dashPayConfig = dashPayConfig,
        cutoverCommitted = { sdkL1SendService.cutoverCommitted() },
        feeDuffs = { contested ->
            if (contested) Constants.DASH_PAY_FEE_CONTESTED.value else Constants.DASH_PAY_FEE.value
        },
        generateOneLink = { link ->
            // Bounded so a stuck AppsFlyer callback can't hang the invite;
            // any failure/timeout → null → raw deep-link fallback.
            withTimeoutOrNull(ONE_LINK_TIMEOUT_MS) {
                runCatching { topUpRepository.createShieldedAppsFlyerLink(link).shortLink }
                    .onFailure { log.warn("L1 invite OneLink generation failed", it) }
                    .getOrNull()
            }
        },
        invitationsDao = invitationsDao,
        seedAssetLockKind = { displayHex, kind -> assetLockKindResolver.seed(displayHex, kind) }
    )

    /**
     * The routing predicate the UI seams consult to decide whether to take the
     * SDK path over the dashj path: the flag is on AND the cutover is
     * committed. A read failure reads as NOT routable (dashj rules).
     */
    suspend fun isEnabledAndCommitted(): Boolean = isEnabled() && isCutoverCommitted()

    /**
     * Create a TRANSPARENT (L1) invitation for the INVITER's profile
     * ([username]/[displayName]/[avatarUrl]), funded from the wallet's
     * transparent Core UTXOs (held by the SDK post-cutover). [contested]
     * selects the funding fee (the inviter picks the username kind at the fee
     * step, exactly as for a dashj invite). [inviterIdentityIdBase58] is the
     * inviter's own identity id (enables the invitee's contact-bootstrap
     * opt-in); null-safe — a null/unparseable id degrades to a pure funding
     * voucher. On success returns the ready-to-share [InvitationLinkData] +
     * OneLink and persists a tracking [Invitation] row. The funding transfer is
     * attempted once and classified via [classifyBroadcastFailure].
     */
    suspend fun createL1Invite(
        username: String,
        displayName: String,
        avatarUrl: String,
        inviterIdentityIdBase58: String?,
        contested: Boolean
    ): SdkWriteResult<L1Invite> {
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")

        // Fail closed unless the cutover is committed — pre-cutover the dashj
        // path owns invite funding.
        val committed = try {
            cutoverCommitted()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("cutover state read failed", t)
        }
        if (!committed) return SdkWriteResult.NotBroadcast("cutover not committed")

        val amountDuffs = try {
            feeDuffs(contested)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("invite fee unavailable", t)
        }

        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast("app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("SDK bootstrap/bind lookup failed", t)
        }

        // The inviter identity id enables the contact-bootstrap opt-in; a
        // null/malformed id is fine (pure funding voucher). When we DO pass an
        // id the SDK requires a username alongside it, which we always have.
        val inviterIdentityId: ByteArray? = inviterIdentityIdBase58?.let {
            runCatching { Identifier.from(it).toBuffer() }
                .getOrElse {
                    log.warn("inviter identity id unparseable; creating a pure funding voucher")
                    null
                }
        }?.takeIf { it.size == 32 }

        // THE funding transfer — one attempt.
        val created = try {
            source.createInvitation(
                walletIdHex = walletId,
                amountDuffs = amountDuffs,
                fundingAccountIndex = ACCOUNT_INDEX,
                inviterIdentityId = inviterIdentityId,
                inviterUsername = username,
                nowUnix = nowUnixSeconds()
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return when (val classified = classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("L1 invite funding rejected pre-broadcast", t)
                    classified
                }
                else -> {
                    log.error(
                        "L1 invite funding outcome unconfirmed — the voucher MAY exist; do NOT retry",
                        t
                    )
                    SdkWriteResult.Ambiguous(t)
                }
            }
        }

        // Seed the funding asset-lock kind in-memory FIRST (synchronous, same
        // turn) so the first engine-feed classification of this L1 lock already
        // reads "Invitation" instead of momentarily "Internal" — mirroring the
        // transparent paths. The funding txid is the first 32 WIRE bytes of the
        // 36-byte outpoint; displayHexOf reverses them to the display-hex key
        // the resolver / tx_display_cache use.
        val fundingDisplayHex = displayHexOf(created.outPoint.copyOfRange(0, 32))
        seedAssetLockKind(fundingDisplayHex, AssetLockKind.INVITE)

        // Build the app-side InvitationLinkData from the SDK's bearer uri,
        // ensuring the preview params (du / display-name / avatar-url) the
        // invite UI + OneLink OG preview read are present (the SDK embeds the
        // inviter username; the display name / avatar are app-side preview
        // metadata).
        val link = InvitationLinkData(
            augmentInviteUri(created.uri, username, displayName, avatarUrl)
        )

        // Wrap the raw deep link in an AppsFlyer OneLink so the shared/copied
        // link carries the OG preview + install redirect; fall back to the raw
        // deep link if generation is unavailable.
        val shareLink = generateOneLink(link) ?: link.link.toString()

        // Track it in the invite history (best-effort — the voucher is already
        // funded, so a persistence failure must not fail the invite). Keyed by
        // a synthetic prefix + funding-outpoint hex (the Invitation entity
        // assumes an L1 base58 funding address; the SDK path has a 36-byte
        // outpoint instead), mirroring the shielded tracking row.
        try {
            persistTracking(created.outPoint, shareLink)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("L1 invite tracking persist failed — the invite is still valid", t)
        }

        return SdkWriteResult.Broadcast(L1Invite(link, shareLink))
    }

    /**
     * Claim a TRANSPARENT (L1) invitation carried by [uri] (a bearer secret —
     * never log it), registering the invitee's NEW identity ([label] is the
     * requested primary username, used only for logging here — the DPNS name
     * is registered by the caller's existing tail). The new identity is funded
     * by the imported voucher, so there is NO wallet-UTXO spend and no resume
     * gate (the voucher is single-use; a double-claim fails on the spent
     * nullifier/outpoint and is classified accordingly). Returns the new
     * identity id (base58) on success.
     */
    suspend fun claimL1Invite(uri: String, label: String): SdkWriteResult<String> {
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")

        val committed = try {
            cutoverCommitted()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("cutover state read failed", t)
        }
        if (!committed) return SdkWriteResult.NotBroadcast("cutover not committed")

        if (uri.isBlank()) return notBroadcast("blank invite uri", null)

        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast("app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("SDK bootstrap/bind lookup failed", t)
        }

        // Identity slot + canonical key set for the invitee's new identity.
        val identityIndex: Int
        val keys: List<IdentityKeyPreview>
        try {
            identityIndex = source.managedIdentityCount(walletId)
            keys = source.previewRegistrationKeySet(walletId, identityIndex)
            check(keys.isNotEmpty()) { "empty registration key set" }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("registration key derivation failed", t)
        }

        // MANDATORY KEY PERSIST — each registration row's OWN private key into
        // the SDK's Keystore-backed WalletStorage BEFORE the claim call (the
        // signer resolves identity keys by LOOKUP, not derivation — same as the
        // transparent register path). Nothing is spent yet, so a persist
        // failure is provably pre-broadcast and retry-safe.
        try {
            keys.forEach { key ->
                source.storeIdentityPrivateKey(walletId, key.publicKeyHex, key.privateKey)
                key.privateKey.fill(0)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("registration key persist failed", t)
        }

        // THE claim spend — one attempt (imports + spends the voucher).
        val identityId = try {
            source.claimInvitation(walletId, uri, identityIndex, keys, nowUnixSeconds())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return when (val classified = classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("L1 invite claim rejected pre-broadcast", t)
                    classified
                }
                else -> {
                    log.error("L1 invite claim outcome unconfirmed — identity MAY be on chain; do NOT retry", t)
                    SdkWriteResult.Ambiguous(t)
                }
            }
        }
        val identityIdBase58 = Identifier.from(identityId).toString()
        log.info("L1 invite claimed at index {} — identity {}… on chain", identityIndex, identityIdBase58.take(8))
        return SdkWriteResult.Broadcast(identityIdBase58)
    }

    private suspend fun persistTracking(fundingOutPoint36: ByteArray, shareLink: String) {
        val dao = invitationsDao ?: return
        val syntheticUserId = Identifier.from(Sha256Hash.hash(fundingOutPoint36)).toString()
        // Record the funding tx's DISPLAY-order txid (first 32 wire bytes of the
        // outpoint, reversed) so the invite-history row and the resolver's
        // txid-keyed invitation probe are coherent — previously null, which left
        // the L1 invite unclassifiable / mislabelled by txid.
        val fundingTxid = Sha256Hash.wrap(displayHexOf(fundingOutPoint36.copyOfRange(0, 32)))
        dao.insert(
            Invitation(
                fundingAddress = L1_SDK_FUNDING_ADDRESS_PREFIX + Utils.HEX.encode(fundingOutPoint36),
                userId = syntheticUserId,
                txid = fundingTxid,
                createdAt = System.currentTimeMillis(),
                sentAt = System.currentTimeMillis(),
                shortDynamicLink = shareLink,
                dynamicLink = shareLink
            )
        )
    }

    /**
     * Return [rawUri] with the preview query params guaranteed present: [PARAM_USER]
     * (`du`) is appended when the SDK's uri omitted it (the app UI dereferences
     * it non-null), and the optional display-name / avatar-url when non-blank
     * and absent — matching the params [InvitationLinkData.create] /
     * [InvitationLinkData.createShielded] embed.
     */
    private fun augmentInviteUri(
        rawUri: String,
        username: String,
        displayName: String,
        avatarUrl: String
    ): Uri {
        val parsed = rawUri.toUri()
        val existing = try {
            parsed.queryParameterNames
        } catch (e: Exception) {
            emptySet<String>()
        }
        val builder = parsed.buildUpon()
        if (!existing.contains(PARAM_USER) && username.isNotEmpty()) {
            builder.appendQueryParameter(PARAM_USER, username)
        }
        if (!existing.contains(PARAM_DISPLAY_NAME) && displayName.isNotEmpty()) {
            builder.appendQueryParameter(PARAM_DISPLAY_NAME, displayName)
        }
        if (!existing.contains(PARAM_AVATAR_URL) && avatarUrl.isNotEmpty()) {
            builder.appendQueryParameter(PARAM_AVATAR_URL, avatarUrl)
        }
        return builder.build()
    }

    private fun notBroadcast(reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("L1 invite not created ({})", reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.USE_KOTLIN_SDK_L1_INVITE) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_L1_INVITE; treating as off", e)
        false
    }

    private suspend fun isCutoverCommitted(): Boolean = try {
        cutoverCommitted()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        log.warn("failed to read the cutover state; treating as not committed", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkL1InviteCreation::class.java)

        /** BIP44 account the invitation voucher is funded from (same as the SDK's plain send). */
        private const val ACCOUNT_INDEX = 0

        /** Cap on the AppsFlyer OneLink callback so a stuck SDK can't hang the invite. */
        private const val ONE_LINK_TIMEOUT_MS = 15_000L

        /**
         * Primary-key prefix marking an [Invitation] row as an SDK-created L1
         * invite — keyed by this prefix + the 36-byte funding outpoint hex
         * (there is no dashj base58 funding address). Mirrors
         * [SdkShieldedInviteCreation.SHIELDED_FUNDING_ADDRESS_PREFIX].
         */
        const val L1_SDK_FUNDING_ADDRESS_PREFIX = "l1sdk:"

        // Invitation-link preview params (kept in sync with InvitationLinkData).
        private const val PARAM_USER = "du"
        private const val PARAM_DISPLAY_NAME = "display-name"
        private const val PARAM_AVATAR_URL = "avatar-url"
    }
}
