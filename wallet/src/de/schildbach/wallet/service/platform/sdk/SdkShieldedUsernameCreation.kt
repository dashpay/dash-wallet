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
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet.service.platform.work.RestoreIdentityOperation
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dash.wallet.common.money.Dash
import org.dashfoundation.dashsdk.errors.DashSdkError
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashfoundation.dashsdk.identity.RegistrationKeys
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Pure mapping helpers ──────────────────────────────────────────────

/**
 * The ALLOWED EXIT-DENOMINATION SET, in Platform credits — the only amounts a
 * Type-20 shielded identity-create (`shielded_identity_create_from_pool` and
 * `shielded_identity_create_from_one_time_key`) may spend out of the pool.
 * Ascending. 1 DASH = 1e11 credits.
 *
 * MIRRORS the protocol's `shielded_identity_create_denominations`
 * (rs-platform-version, `DRIVE_ABCI_VALIDATION_VERSIONS_V9` → the active set
 * from protocol version 13). This is NOT an app-side preference: a denomination
 * outside the set is refused by `select_notes_for_denomination`
 * (rs-platform-wallet `shielded/note_selection.rs`) BEFORE any Orchard build,
 * and by `validate_structure` on the transition itself, with
 *
 *     "denomination {d} is not a member of the allowed exit-denomination set {…}"
 *
 * v13 revised the pre-v13 (`…_V8`) set — it ADDS 0.03 and 0.25 DASH and RETIRES
 * 0.3 DASH. Anything computed here must be a member; see
 * [parseAllowedExitDenominations] for the runtime divergence guard that catches
 * a future revision of the set on a live network before it can strand a claim.
 *
 * INVITE FEE TIERS (0.1 / 0.3 DASH, the amounts an inviter historically debited)
 * ARE NOT EXIT DENOMINATIONS — 0.3 is not a member and can never be exited.
 */
internal val SHIELDED_IDENTITY_DENOMINATIONS_CREDITS = longArrayOf(
    3_000_000_000L, // 0.03 DASH
    10_000_000_000L, // 0.1 DASH
    25_000_000_000L, // 0.25 DASH
    50_000_000_000L, // 0.5 DASH
    100_000_000_000L // 1.0 DASH
)

/**
 * Fee → denomination mapping: the SMALLEST member of the allowed
 * exit-denomination set ([allowed], defaulting to
 * [SHIELDED_IDENTITY_DENOMINATIONS_CREDITS]) that covers [feeCredits], or null
 * when the fee is non-positive or exceeds the largest denomination. The metered
 * creation fee is taken from the denomination Rust-side and the change returns
 * to the pool, so "covers" is a simple ≥ — no extra margin is required.
 *
 * Concretely, for today's fees under the v13 set:
 * - non-contested username, `Constants.DASH_PAY_FEE` = 0.03 DASH
 *   (3e9 credits) → 0.03 DASH, now the smallest denomination and an exact
 *   match for the fee the transparent/L1 path funds.
 * - contested username, `Constants.DASH_PAY_FEE_CONTESTED` = 0.25 DASH
 *   (2.5e10 credits) → 0.25 DASH. The identity is created holding
 *   `denomination − metered fee` in credits, and a contested DPNS registration
 *   needs ~0.2 DASH of those credits for the prefunded voting balance Drive
 *   attaches to every contestable label — 0.1 cannot cover it, 0.25 can.
 *   (Before v13 this resolved to 0.3, which the active set no longer accepts.)
 */
internal fun chooseShieldedIdentityDenominationCredits(
    feeCredits: Long,
    allowed: List<Long> = SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toList()
): Long? {
    if (feeCredits <= 0) return null
    return allowed.sorted().firstOrNull { it >= feeCredits }
}

/**
 * The note values a shielded INVITE is ever minted at, in Platform credits.
 * [SdkShieldedInviteCreation.createShieldedInvite] funds a note of
 * [chooseShieldedIdentityDenominationCredits] for the picked tier, so a note
 * minted TODAY is 0.03 (non-contested) or 0.25 (contested) — but invites minted
 * before the v13 set existed carry 0.1 or 0.3, and those links are still live.
 * Ascending, union of both eras.
 *
 * A funded note value is NOT the same thing as an exit denomination: the 0.3
 * legacy contested note is a real note that no longer has a matching exit
 * denomination, so claiming it exits 0.25 and the 0.05 remainder returns to the
 * claimer's OWN Orchard change address (nothing is stranded).
 *
 * Bounds the claim-side ladder ([inviteClaimDenominationLadder]): a link-claimed
 * value outside this set is treated as unreadable, so a tampered `amt` cannot
 * make the claim chase values no invite was ever funded with.
 */
internal val SHIELDED_INVITE_NOTE_VALUES_CREDITS = longArrayOf(
    3_000_000_000L, // 0.03 DASH — non-contested invite (v13 onwards)
    10_000_000_000L, // 0.1 DASH — non-contested invite (pre-v13)
    25_000_000_000L, // 0.25 DASH — contested invite (v13 onwards)
    30_000_000_000L // 0.3 DASH — contested invite (pre-v13); NOT an exit denomination
)

/**
 * The largest member of [allowed] that is ≤ [valueCredits], or null when the
 * value is below every denomination. This is the note-value → denomination
 * rule: a note can only ever exit at a denomination it fully covers (the FFI
 * targets the denomination EXACTLY and refuses when the selected notes fall
 * short), and every credit above it re-enters the pool as the claimer's own
 * change note — so the largest covered denomination is the most the identity
 * can be funded with.
 */
internal fun largestExitDenominationAtOrBelow(
    valueCredits: Long,
    allowed: List<Long> = SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toList()
): Long? = allowed.filter { it <= valueCredits }.maxOrNull()

/**
 * The ordered list of exit denominations an invitation CLAIM attempts, per the
 * product decision that the new IDENTITY gets as much of the invite's value as
 * the protocol lets it (rather than the username-derived minimum, with the rest
 * routed to the claimer's own Orchard change address).
 *
 * EVERY element is a member of [allowed] (the allowed exit-denomination set) —
 * that is the invariant this function exists to hold. A funded note value is
 * mapped THROUGH the set, never emitted directly: a 0.3 legacy contested note
 * yields 0.25, because 0.3 is not exitable.
 *
 * - [minimumCredits] is the username-derived floor (the smallest denomination
 *   covering the chosen name's creation fee, from
 *   [chooseShieldedIdentityDenominationCredits]) — nothing below it can fund
 *   the name, so the ladder never descends past it.
 * - [fundingCredits] is the note value the link claims (`amt`,
 *   `InvitationLinkData.shieldedFundingCredits`), or null for a legacy link
 *   that carries none. It is only BELIEVED when it is a real invite note value
 *   ([SHIELDED_INVITE_NOTE_VALUES_CREDITS]); anything else (absent, junk,
 *   non-member) is treated as unknown.
 * - Unknown starts at the largest denomination any invite note could cover (the
 *   largest allowed denomination ≤ the largest invite note value = 0.25) so a
 *   legacy contested invite still funds the identity as fully as it can; the
 *   claim then falls back DOWN the ladder on the specific "note does not cover
 *   the denomination" pre-broadcast refusal (fail-closed: nothing is spent by a
 *   refused attempt — see [SdkShieldedUsernameCreation.isNoteBelowDenominationFailure]).
 *
 * The same descent is what defuses a tampered-high `amt`: the oversized attempt
 * finds no covering note, the FFI refuses pre-broadcast, and the claim steps
 * down until it meets the note that actually exists. An empty result means no
 * denomination the invite could cover also covers the username fee (the caller
 * refuses without attempting anything) — e.g. a 0.03 invite and a contested
 * name.
 */
internal fun inviteClaimDenominationLadder(
    minimumCredits: Long,
    fundingCredits: Long?,
    allowed: List<Long> = SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toList()
): List<Long> {
    val believedNoteValue = fundingCredits?.takeIf { it in SHIELDED_INVITE_NOTE_VALUES_CREDITS }
    val noteCeiling = believedNoteValue ?: SHIELDED_INVITE_NOTE_VALUES_CREDITS.max()
    val start = largestExitDenominationAtOrBelow(noteCeiling, allowed) ?: return emptyList()
    return allowed.filter { it in minimumCredits..start }.sortedDescending()
}

