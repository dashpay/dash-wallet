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
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashj.platform.dpp.identifier.Identifier
import org.dashj.platform.sdk.platform.Names
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Pure mapping helpers ──────────────────────────────────────────────

/**
 * The fixed Type-20 exit denominations, in Platform credits — the only
 * amounts `shielded_identity_create_from_pool` can spend from the pool
 * (0.1 / 0.3 / 0.5 / 1.0 DASH; 1 DASH = 1e11 credits). Ascending.
 */
internal val SHIELDED_IDENTITY_DENOMINATIONS_CREDITS = longArrayOf(
    10_000_000_000L, // 0.1 DASH
    30_000_000_000L, // 0.3 DASH
    50_000_000_000L, // 0.5 DASH
    100_000_000_000L // 1.0 DASH
)

/**
 * Fee → denomination mapping: the SMALLEST fixed Type-20 denomination
 * that covers [feeCredits], or null when the fee is non-positive or
 * exceeds the largest denomination. The metered creation fee is taken
 * from the denomination Rust-side and the change returns to the pool, so
 * "covers" is a simple ≥ — no extra margin is required.
 *
 * Concretely, for today's fees:
 * - non-contested username, `Constants.DASH_PAY_FEE` = 0.03 DASH
 *   (3e9 credits) → 0.1 DASH (1e10 credits), the smallest denomination.
 * - contested username, `Constants.DASH_PAY_FEE_CONTESTED` = 0.25 DASH
 *   (2.5e10 credits) → 0.3 DASH (3e10 credits). The identity is created
 *   holding `denomination − metered fee` in credits, and a contested DPNS
 *   registration needs ~0.2 DASH of those credits for the prefunded
 *   voting balance Drive attaches to every contestable label — the 0.1
 *   denomination cannot cover it, 0.3 can.
 */