/**
 * The invite-claim OVERAGE, in Platform credits — the part of a claimed note's
 * value that could NOT be exited into the new identity because the exit
 * denomination is clamped to the allowed set — or null when there is none (or
 * none can be safely determined).
 *
 * A legacy 0.3 contested note exits at 0.25; the 0.05 remainder re-enters the
 * pool as a change note to the CLAIMER's own Orchard address. Per the product
 * decision ("all remaining value after username creation ends up on the
 * identity") that remainder is then moved onto the new identity by
 * [ShieldedInviteOverageTopUp] — this function is what tells the claim path
 * how much that is.
 *
 * Non-null ONLY when the overage is provable from the claim itself:
 * - [fundingCredits] (the link's `amt`) is a real invite note value
 *   ([SHIELDED_INVITE_NOTE_VALUES_CREDITS]) — junk/absent values prove
 *   nothing; and
 * - [spentDenominationCredits] is exactly the ladder's FIRST rung for that
 *   note value ([largestExitDenominationAtOrBelow]) — a claim that DESCENDED
 *   proves the `amt` lied high (the note was smaller than claimed), so the
 *   difference is fiction and topping it up would chase value that does not
 *   exist; and
 * - the difference is positive (an exact-denomination invite has no overage).
 */
internal fun inviteClaimOverageCredits(
    fundingCredits: Long?,
    spentDenominationCredits: Long,
    allowed: List<Long> = SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toList()
): Long? {
    val believed = fundingCredits?.takeIf { it in SHIELDED_INVITE_NOTE_VALUES_CREDITS } ?: return null
    if (largestExitDenominationAtOrBelow(believed, allowed) != spentDenominationCredits) return null
    return (believed - spentDenominationCredits).takeIf { it > 0 }
}

/**
 * The allowed exit-denomination set the SDK itself quoted in a refusal, parsed
 * out of [message], or null when the message is not that refusal. The Rust
 * refusal (rs-platform-wallet `shielded/note_selection.rs`, and the identical
 * one in rs-dpp's Type-20 builder) formats the live set inline:
 *
 *     "…denomination 30000000000 is not a member of the allowed
 *      exit-denomination set [3000000000, 10000000000, 25000000000, …]"
 *
 * so the refusal is also the ONLY runtime channel that reports the set — the
 * SDK exposes no accessor for it. Used to (a) log a loud divergence warning and
 * (b) re-derive the claim ladder from the authoritative set, so a future
 * protocol revision of the denominations costs one refused (fail-closed,
 * nothing-spent) attempt instead of stranding every claim.
 */
internal fun parseAllowedExitDenominations(message: String?): List<Long>? {
    val marker = "allowed exit-denomination set"
    val at = message?.indexOf(marker) ?: return null
    if (at < 0) return null
    val open = message.indexOf('[', at).takeIf { it >= 0 } ?: return null
    val close = message.indexOf(']', open).takeIf { it >= 0 } ?: return null
    val values = message.substring(open + 1, close)
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.toLongOrNull() ?: return null }
    return values.takeIf { it.isNotEmpty() && it.all { v -> v > 0 } }?.sorted()
}

/**
 * The shielded pool balance required to fund a username whose creation
 * fee is [fee] — the chosen Type-20 denomination as [Dash], or null when
 * no denomination covers the fee. This is the affordability bar the UI
 * must use ("denomination affordability, not just fee"): a pool holding
 * more than the fee but less than the smallest covering denomination
 * cannot actually fund the creation.
 */
fun shieldedIdentityFundingRequirement(fee: Dash): Dash? {
    val feeCredits = try {
        dashToCredits(fee)
    } catch (e: ArithmeticException) {
        return null
    }
    return chooseShieldedIdentityDenominationCredits(feeCredits)?.let(::creditsToDash)
}

/**
 * Decode a DIP-0018 bech32m Platform address (`dash1…` / `tdash1…`) to
 * the raw 21-byte PlatformAddress STORAGE bytes (variant tag + 20-byte
 * hash) the Type-20 `fallbackAddress` FFI parameter wants. The bech32m
 * DISPLAY type byte differs from the bincode variant tag the FFI
 * deserializes (rs-dpp `PlatformAddress`: P2PKH display `0xb0` → tag
 * `0x00`, P2SH display `0x80` → tag `0x01`) — passing the display byte
 * through fails FFI input validation with `UnexpectedVariant { allowed:
 * 0..1, found: 176 }` (observed live on the first on-device shielded
 * username creation). Null when the input is not a well-formed Platform
 * address under [hrp] (wrong HRP = wrong network, wrong payload length,
 * or an unknown type byte).
 */
internal fun decodePlatformAddressRaw21(address: String, hrp: String): ByteArray? {
    val decoded = Bech32m.decode(address.trim().lowercase()) ?: return null
    if (decoded.hrp != hrp) return null
    val data = decoded.data
    if (data.size != 21) return null
    val storageTag: Byte = when (data[0].toInt() and 0xFF) {
        0xb0 -> 0x00 // P2PKH
        0x80 -> 0x01 // P2SH
        else -> return null
    }
    return byteArrayOf(storageTag) + data.copyOfRange(1, data.size)
}

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over the Kotlin SDK's shielded-identity-creation surface
 * ([org.dashfoundation.dashsdk.wallet.PlatformWalletManager]'s Type-20 +
 * identity-registration slices), so the flag/preflight/no-double-broadcast
 * orchestration in [SdkShieldedUsernameCreation] is host-JVM
 * unit-testable — the real calls need `libdash_sdk`.
 */
interface ShieldedUsernameSource {
    /** Same contract as [SdkDashPayWriteSource.boundWalletIdOrNull]. */
    suspend fun boundWalletIdOrNull(): String?

    /**
     * Number of identities the bound SDK wallet already manages — the
     * next free identity index on the DIP-9 identity chain (0 for the
     * fresh-wallet create path this flow serves; index gaps are a
     * theoretical concern only reachable outside it).
     */
    suspend fun managedIdentityCount(walletIdHex: String): Int

    /**
     * Derive the full canonical registration key SET (keyId 0 MASTER/AUTH,
     * 1 CRITICAL/AUTH, 2 HIGH/AUTH, 3 TRANSFER/CRITICAL, 4 ENCRYPTION and
     * 5 DECRYPTION bound to DashPay's `contactRequest`) for [identityIndex].
     * Pure compute — no Platform RPCs, nothing persisted.
     *
     * The DashPay pair is always included: BOTH consumers
     * ([createIdentityFromPool] and [createIdentityFromOneTimeKey]) put a
     * BRAND-NEW identity on chain — neither resumes a key set some earlier
     * attempt already committed — so this is always the first (and only)
     * chance to commit the ENCRYPTION key `select_own_encryption_key`
     * requires for SDK-routed contact requests.
     */
    suspend fun previewRegistrationKeySet(walletIdHex: String, identityIndex: Int): List<IdentityKeyPreview>

    /**
     * Persist the private [privateKey] scalar of ONE registration key
     * (whose compressed public half is [pubkeyHex]) into the SDK's
     * Keystore-backed `WalletStorage`, owned by [walletIdHex] — the
     * precondition for the FFI signer (both the Type-20 create and the DPNS
     * registration sign with these keys). The signer resolves each identity
     * key's private half by LOOKUP (`retrievePrivateKey(pubkeyHex)`), never
     * by derivation, so an unstored key throws `SigningKeyUnavailable`. A
     * public-key encrypt: never auth-gated, safe unprompted. Throws on
     * failure. See [DashSdkService.storeIdentityPrivateKey].
     */
    suspend fun storeIdentityPrivateKey(walletIdHex: String, pubkeyHex: String, privateKey: ByteArray)

    /**
     * The wallet's own DIP-17 Platform receive address (bech32m) for the
     * REQUIRED creation-failure fallback, or null when the SDK's address
     * store has no row for the wallet yet. Same selection as
     * [ShieldedSource.ownPlatformAddressOrNull]: lowest unused index,
     * falling back to the lowest-index row of any state.
     */
    suspend fun fallbackPlatformAddressOrNull(walletIdHex: String): String?

    /**
     * The wallet's OWN 43-byte default Orchard payment address (ZIP-32
     * account 0) — the `changeAddressRaw43` the L2 invitation-claim FFI
     * ([createIdentityFromOneTimeKey]) sends any over-funding change note to.
     * Null when the SDK has no bound shielded sub-wallet for the wallet yet.
     */
    suspend fun ownDefaultOrchardAddressRaw43(walletIdHex: String): ByteArray?

    /**
     * Type 20: create an identity directly from the shielded pool. Spends
     * a note of the fixed [denominationCredits] denomination; the metered
     * creation fee comes out of it and the change returns to the pool;
     * [fallbackAddress21] receives the value (minus a penalty) if creation
     * fails a stateful check. Blocks for the ~30s Halo 2 proof. Returns
     * the new 32-byte identity id.
     */
    suspend fun createIdentityFromPool(
        walletIdHex: String,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>,
        denominationCredits: Long,
        fallbackAddress21: ByteArray
    ): ByteArray

    /**
     * Type 20, INVITATION-CLAIM variant: create an identity funded from the
     * one-time Orchard spending key [oneTimeSk] (32 bytes) carried by a
     * shielded (L2) invitation link, instead of the wallet's own pool. The
     * SDK derives that key's viewing keys, transiently scans for the note(s)
     * funded to it, and spends a note of the fixed [denominationCredits]
     * denomination to fund a new identity at [identityIndex].
     * [changeAddressRaw43] is the CLAIMER's own 43-byte default Orchard
     * address that receives any over-funding change (zero for a well-formed
     * invitation); [fundingBirthHeight] is an advisory scan hint (null = no
     * hint). [keys] / [fallbackAddress21] match [createIdentityFromPool].
     * Blocks for the ~30s Halo 2 proof. Returns the new 32-byte identity id.
     */
    suspend fun createIdentityFromOneTimeKey(
        walletIdHex: String,
        oneTimeSk: ByteArray,
        changeAddressRaw43: ByteArray,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>,
        denominationCredits: Long,
        fallbackAddress21: ByteArray,
        fundingBirthHeight: Int?
    ): ByteArray

    /**
     * Register [label] as a DPNS name for [identityId]. Contested labels
     * need no special client-side handling: dpp derives the ~0.2 DASH
     * prefunded voting balance from the contested unique index
     * automatically (`prefunded_voting_balance_for_document`), paid from
     * the identity's credit balance — which is why a contested creation
     * must fund the identity with the 0.3 denomination. Returns the full
     * domain name (e.g. `"alice.dash"`).
     */
    suspend fun registerDpnsName(walletIdHex: String, identityId: ByteArray, label: String): String
}

/** Production [ShieldedUsernameSource]: boots the SDK on demand. */
internal class DashSdkShieldedUsernameSource(
    private val service: DashSdkService
) : ShieldedUsernameSource {

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
            // Both Type-20 creates commit a fresh key set, so derive the
            // DashPay ENCRYPTION/DECRYPTION pair too — the SDK default (-1)
            // would give only the base four and leave the new identity unable
            // to send contact requests through the SDK.
            count = RegistrationKeys.keyCount(includeDashPayKeys = true)
        )
    }

    override suspend fun storeIdentityPrivateKey(
        walletIdHex: String,
        pubkeyHex: String,
        privateKey: ByteArray
    ) {
        // Encrypt the preview row's own private scalar into the SDK's
        // Keystore-backed WalletStorage under the pubkey hex, owned by the
        // wallet — exactly the persist the Type-20 create / DPNS registration
        // FFI signer requires (it resolves identity keys by lookup, never by
        // derivation). Uses the same WalletStorage.storePrivateKey primitive
        // the app's key-heal pass lands on; NOT repairIdentityKey (which is a
        // POST-registration repair that reads breadcrumbs off a persisted
        // public_keys row that does not exist yet before create).
        service.storeIdentityPrivateKey(pubkeyHex, privateKey, walletId(walletIdHex))
    }

    override suspend fun fallbackPlatformAddressOrNull(walletIdHex: String): String? {
        service.ensureStarted()
        val database = checkNotNull(service.databaseOrNull()) {
            "SDK database missing after ensureStarted()"
        }
        val rows = database.platformAddressDao().observeByWallet(walletId(walletIdHex)).first()
        val row = rows.filter { !it.isUsed }.minByOrNull { it.addressIndex }
            ?: rows.minByOrNull { it.addressIndex }
        return row?.address
    }

    override suspend fun ownDefaultOrchardAddressRaw43(walletIdHex: String): ByteArray? =
        manager().shieldedDefaultAddress(walletId(walletIdHex), account = 0)

    override suspend fun createIdentityFromPool(
        walletIdHex: String,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>,
        denominationCredits: Long,
        fallbackAddress21: ByteArray
    ): ByteArray = manager().shieldedIdentityCreateFromPool(
        walletId = walletId(walletIdHex),
        identityIndex = identityIndex,
        keys = registrationRowsFor(keys),
        denomination = denominationCredits,
        fallbackAddress = fallbackAddress21
    )

    override suspend fun createIdentityFromOneTimeKey(
        walletIdHex: String,
        oneTimeSk: ByteArray,
        changeAddressRaw43: ByteArray,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>,
        denominationCredits: Long,
        fallbackAddress21: ByteArray,
        fundingBirthHeight: Int?
    ): ByteArray = manager().shieldedIdentityCreateFromOneTimeKey(
        walletId = walletId(walletIdHex),
        oneTimeSk = oneTimeSk,
        changeAddressRaw43 = changeAddressRaw43,
        identityIndex = identityIndex,
        keys = registrationRowsFor(keys),
        denomination = denominationCredits,
        fallbackAddress = fallbackAddress21,
        fundingBirthHeight = fundingBirthHeight
    )

    override suspend fun registerDpnsName(
        walletIdHex: String,
        identityId: ByteArray,
        label: String
    ): String {
        val manager = manager()
        return manager.identityRegistration.registerDpnsName(
            walletHandle = wallet(walletIdHex).handle,
            identityId = identityId,
            label = label,
            signerHandle = manager.signerHandle
        )
    }
}

// ── Outcome / executor state ──────────────────────────────────────────

/** Whether the DPNS name landed after a successful identity creation. */
enum class ShieldedUsernameNameStatus {
    /** Identity created AND the username registered. */
    REGISTERED,

    /**
     * Identity created but the name registration provably did not submit
     * — the identity exists on chain without a name; the legacy handoff
     * still runs so the state machine can pick the identity up, and the
     * user re-requests the name through the normal flow.
     */
    NOT_REGISTERED,

    /**
     * Identity created and the name registration outcome is unknown (may
     * be on chain). Never retried here; the legacy handoff reconciles.
     */
    AMBIGUOUS
}

/**
 * Payload of a [SdkWriteResult.Broadcast] from
 * [SdkShieldedUsernameCreation.createUsernameFromShielded]: the identity
 * IS on chain (the pool was spent); [nameStatus] qualifies the primary
 * username and [secondaryNameStatus] the optional secondary (dual-username
 * flow; null when none was requested).
 */
data class ShieldedUsernameCreationOutcome(
    val identityIdBase58: String,
    val nameStatus: ShieldedUsernameNameStatus,
    val nameFailureReason: String? = null,
    val secondaryNameStatus: ShieldedUsernameNameStatus? = null,
    val secondaryNameFailureReason: String? = null
)

/**
 * App-scope submit state of the one allowed shielded username creation —
 * the same single-flight/no-double-broadcast machine as
 * [de.schildbach.wallet.ui.shielded.ShieldedTransferExecutor]'s
 * `ShieldedSubmitState`, minimally re-expressed for this operation.
 */
sealed class ShieldedUsernameSubmitState {
    object Idle : ShieldedUsernameSubmitState()

    /** ~30s Halo 2 proof + broadcast(s) in flight — indeterminate progress. */
    object Proving : ShieldedUsernameSubmitState()

    /** Identity created ([SdkWriteResult.Broadcast]); handoff enqueued. */
    data class Created(val outcome: ShieldedUsernameCreationOutcome) : ShieldedUsernameSubmitState()

    /** Provably nothing was spent — retry is safe. */
    data class NotSent(val reason: String) : ShieldedUsernameSubmitState()

    /**
     * The identity creation MAY have gone through — terminal and sticky;
     * [SdkShieldedUsernameCreation.submit] refuses re-submission until the
     * process restarts (Ambiguous is never retried).
     */
    object MayHaveGoneThrough : ShieldedUsernameSubmitState()
}

// ── Service ───────────────────────────────────────────────────────────