internal fun chooseShieldedIdentityDenominationCredits(feeCredits: Long): Long? {
    if (feeCredits <= 0) return null
    return SHIELDED_IDENTITY_DENOMINATIONS_CREDITS.firstOrNull { it >= feeCredits }
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
 * the raw 21-byte PlatformAddress payload (type byte + 20-byte hash) the
 * Type-20 `fallbackAddress` FFI parameter wants. Null when the input is
 * not a well-formed Platform address under [hrp] (wrong HRP = wrong
 * network, wrong payload length, or an unknown type byte). Type bytes per
 * the SDK's own `decodePlatformAddress`: `0xb0` P2PKH, `0x80` P2SH.
 */
internal fun decodePlatformAddressRaw21(address: String, hrp: String): ByteArray? {
    val decoded = Bech32m.decode(address.trim().lowercase()) ?: return null
    if (decoded.hrp != hrp) return null
    val data = decoded.data
    if (data.size != 21) return null
    return when (data[0].toInt() and 0xFF) {
        0xb0, 0x80 -> data
        else -> null
    }
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
     * 1 CRITICAL/AUTH, 2 HIGH/AUTH, 3 TRANSFER/CRITICAL) for
     * [identityIndex]. Pure compute — no Platform RPCs, nothing persisted.
     */
    suspend fun previewRegistrationKeySet(walletIdHex: String, identityIndex: Int): List<IdentityKeyPreview>

    /**
     * Derive + persist the private key of the registration key at
     * `(identityIndex, keyIndex)` into the SDK's Keystore-backed store,
     * keyed by [publicKey]'s hex — the precondition for the FFI signer
     * (both the Type-20 create and the DPNS registration sign with these
     * keys). Throws on failure.
     */
    suspend fun persistRegistrationKey(
        walletIdHex: String,
        publicKey: ByteArray,
        identityIndex: Int,
        keyIndex: Int
    )

    /**
     * The wallet's own DIP-17 Platform receive address (bech32m) for the
     * REQUIRED creation-failure fallback, or null when the SDK's address
     * store has no row for the wallet yet. Same selection as
     * [ShieldedSource.ownPlatformAddressOrNull]: lowest unused index,
     * falling back to the lowest-index row of any state.
     */
    suspend fun fallbackPlatformAddressOrNull(walletIdHex: String): String?

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
            identityIndex = identityIndex
        )
    }

    override suspend fun persistRegistrationKey(
        walletIdHex: String,
        publicKey: ByteArray,
        identityIndex: Int,
        keyIndex: Int
    ) {
        // repairIdentityKey re-derives the scalar Rust-side and encrypts it
        // into the SDK's Keystore-backed WalletStorage under the pubkey hex
        // — exactly the persist the registration FFIs require, without this
        // module ever touching the private material the preview rows carry.
        val recorded = manager().repairIdentityKey(
            walletId = walletId(walletIdHex),
            publicKeyData = publicKey,
            identityIndex = identityIndex,
            keyIndex = keyIndex
        )
        checkNotNull(recorded) { "registration key persist returned no storage identifier" }
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

    override suspend fun createIdentityFromPool(
        walletIdHex: String,
        identityIndex: Int,
        keys: List<IdentityKeyPreview>,
        denominationCredits: Long,
        fallbackAddress21: ByteArray
    ): ByteArray = manager().shieldedIdentityCreateFromPool(
        walletId = walletId(walletIdHex),
        identityIndex = identityIndex,
        keys = keys,
        denomination = denominationCredits,
        fallbackAddress = fallbackAddress21
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
 * IS on chain (the pool was spent); [nameStatus] qualifies the username.
 */
data class ShieldedUsernameCreationOutcome(
    val identityIdBase58: String,
    val nameStatus: ShieldedUsernameNameStatus,
    val nameFailureReason: String? = null
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
    /** Scope for [submit]; null (tests' default path) makes submit inert. */
    private val executorScope: CoroutineScope? = null
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        dashPayConfig: DashPayConfig,
        shieldedBalanceService: ShieldedBalanceService,
        walletApplication: WalletApplication,
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
            RestoreIdentityOperation(walletApplication).create(identityId).enqueue()
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
    fun submit(username: String): Boolean {
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
            val result = withContext(ioDispatcher) { createUsernameFromShielded(username) }
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
    suspend fun createUsernameFromShielded(username: String): SdkWriteResult<ShieldedUsernameCreationOutcome> {
        if (!isEnabled()) return SdkWriteResult.NotBroadcast("flag off")
        val label = username.trim()
        if (label.isEmpty()) {
            return notBroadcast("empty username", null)
        }

        // Contested-ness is derived HERE from the label (same rule the
        // request screen gates on) so a caller can never pair a contested
        // name with the too-small non-contested denomination — the name
        // registration would fail its ~0.2 prefunded-voting-balance
        // debit after the identity was already created.
        val contested = try {
            Names.isUsernameContestable(label)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("contested-ness check failed", t)
        }

        // Fee → denomination (explicit mapping, see
        // chooseShieldedIdentityDenominationCredits: non-contested
        // 0.03 DASH → 0.1, contested 0.25 DASH → 0.3).
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
            return notBroadcast("shielded runtime not ready", null)
        }
        if (shieldedBalanceService.shieldedSyncStatus.value != ShieldedSyncStatus.READY) {
            // A mid-sync zero (or partial) balance is a placeholder, not
            // evidence — never spend against it.
            return notBroadcast("shielded pool still syncing", null)
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

        // Identity slot + canonical key set, derived and PERSISTED before
        // anything is signed (the FFI signer reads the Keystore store).
        val identityIndex: Int
        val keys: List<IdentityKeyPreview>
        try {
            identityIndex = source.managedIdentityCount(walletId)
            keys = source.previewRegistrationKeySet(walletId, identityIndex)
            check(keys.isNotEmpty()) { "empty registration key set" }
            keys.forEachIndexed { keyIndex, key ->
                source.persistRegistrationKey(walletId, key.publicKey, identityIndex, keyIndex)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast("registration key derivation/persist failed", t)
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

        // Best-effort DPNS name — the identity exists either way.
        val (nameStatus, nameFailure) = try {
            source.registerDpnsName(walletId, identityId, label)
            ShieldedUsernameNameStatus.REGISTERED to null
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            when (classifyBroadcastFailure(t)) {
                is SdkWriteResult.NotBroadcast -> {
                    log.warn("DPNS name registration rejected pre-broadcast after identity creation", t)
                    ShieldedUsernameNameStatus.NOT_REGISTERED to (t.message ?: "name registration failed")
                }
                else -> {
                    log.error("DPNS name registration outcome unconfirmed after identity creation", t)
                    ShieldedUsernameNameStatus.AMBIGUOUS to (t.message ?: "name registration ambiguous")
                }
            }
        }

        // Hand the on-chain identity to the legacy state machine (restore
        // path). Best-effort: a handoff failure must not demote a real
        // Broadcast — the identity/name are on chain; the restore also
        // re-runs from PlatformSyncService's preBlockDownload discovery.
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
            ShieldedUsernameCreationOutcome(identityIdBase58, nameStatus, nameFailure)
        )
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
    }
}