/**
 * Shielded-funded username creation (the iOS-parity L2 path): creates the
 * identity DIRECTLY from the wallet's shielded (Orchard) pool via the
 * Type-20 `shieldedIdentityCreateFromPool` FFI, then registers the DPNS
 * name — no L1 asset lock, no dashj involvement. Behind
 * [DashPayConfig.USE_KOTLIN_SDK_SHIELDED] (default OFF): while the flag is
 * off every entry point returns [SdkWriteResult.NotBroadcast]/false
 * without touching the SDK (the inertness contract), and the L1
 * (`DASH_BALANCE`) create path is byte-identical to before.
 *
 * ## Pipeline (one [createUsernameFromShielded] call)
 *
 * 1. Preflights (nothing submitted if any fails → NotBroadcast): flag on,
 *    contested-ness derived from the label (contested labels take the
 *    0.25-fee → 0.3-denomination mapping so the identity's credits cover
 *    the ~0.2 prefunded voting balance the contested DPNS registration
 *    debits; non-contested take 0.03 → 0.1), fee → denomination mapping
 *    resolves ([chooseShieldedIdentityDenominationCredits]), shielded runtime
 *    ready ([ShieldedBalanceService.ensureShieldedReady]) with the pool
 *    READY (trustworthy balance, not a mid-sync placeholder zero) and
 *    balance ≥ the chosen denomination, wallet bound, registration key
 *    set derived + persisted to the Keystore (the FFI signer's
 *    precondition), and the REQUIRED creation-failure fallback Platform
 *    address resolved from the SDK's own DIP-17 address store.
 * 2. THE spend: `shieldedIdentityCreateFromPool` — blocks for the ~30s
 *    Halo 2 proof. One attempt; failures go through the shared
 *    [classifyBroadcastFailure] decision table and Ambiguous is NEVER
 *    retried.
 * 3. Best-effort DPNS registration (`registerDpnsName`) — the identity
 *    already exists, so a name failure demotes the outcome
 *    ([ShieldedUsernameNameStatus]) instead of the result.
 * 4. Legacy handoff: the identity now exists ON CHAIN, which is exactly
 *    the state the wallet's restore machinery is built for —
 *    [RestoreIdentityOperation] (→ `RestoreIdentityWorker`) recovers the
 *    identity by its slot-0 public key hash (SDK/dashj DIP-9 derivation
 *    parity at identity index 0 — see [SdkDashPayWrites]' key-parity
 *    note), recovers the username, and seeds [de.schildbach.wallet
 *    .database.entity.BlockchainIdentityData] through its normal states
 *    so the existing UI observers (processing header, More screen)
 *    reflect the new identity. No legacy state is hand-written here.
 *
 * ## App-scope execution ([submit])
 *
 * The proof outlives any screen, so — mirroring
 * [de.schildbach.wallet.ui.shielded.ShieldedTransferExecutor] — [submit]
 * runs the pipeline on the application scope and publishes
 * [submitState]; ViewModels only mirror it. Single-flight: a submit is
 * refused unless the state is Idle or the retry-safe NotSent.
 * (Follow-up, deliberately not done here: a system notification when the
 * outcome lands while the app is backgrounded — the transfer executor's
 * notification plumbing is transfer-specific copy.)
 */
@Singleton
class SdkShieldedUsernameCreation internal constructor(
    private val source: ShieldedUsernameSource,
    private val dashPayConfig: DashPayConfig,
    private val shieldedBalanceService: ShieldedBalanceService,
    /**
     * Creation fee in credits for a username of the given contested-ness
     * (prod: `DASH_PAY_FEE_CONTESTED` = 0.25 DASH for contested labels,
     * `DASH_PAY_FEE` = 0.03 DASH otherwise) — the input to the
     * denomination mapping, NOT the amount spent.
     */
    private val feeCredits: (contested: Boolean) -> Long,
    /** HRP the fallback Platform address must decode under (`dash`/`tdash`). */
    private val displayHrp: () -> String,
    /**
     * Hands the freshly created on-chain identity to the legacy state
     * machine (prod: enqueue [RestoreIdentityOperation]) — best-effort,
     * never affects the returned result.
     */
    private val handOffToLegacy: (identityIdBase58: String) -> Unit,
    /**
     * Drive the persisted identity CREATION STATE (and error message) — the
     * same [de.schildbach.wallet.database.entity.BlockchainIdentityConfig]
     * seam the classic [de.schildbach.wallet.ui.dashpay.CreateIdentityService]
     * path, the transparent path, and the restore worker use — so the home
     * tile reflects the DPNS registration step and, for a confirmed
     * non-contested primary, reaches DONE immediately. No-op default keeps the
     * host-JVM tests (which have no DataStore) inert.
     */
    private val driveCreationState: suspend (state: IdentityCreationState, errorMessage: String?) -> Unit =
        { _, _ -> },
    /**
     * Persist the identity id + requested label(s) + the `restoring` flag so
     * the tile has context during (and after) the DPNS step. On a name
     * FAILURE this is called with `restoring = true` so a tile retry routes to
     * the restore worker (re-drives ONLY the DPNS step for the EXISTING
     * identity — never re-funds). No-op default keeps the host-JVM tests inert.
     */
    private val persistNameContext: suspend (
        identityIdBase58: String,
        username: String,
        secondaryUsername: String?,
        restoring: Boolean
    ) -> Unit = { _, _, _, _ -> },
    /**
     * Persist a pending invite-claim OVERAGE record (see
     * [inviteClaimOverageCredits] / [ShieldedInviteOverageTopUp]) — called
     * BEFORE the claim result is returned, so an app death right after the
     * claim still finds the record at next launch and completes the top-up.
     * Best-effort: a persist failure is logged and never affects the claim
     * result (the identity is on chain either way). No-op default keeps the
     * host-JVM tests inert.
     */
    private val recordInviteOverage: suspend (identityIdBase58: String, overageCredits: Long) -> Unit =
        { _, _ -> },
    /** Scope for [submit]; null (tests' default path) makes submit inert. */
    private val executorScope: CoroutineScope? = null
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        shieldedBalanceService: ShieldedBalanceService,
        walletApplication: WalletApplication,
        blockchainIdentityConfig: BlockchainIdentityConfig,
        applicationScope: CoroutineScope
    ) : this(
        source = DashSdkShieldedUsernameSource(sdkService),
        dashPayConfig = dashPayConfig,
        shieldedBalanceService = shieldedBalanceService,
        // Lazy: Constants untouched at construction (inert-until-called).
        feeCredits = { contested ->
            val fee = if (contested) Constants.DASH_PAY_FEE_CONTESTED else Constants.DASH_PAY_FEE
            dashToCredits(Dash(fee.value))
        },
        displayHrp = { shieldedHrp(toSdkNetwork(Constants.NETWORK_PARAMETERS)) },
        handOffToLegacy = { identityId ->
            RestoreIdentityOperation(walletApplication).create(identityId, fromCreation = true).enqueue()
        },
        driveCreationState = { state, errorMessage ->
            blockchainIdentityConfig.updateCreationState(state, errorMessage)
        },
        persistNameContext = { identityIdBase58, username, secondaryUsername, restoring ->
            blockchainIdentityConfig.set(BlockchainIdentityConfig.IDENTITY_ID, identityIdBase58)
            blockchainIdentityConfig.set(BlockchainIdentityConfig.USERNAME, username)
            secondaryUsername?.let {
                blockchainIdentityConfig.set(BlockchainIdentityConfig.USERNAME_SECONDARY, it)
            }
            blockchainIdentityConfig.set(BlockchainIdentityConfig.RESTORING, restoring)
        },
        recordInviteOverage = { identityIdBase58, overageCredits ->
            ShieldedInviteOverageTopUp.persistPendingRecord(dashPayConfig, identityIdBase58, overageCredits)
        },
        executorScope = applicationScope
    )

    /** Test seam: the pipeline blocks for a ~30s proof — keep it off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _submitState =
        MutableStateFlow<ShieldedUsernameSubmitState>(ShieldedUsernameSubmitState.Idle)

    /** The in-flight/terminal state of the one allowed creation operation. */
    val submitState: StateFlow<ShieldedUsernameSubmitState> = _submitState.asStateFlow()

    /**
     * Start the creation on the application scope. Returns false — and
     * submits NOTHING — unless the current state is Idle or the
     * provably-pre-broadcast NotSent (retry-safe). The Idle→Proving
     * transition is atomic under [this].
     */
    fun submit(username: String, secondaryUsername: String? = null): Boolean {
        val scope = executorScope
        if (scope == null) {
            log.warn("shielded username creation submit refused: no executor scope")
            return false
        }
        synchronized(this) {
            val state = _submitState.value
            if (state != ShieldedUsernameSubmitState.Idle &&
                state !is ShieldedUsernameSubmitState.NotSent
            ) {
                log.warn("shielded username creation submit refused: an operation is {}", state)
                return false
            }
            _submitState.value = ShieldedUsernameSubmitState.Proving
        }
        scope.launch {
            val result = withContext(ioDispatcher) {
                createUsernameFromShielded(username, secondaryUsername)
            }
            val outcome = when (result) {
                is SdkWriteResult.Broadcast -> ShieldedUsernameSubmitState.Created(result.value)
                is SdkWriteResult.NotBroadcast -> ShieldedUsernameSubmitState.NotSent(result.reason)
                is SdkWriteResult.Ambiguous -> ShieldedUsernameSubmitState.MayHaveGoneThrough
            }
            synchronized(this@SdkShieldedUsernameCreation) {
                _submitState.value = outcome
            }
        }
        return true
    }

    /**
     * The caller surfaced the result — Created/NotSent reset to Idle. The
     * funds-critical [ShieldedUsernameSubmitState.MayHaveGoneThrough]
     * stays sticky (never re-submittable this process); an in-flight
     * Proving is never cleared.
     */
    fun acknowledge() {
        synchronized(this) {
            when (_submitState.value) {
                ShieldedUsernameSubmitState.Proving,
                ShieldedUsernameSubmitState.MayHaveGoneThrough -> Unit
                else -> _submitState.value = ShieldedUsernameSubmitState.Idle
            }
        }
    }

    /**
     * The full pipeline — see the class KDoc. One broadcast attempt for
     * the Type-20 spend; the [SdkWriteResult] three-valued contract holds
     * ([SdkWriteResult.Ambiguous] is never retried by anyone).
     */
    suspend fun createUsernameFromShielded(
        username: String,
        secondaryUsername: String? = null
    ): SdkWriteResult<ShieldedUsernameCreationOutcome> {
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")
        val label = username.trim()
        if (label.isEmpty()) {
            return notBroadcast("empty username", null)
        }
        val secondaryLabel = secondaryUsername?.trim()?.takeIf { it.isNotEmpty() && it != label }

        // Contested-ness is derived HERE from the labels (same rule the
        // request screen gates on) so a caller can never pair a contested
        // name with the too-small non-contested denomination — the name
        // registration would fail its ~0.2 prefunded-voting-balance
        // debit after the identity was already created. In the dual-
        // username flow the primary is the contested one, but either
        // label being contestable bumps the funding requirement.
        val contested = try {
            Names.isUsernameContestable(label) ||
                (secondaryLabel != null && Names.isUsernameContestable(secondaryLabel))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("contested-ness check failed", t)
        }

        // Fee → denomination (explicit mapping, see
        // chooseShieldedIdentityDenominationCredits: non-contested
        // 0.03 DASH → 0.03, contested 0.25 DASH → 0.25 — both members of the
        // allowed exit-denomination set).
        val fee = try {
            feeCredits(contested)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("username fee unavailable", t)
        }
        val denominationCredits = chooseShieldedIdentityDenominationCredits(fee)
            ?: return notBroadcast("no shielded denomination covers the username fee", null)

        // Shielded runtime preflights — nothing submitted if any fails.
        if (!shieldedBalanceService.ensureShieldedReady()) {
            return notBroadcast(REASON_RUNTIME_NOT_READY, null)
        }
        if (shieldedBalanceService.shieldedSyncStatus.value != ShieldedSyncStatus.READY) {
            // A mid-sync zero (or partial) balance is a placeholder, not
            // evidence — never spend against it.
            return notBroadcast(REASON_POOL_STILL_SYNCING, null)
        }
        // Funding-note anchor preflight: pool READY clears BEFORE a freshly
        // shielded funding note is anchored (a recorded commitment-tree
        // anchor), and a too-soon Type-20 create bounces with
        // ShieldedNoRecordedAnchor. Require the funding denomination to be
        // covered by an ANCHORED unspent note set so the UI keeps the calm
        // "still preparing" surface (transient reason) instead of that bounce.
        val fundingNoteAnchored = try {
            shieldedBalanceService.isFundingNoteAnchoredForDenomination(
                creditsToDash(denominationCredits)
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(REASON_FUNDING_NOTE_UNCONFIRMED, t)
        }
        if (!fundingNoteAnchored) {
            return notBroadcast(REASON_FUNDING_NOTE_UNCONFIRMED, null)
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
                    "DASH funding denomination",
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

        // Identity slot + canonical key set. previewRegistrationKeySet returns
        // the private scalars in hand (IdentityKeyPreview.privateKey) so the
        // caller can store them for the signer — see the persist step below.
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

        // MANDATORY KEY PERSIST before the Type-20 create — the FFI signer
        // resolves each registration key's private half by LOOKUP
        // (retrievePrivateKey by pubkey hex), never by derivation, so an
        // unstored key throws SigningKeyUnavailable (the same on-device
        // key-persist crash the transparent path hit). Store each preview
        // row's own scalar, then zero the in-memory copy (storePrivateKey has
        // encrypted it into the keystore the signer reads).
        try {
            keys.forEach { key ->
                source.storeIdentityPrivateKey(walletId, key.publicKeyHex, key.privateKey)
                key.privateKey.fill(0)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("registration key persist failed", t)
        }

        // REQUIRED creation-failure fallback: the wallet's own DIP-17
        // Platform receive address, decoded to the raw 21-byte payload.
        val fallbackAddress = try {
            source.fallbackPlatformAddressOrNull(walletId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("fallback platform address lookup failed", t)
        } ?: return notBroadcast("no platform receive address in the SDK store yet", null)
        val fallbackRaw21 = decodePlatformAddressRaw21(fallbackAddress, displayHrpSafe())
            ?: return notBroadcast("malformed fallback platform address", null)

        // THE spend — one attempt, ~30s Halo 2 proof.
        val identityId = try {
            source.createIdentityFromPool(walletId, identityIndex, keys, denominationCredits, fallbackRaw21)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return when (val classified = classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("shielded identity creation rejected pre-broadcast", t)
                    classified
                }
                else -> {
                    log.error(
                        "shielded identity creation outcome unconfirmed — it MAY be on chain " +
                            "and the spent notes stay reserved; do NOT retry",
                        t
                    )
                    SdkWriteResult.Ambiguous(t)
                }
            }
        }
        val identityIdBase58 = Identifier.from(identityId).toString()
        log.info(
            "shielded-funded identity created at index {} ({}…) — {} denomination, contested={}",
            identityIndex,
            identityIdBase58.take(8),
            creditsToDash(denominationCredits).toPlainString(),
            contested
        )

        // The identity is on chain. The DPNS name is a SEPARATE, REQUIRED
        // transition — persist the identity + labels first so the home tile
        // has context (and, on failure, a tile retry can re-drive ONLY the
        // DPNS step for THIS identity, never re-fund). restoring=false here so
        // the tile reads "requesting your username" while the name lands.
        try {
            persistNameContext(identityIdBase58, label, secondaryLabel, false)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to persist identity/name context before DPNS registration", t)
        }

        // State-tracked DPNS names — the identity exists either way. Each
        // registration drives the creation state (preorder → registering →
        // registered) so the home tile shows the step. The secondary
        // (dual-username flow) is registered independently of the primary's
        // outcome: each name that can land should land, and the legacy handoff
        // below reconciles whatever is on chain.
        val (nameStatus, nameFailure) = registerNameTracked(
            walletId,
            identityId,
            label,
            preorderState = IdentityCreationState.PREORDER_REGISTERING,
            registeringState = IdentityCreationState.USERNAME_REGISTERING,
            registeredState = IdentityCreationState.USERNAME_REGISTERED
        )
        val secondaryResult = secondaryLabel?.let {
            registerNameTracked(
                walletId,
                identityId,
                it,
                preorderState = IdentityCreationState.PREORDER_SECONDARY_REGISTERING,
                registeringState = IdentityCreationState.USERNAME_SECONDARY_REGISTERING,
                registeredState = IdentityCreationState.USERNAME_SECONDARY_REGISTERED
            )
        }

        // A NON-contested confirmed primary is TERMINAL right here: the
        // identity and its uncontested name are both on chain, so drive the
        // creation state to DONE now — independent of (and before) the
        // best-effort handoff — so `hasUsername` flips immediately and the
        // home welcome tile + DashPay bottom-nav appear without waiting for
        // RestoreIdentityWorker's full network recovery to write DONE later.
        // A CONTESTED primary must NOT be forced to DONE: it still routes
        // through the worker to VOTING, so leave its state (USERNAME_REGISTERED)
        // untouched here.
        val primaryConfirmed = nameStatus == ShieldedUsernameNameStatus.REGISTERED
        if (primaryConfirmed && !contested) {
            try {
                driveCreationState(IdentityCreationState.DONE, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn(
                    "failed to advance creation state to DONE for confirmed non-contested primary {}…; " +
                        "RestoreIdentityWorker recovery will still reach DONE",
                    identityIdBase58.take(8),
                    t
                )
            }
        } else if (!primaryConfirmed) {
            // The PRIMARY DPNS name did NOT confirm. The identity IS on chain,
            // but the username is not — registerNameTracked left the creation
            // state at USERNAME_REGISTERING (with a retryable error for a
            // provably pre-broadcast rejection; no hard error for an ambiguous
            // one). Mark restoring so a tile retry — or the background sync —
            // routes to RestoreIdentityWorker, which re-drives ONLY the DPNS
            // step for the existing identity (never re-funds).
            try {
                persistNameContext(identityIdBase58, label, secondaryLabel, true)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to mark identity restoring after DPNS registration failure", t)
            }
        }

        // Hand the on-chain identity to the legacy state machine (restore
        // path). Best-effort: a handoff failure must not demote a real
        // Broadcast — the identity/name are on chain; the restore also
        // re-runs from PlatformSyncService's preBlockDownload discovery. For a
        // contested primary the handoff carries it through to VOTING.
        try {
            handOffToLegacy(identityIdBase58)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.error(
                "legacy identity handoff failed for {}… — the identity is on chain; " +
                    "platform sync recovery will pick it up",
                identityIdBase58.take(8),
                t
            )
        }

        return SdkWriteResult.Broadcast(
            ShieldedUsernameCreationOutcome(
                identityIdBase58 = identityIdBase58,
                nameStatus = nameStatus,
                nameFailureReason = nameFailure,
                secondaryNameStatus = secondaryResult?.first,
                secondaryNameFailureReason = secondaryResult?.second
            )
        )
    }

    /**
     * The INVITATION-CLAIM counterpart of [createUsernameFromShielded]: put a
     * new identity on chain funded from a shielded (L2) invitation's one-time
     * Orchard key [oneTimeSkHex] (lowercase hex of the 32-byte scalar carried
     * by the link), instead of the wallet's own pool. Unlike the pool path
     * this does NOT preflight the wallet's shielded balance — the funds come
     * from the invite note, which the FFI transiently scans for from
     * [fundingHeight] (advisory hint; null = no hint).
     *
     * DENOMINATION — the identity gets as much of the invite's value as the
     * protocol allows (product decision): [fundingCredits] is the note value
     * the link carries (`amt`,
     * [de.schildbach.wallet.data.InvitationLinkData.shieldedFundingCredits]),
     * and the claim requests the LARGEST allowed exit denomination that note
     * covers. A legacy 0.3 contested invite claimed with a non-contested name
     * therefore exits 0.25 (0.3 is not a member of the allowed
     * exit-denomination set — see [SHIELDED_IDENTITY_DENOMINATIONS_CREDITS])
     * instead of the 0.03 username minimum; the 0.05 remainder returns to the
     * CLAIMER's own Orchard change address as spendable shielded value, not to
     * the identity. When the note value is unreadable (legacy links minted
     * before `amt` existed, or a value that is not a real invite note value)
     * the claim tries the denominations DESCENDING from 0.25, retrying only on
     * the specific fail-closed "no note covers this denomination" refusal
     * (nothing is spent by a refused attempt; see
     * [inviteClaimDenominationLadder] and [isNoteBelowDenominationFailure]).
     * [label] is the username the claimer will register; its tier sets the
     * FLOOR of that ladder (a contested name needs at least the 0.25
     * denomination — same mapping the pool path uses), never the amount.
     *
     * On success returns [SdkWriteResult.Broadcast] with the new identity id
     * (base58) — the legacy claim tail then recovers it by its slot-0 public
     * key and registers the DPNS name + contact-request. A SECOND claim of the
     * same invite fails at broadcast because the note's nullifier is already
     * published; that (and the L1-parity "outpoint already exists") is mapped
     * to [SdkWriteResult.NotBroadcast] with [REASON_INVITE_ALREADY_USED] so the
     * caller can surface the existing "invite already used" state. Registering
     * the DPNS name and sending the contact request are the CALLER's job (the
     * legacy path already owns those steps) — this method only creates the
     * identity.
     */
    suspend fun createIdentityFromInvitation(
        oneTimeSkHex: String,
        fundingHeight: Int?,
        label: String,
        fundingCredits: Long? = null
    ): SdkWriteResult<String> {
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")
        val name = label.trim()
        if (name.isEmpty()) return notBroadcast("empty username", null)

        val oneTimeSk = try {
            hexToBytes32(oneTimeSkHex)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("malformed one-time key", t)
        }

        val contested = try {
            Names.isUsernameContestable(name)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("contested-ness check failed", t)
        }
        val fee = try {
            feeCredits(contested)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("username fee unavailable", t)
        }
        val minimumCredits = chooseShieldedIdentityDenominationCredits(fee)
            ?: return notBroadcast("no shielded denomination covers the username fee", null)
        // The denominations to attempt, LARGEST first — the identity gets the
        // invite's full value, with the username tier only setting the floor
        // (see the method KDoc and inviteClaimDenominationLadder).
        val denominationLadder = inviteClaimDenominationLadder(minimumCredits, fundingCredits)
        if (denominationLadder.isEmpty()) {
            return notBroadcast("no shielded invite denomination covers the username fee", null)
        }

        // The runtime must be up (viewing-key derivation + the transient note
        // scan run on it), but we do NOT gate on the wallet's own pool balance
        // — the note funding this claim belongs to the one-time key.
        if (!shieldedBalanceService.ensureShieldedReady()) {
            return notBroadcast(REASON_RUNTIME_NOT_READY, null)
        }

        val walletId = try {
            source.boundWalletIdOrNull()
                ?: return notBroadcast("app wallet not bound to the SDK", null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("SDK bootstrap/bind lookup failed", t)
        }

        // Same key derivation + persist rule as createUsernameFromShielded (and
        // the transparent path): previewRegistrationKeySet returns the private
        // scalars, which must be stored for the FFI signer BEFORE the claim
        // create — the invitation-claim FFI (createIdentityFromOneTimeKey) signs
        // with the KeystoreSigner, which resolves identity keys by lookup, not
        // derivation, so an unstored key throws SigningKeyUnavailable.
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

        // MANDATORY KEY PERSIST before the claim create (see
        // createUsernameFromShielded). Store each preview row's own scalar,
        // then zero the in-memory copy.
        try {
            keys.forEach { key ->
                source.storeIdentityPrivateKey(walletId, key.publicKeyHex, key.privateKey)
                key.privateKey.fill(0)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("registration key persist failed", t)
        }

        val fallbackAddress = try {
            source.fallbackPlatformAddressOrNull(walletId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("fallback platform address lookup failed", t)
        } ?: return notBroadcast("no platform receive address in the SDK store yet", null)
        val fallbackRaw21 = decodePlatformAddressRaw21(fallbackAddress, displayHrpSafe())
            ?: return notBroadcast("malformed fallback platform address", null)

        val changeAddressRaw43 = try {
            source.ownDefaultOrchardAddressRaw43(walletId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("own default orchard address lookup failed", t)
        } ?: return notBroadcast("no bound shielded sub-wallet for the claimer yet", null)

        // THE claim spend — each attempt is a ~30s Halo 2 proof. Walk the
        // denomination ladder LARGEST first. A "note does not cover this
        // denomination" refusal is fail-closed — the FFI's transient scan +
        // note selection run strictly BEFORE proof generation or broadcast,
        // nothing is spent, no reservation is held (the note belongs to the
        // foreign one-time key, not to any subwallet), and the invite stays
        // unused — so stepping down and retrying is safe. ONLY that refusal
        // descends: an already-used invite, any other pre-broadcast
        // rejection, and every unprovable outcome terminate exactly as a
        // single attempt would (Ambiguous is NEVER retried — the identity
        // may already be on chain and a retry could double-spend the note's
        // idempotence handling).
        var claimedIdentityId: ByteArray? = null
        var spentDenominationCredits = 0L
        var ladder = denominationLadder
        var attempt = 0
        // The SDK's exit-denomination set is mirrored, not queried, so a protocol
        // revision of it can only be observed from the refusal itself. Re-derive
        // the ladder ONCE from the set the SDK quoted (fail-closed: the refusal
        // is pre-broadcast, nothing was spent) so a divergence costs one attempt
        // rather than every claim. Once only — a second divergence would mean the
        // parse is wrong, and looping on it must not be possible.
        var divergenceHandled = false
        while (attempt < ladder.size) {
            val denominationCredits = ladder[attempt]
            try {
                claimedIdentityId = source.createIdentityFromOneTimeKey(
                    walletIdHex = walletId,
                    oneTimeSk = oneTimeSk,
                    changeAddressRaw43 = changeAddressRaw43,
                    identityIndex = identityIndex,
                    keys = keys,
                    denominationCredits = denominationCredits,
                    fallbackAddress21 = fallbackRaw21,
                    fundingBirthHeight = fundingHeight
                )
                spentDenominationCredits = denominationCredits
                break
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // TERMINAL, checked BEFORE the ladder's descend arm: an
                // already-claimed invite (typed ShieldedInviteAlreadyClaimed,
                // native code 37) means the note is consumed on chain, so no
                // smaller denomination can ever spend it. Descending would only
                // burn another ~30 s transient scan to reach the identical
                // refusal.
                if (isInviteAlreadyUsedFailure(t)) {
                    log.warn("shielded invite claim rejected — the one-time note is already spent", t)
                    return SdkWriteResult.NotBroadcast(REASON_INVITE_ALREADY_USED, t)
                }
                val sdkAllowed = parseAllowedExitDenominations(t.message)
                if (!divergenceHandled && sdkAllowed != null &&
                    sdkAllowed != SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toList()
                ) {
                    divergenceHandled = true
                    log.error(
                        "exit-denomination set DIVERGED from the SDK's — ours {}, SDK {}; " +
                            "re-deriving the claim ladder from the SDK's set",
                        SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.toList(),
                        sdkAllowed
                    )
                    val correctedMinimum = chooseShieldedIdentityDenominationCredits(fee, sdkAllowed)
                    val corrected = correctedMinimum?.let {
                        inviteClaimDenominationLadder(it, fundingCredits, sdkAllowed)
                    }.orEmpty()
                    if (corrected.isNotEmpty()) {
                        ladder = corrected
                        attempt = 0
                        continue
                    }
                }
                if (attempt < ladder.lastIndex && isNoteBelowDenominationFailure(t)) {
                    log.info(
                        "no invite note covers the {} denomination ({}) — falling back to {}",
                        creditsToDash(denominationCredits).toPlainString(),
                        t.message,
                        creditsToDash(ladder[attempt + 1]).toPlainString()
                    )
                    attempt++
                    continue
                }
                return when (val classified = classifyBroadcastFailure(t)) {
                    is SdkWriteResult.NotBroadcast -> {
                        log.warn("shielded invite claim rejected pre-broadcast", t)
                        classified
                    }
                    else -> {
                        log.error(
                            "shielded invite claim outcome unconfirmed — the identity MAY be on chain; do NOT retry",
                            t
                        )
                        SdkWriteResult.Ambiguous(t)
                    }
                }
            }
        }
        val identityId = checkNotNull(claimedIdentityId) {
            "invite-claim ladder exited without an identity or a classified failure"
        }
        val identityIdBase58 = Identifier.from(identityId).toString()
        log.info(
            "shielded-invite-claimed identity created at index {} ({}…) — {} denomination, contested={}",
            identityIndex,
            identityIdBase58.take(8),
            creditsToDash(spentDenominationCredits).toPlainString(),
            contested
        )
        // OVERAGE → the identity (product decision): when the claimed note's
        // value provably exceeds the exit denomination (legacy 0.3 note →
        // 0.25 claim), the remainder landed in the claimer's own pool as the
        // claim's change note — persist a pending record BEFORE returning so
        // the follow-up top-up (ShieldedInviteOverageTopUp) survives an app
        // death here. Best-effort: the claim outcome is already final.
        inviteClaimOverageCredits(fundingCredits, spentDenominationCredits)?.let { overage ->
            try {
                recordInviteOverage(identityIdBase58, overage)
                log.info(
                    "invite overage of {} DASH recorded for identity {}… — a follow-up top-up moves it onto the identity",
                    creditsToDash(overage).toPlainString(),
                    identityIdBase58.take(8)
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.error("failed to persist the invite overage record — the {} DASH overage stays in the claimer's shielded balance", creditsToDash(overage).toPlainString(), t)
            }
        }
        return SdkWriteResult.Broadcast(identityIdBase58)
    }

    /**
     * One state-tracked DPNS registration for an identity that already exists
     * on chain. Drives the creation state around `source.registerDpnsName`
     * ([preorderState] → [registeringState], then [registeredState] on success)
     * so the home tile reflects the step, and on failure LEAVES the state at
     * [registeringState] — stamped with the error for a provably pre-broadcast
     * rejection (retryable error card), but WITHOUT a hard error for an
     * AMBIGUOUS (unconfirmed) outcome (self-healing: the restore worker
     * re-checks the on-chain name before re-registering, so it never
     * double-registers/re-funds). A failure demotes the name's status, never
     * the creation result; the returned Pair<status, reason> is unchanged from
     * the previous best-effort helper so the outcome reporting is identical.
     */
    private suspend fun registerNameTracked(
        walletId: String,
        identityId: ByteArray,
        label: String,
        preorderState: IdentityCreationState,
        registeringState: IdentityCreationState,
        registeredState: IdentityCreationState
    ): Pair<ShieldedUsernameNameStatus, String?> {
        try {
            driveCreationState(preorderState, null)
            driveCreationState(registeringState, null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("failed to record DPNS registering state for '{}'", label, t)
        }
        return try {
            source.registerDpnsName(walletId, identityId, label)
            try {
                driveCreationState(registeredState, null)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                log.warn("failed to record DPNS registered state for '{}'", label, t)
            }
            ShieldedUsernameNameStatus.REGISTERED to null
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val (status, reason) = when (classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("DPNS registration of '{}' rejected pre-broadcast after identity creation", label, t)
                    ShieldedUsernameNameStatus.NOT_REGISTERED to (t.message ?: "name registration failed")
                }
                else -> {
                    log.error("DPNS registration of '{}' outcome unconfirmed after identity creation", label, t)
                    ShieldedUsernameNameStatus.AMBIGUOUS to (t.message ?: "name registration ambiguous")
                }
            }
            // Leave the creation state at the registering state. A provably
            // pre-broadcast rejection (NOT_REGISTERED) is a GENUINE, terminal
            // failure for this attempt — stamp the error so the home tile shows
            // the retryable error card. An AMBIGUOUS (unconfirmed) outcome is
            // the self-healing case (the restore worker re-checks the on-chain
            // name before re-registering, never a double-register/re-fund), so
            // DON'T stamp a hard error — keep the sticky non-terminal
            // "unconfirmed, will reconcile on sync" registering state. The
            // ambiguous reason is still returned in the outcome for logging.
            val errorForTile = if (status == ShieldedUsernameNameStatus.AMBIGUOUS) null else reason
            try {
                driveCreationState(registeringState, errorForTile)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                log.warn("failed to stamp DPNS registration state for '{}'", label, e)
            }
            status to reason
        }
    }

    private fun notBroadcast(reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("shielded username creation not attempted ({})", reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    /** [displayHrp] with failures contained (a throw must not escape a preflight). */
    private fun displayHrpSafe(): String = try {
        displayHrp()
    } catch (e: Exception) {
        "" // matches no valid address HRP → rejected as malformed
    }

    private suspend fun isEnabled(): Boolean = try {
        dashPayConfig.get(DashPayConfig.USE_KOTLIN_SDK_SHIELDED) == true
    } catch (e: Exception) {
        log.warn("failed to read USE_KOTLIN_SDK_SHIELDED; treating as off", e)
        false
    }

    companion object {
        private val log = LoggerFactory.getLogger(SdkShieldedUsernameCreation::class.java)

        /**
         * The two preflight refusal reasons that mean "the shielded pool is
         * not ready YET" (as opposed to a genuine error): the runtime has
         * not finished bringing up, or the pool is still syncing so its
         * balance is a mid-sync placeholder. Both are transient and
         * retry-safe — nothing was spent — so the UI surfaces a calm "still
         * preparing, try again in a moment" message instead of the red
         * network-error dialog. These are the [SdkWriteResult.NotBroadcast]
         * / [ShieldedUsernameSubmitState.NotSent] reasons emitted at the
         * two preflights above; classify with [isPoolNotReadyReason]
         * rather than string-matching at the call site.
         */
        const val REASON_RUNTIME_NOT_READY = "shielded runtime not ready"
        const val REASON_POOL_STILL_SYNCING = "shielded pool still syncing"

        /**
         * Preflight refusal meaning the pool sync is READY but the wallet's
         * funding note is NOT yet anchored (no recorded commitment-tree
         * anchor / `blockHeight`), so a Type-20 create would bounce with
         * `ShieldedNoRecordedAnchor`. Transient and retry-safe (nothing
         * spent) — grouped with the pool-not-ready reasons so the UI keeps
         * the calm "still preparing" surface until the note anchors.
         */
        const val REASON_FUNDING_NOTE_UNCONFIRMED = "shielded funding note not yet anchored"

        /**
         * [createIdentityFromInvitation] refusal meaning the shielded invite
         * was ALREADY CLAIMED: the one-time note's nullifier is already
         * published (or, at L1 parity, its outpoint already exists), so this
         * second claim spent nothing. The caller maps it to the existing
         * "invite already used" surface. Distinguished from the transient
         * pool-not-ready reasons by [isInviteAlreadyUsedReason].
         */
        const val REASON_INVITE_ALREADY_USED = "shielded invite already used"

        /**
         * Whether a [createIdentityFromInvitation] [SdkWriteResult.NotBroadcast]
         * reason is the already-claimed case ([REASON_INVITE_ALREADY_USED]).
         */
        fun isInviteAlreadyUsedReason(reason: String): Boolean =
            reason == REASON_INVITE_ALREADY_USED

        /**
         * The Display prefix rs-platform-wallet stamps on EVERY
         * `PlatformWalletError::ShieldedInviteAlreadyClaimed` ("Shielded
         * invitation already claimed: its note is spent on chain but this
         * wallet cannot prove that this claim created an identity ({reason});
         * the invitation cannot be claimed again"). Reason-independent, so it
         * matches all four raise sites — unlike the per-reason substrings
         * below, which only match the ones whose `reason` happens to quote a
         * nullifier. Used ONLY by the pre-typed-error fallback in
         * [isInviteAlreadyUsedFailure].
         */
        private const val ALREADY_CLAIMED_DISPLAY_MARKER = "invitation already claimed"

        /**
         * Whether a native claim-spend failure is the double-claim case — the
         * invite note is already spent, or (L1 asset-lock parity) its outpoint
         * already exists on chain.
         *
         * AUTHORITATIVE PATH: the typed
         * [DashSdkError.PlatformWallet.ShieldedInviteAlreadyClaimed]
         * (`ErrorShieldedInviteAlreadyClaimed`, native code 37, SDK
         * 0.1.0-v41int13+). The SDK documents it as TERMINAL and not
         * retryable: the note is consumed, so no retry — and no smaller
         * denomination — can spend it again.
         *
         * The string arm is a FALLBACK for AARs older than v41int13, which
         * surfaced the same outcome as an untyped wallet-operation error. It
         * leads with the reason-independent Display prefix
         * ([ALREADY_CLAIMED_DISPLAY_MARKER]) because the per-reason
         * substrings below miss the raise site whose `reason` carries a plain
         * result-wait error instead of a nullifier quote (rs-platform-wallet
         * `operations.rs` "broadcast accepted but result confirmation
         * failed" arm) — that one used to fall through to
         * [classifyBroadcastFailure] and surface as an AMBIGUOUS "outcome
         * unconfirmed" instead of the terminal "invite already used". The
         * remaining substrings still cover the L1/Drive re-spend shapes.
         */
        internal fun isInviteAlreadyUsedFailure(t: Throwable): Boolean {
            if (t is DashSdkError.PlatformWallet.ShieldedInviteAlreadyClaimed) return true
            val m = t.message ?: return false
            return m.contains(ALREADY_CLAIMED_DISPLAY_MARKER, ignoreCase = true) ||
                m.contains("nullifier", ignoreCase = true) ||
                m.contains("already spent", ignoreCase = true) ||
                m.contains("already exists", ignoreCase = true) ||
                m.contains("OutPointAlreadyExists", ignoreCase = true) ||
                m.contains("outpoint already", ignoreCase = true) ||
                m.contains("double spend", ignoreCase = true)
        }

        /**
         * Whether an [SdkWriteResult] carried out of
         * [createIdentityFromInvitation] is the terminal already-claimed
         * outcome — the app-owned [REASON_INVITE_ALREADY_USED] reason OR (belt
         * and braces, in case a future classification change routes the
         * throwable past [isInviteAlreadyUsedFailure]) a carried cause that is
         * the TYPED already-claimed error. The claim call site uses this
         * instead of matching the reason alone, so a code-37 failure can never
         * surface as an ambiguous "outcome unconfirmed".
         *
         * Deliberately typed-only for the carried cause: the string fallback in
         * [isInviteAlreadyUsedFailure] is scoped to the claim-spend catch,
         * where every throwable came from the claim itself. Applying it to an
         * arbitrary result cause would let an unrelated message ("Wallet
         * already exists", …) promote a pre-broadcast or genuinely ambiguous
         * outcome into a terminal "invite already used".
         */
        fun isInviteAlreadyUsedOutcome(result: SdkWriteResult<*>): Boolean = when (result) {
            is SdkWriteResult.NotBroadcast ->
                isInviteAlreadyUsedReason(result.reason) ||
                    result.cause is DashSdkError.PlatformWallet.ShieldedInviteAlreadyClaimed
            is SdkWriteResult.Ambiguous ->
                result.cause is DashSdkError.PlatformWallet.ShieldedInviteAlreadyClaimed
            is SdkWriteResult.Broadcast -> false
        }

        /**
         * Whether a native claim-spend failure means the one-time key's
         * note(s) exist but do NOT cover the requested denomination — the
         * ONLY refusal the invite-claim denomination ladder
         * ([inviteClaimDenominationLadder]) may fall back down on.
         *
         * This is rs-platform-wallet's typed
         * `ShieldedInsufficientBalance { available, required }`, raised by
         * `select_notes` during note SELECTION — strictly before the Halo 2
         * proof or any broadcast, with no reservation held (the notes belong
         * to the foreign one-time key, not to a subwallet) — so nothing was
         * spent and a smaller-denomination retry is safe. The FFI surfaces it
         * as an `ErrorWalletOperation` whose message embeds the error's
         * Display text ("Insufficient shielded balance: available N,
         * required M"); message-matched until the SDK exposes it typed.
         *
         * Deliberately does NOT match `ShieldedNoUnspentNotes` ("No unspent
         * shielded notes available"): that means the key owns nothing the
         * scan can see AT ALL (never funded, or the wallet's platform sync
         * hasn't reached the note yet) — a smaller denomination re-scans the
         * same tree and finds the same nothing, so descending would only
         * burn two more full transient scans to reach the identical
         * terminal refusal.
         */
        internal fun isNoteBelowDenominationFailure(t: Throwable): Boolean =
            t.message?.contains("Insufficient shielded balance") == true

        /** Decode a 64-char lowercase-hex 32-byte scalar; throws on bad input. */
        internal fun hexToBytes32(hex: String): ByteArray {
            val clean = hex.trim()
            require(clean.length == 64) { "expected 64 hex chars, got ${clean.length}" }
            return ByteArray(32) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /**
         * Whether a [ShieldedUsernameSubmitState.NotSent]/
         * [SdkWriteResult.NotBroadcast] reason is one of the transient
         * pool-not-ready cases ([REASON_RUNTIME_NOT_READY] /
         * [REASON_POOL_STILL_SYNCING]) — the caller should show the calm
         * "still preparing" surface, not a hard error.
         */
        fun isPoolNotReadyReason(reason: String): Boolean =
            reason == REASON_RUNTIME_NOT_READY ||
                reason == REASON_POOL_STILL_SYNCING ||
                reason == REASON_FUNDING_NOTE_UNCONFIRMED
    }
}
