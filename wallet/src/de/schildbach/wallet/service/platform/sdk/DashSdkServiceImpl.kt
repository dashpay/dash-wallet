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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.DevNetParams
import org.dashfoundation.dashsdk.Network
import org.dashfoundation.dashsdk.Sdk
import org.dashfoundation.dashsdk.config.SdkConfig
import org.dashfoundation.dashsdk.persistence.DashDatabase
import org.dashfoundation.dashsdk.security.KeySecurityPolicy
import org.dashfoundation.dashsdk.security.WalletStorage
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager
import org.dashfoundation.dashsdk.wallet.WalletManagerStore
import org.slf4j.LoggerFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Map the app's flavor-selected dashj network onto the SDK's [Network].
 *
 * The wallet's network is fixed at build time
 * ([de.schildbach.wallet.Constants.NETWORK_PARAMETERS]: prod = mainnet,
 * `_testNet3`/staging = testnet, devnet flavor = devnet), so unlike the SDK
 * example app there is no runtime network switching — but the SDK's
 * rebuild-not-reconfigure rule still applies if that ever changes.
 *
 * Kept as a pure function (no native, no Android) so it is unit-testable
 * on the host JVM.
 */
internal fun toSdkNetwork(parameters: NetworkParameters): Network = when {
    parameters.id == NetworkParameters.ID_MAINNET -> Network.MAINNET
    parameters.id == NetworkParameters.ID_TESTNET -> Network.TESTNET
    parameters is DevNetParams -> Network.DEVNET
    parameters.id == NetworkParameters.ID_REGTEST -> Network.REGTEST
    else -> throw IllegalArgumentException("No SDK network mapping for ${parameters.id}")
}

// ── bindAppWallet helpers ─────────────────────────────────────────────
//
// Pure (no native, no Android, no I/O) so the idempotency/mapping logic of
// [DashSdkServiceImpl.bindAppWallet] is unit-testable on the host JVM —
// the native `createWallet` call itself cannot run there.

/**
 * Join provider-supplied BIP39 words into the single-space phrase the
 * SDK's `createWallet` expects. Rejects empty lists and words containing
 * whitespace (a caller passing a whole phrase as one "word"). Error
 * messages deliberately never include the offending word.
 */
internal fun joinMnemonicWords(words: List<String>): String {
    require(words.isNotEmpty()) { "seedWords is empty" }
    val cleaned = words.map { it.trim() }
    cleaned.forEachIndexed { index, word ->
        require(word.isNotEmpty() && word.none { it.isWhitespace() }) {
            "malformed seed word at index $index"
        }
    }
    return cleaned.joinToString(" ")
}

/** Whitespace-normalize a phrase for comparison (never for storage). */
internal fun normalizeMnemonic(phrase: String): String =
    phrase.trim().split(WHITESPACE).joinToString(" ")

private val WHITESPACE = Regex("\\s+")

/**
 * The `birthHeight` to hand the SDK's `createWallet` when importing the
 * app's EXISTING seed.
 *
 * The SDK takes a block height (`null` = SPV tip for brand-new wallets,
 * `0u` = full scan from genesis for imports); the app only knows a birth
 * *time* (`Wallet.getEarliestKeyCreationTime`). Phase 5a closes the gap
 * Phase 3b left open: [resolveHeight] maps time → a SAFE height via the
 * app's dashj checkpoint files ([BirthHeightResolver] — checkpoint
 * at-or-before the birth time minus a ~1-week margin), and anything
 * unresolvable (null time, resolver failure) stays at the conservative
 * `0u` full scan. Guessing too high silently hides funds; 0 is merely
 * slower.
 *
 * NOTE: this only affects FUTURE first-time binds. A wallet already bound
 * with `birthHeight = 0` keeps that stored height — re-binding dedups on
 * the persisted mnemonic and never re-runs `createWallet` (see
 * [BirthHeightResolver]'s "Already-bound wallets" note).
 */
internal fun sdkBirthHeightFor(birthTimeSecs: Long?, resolveHeight: (Long) -> UInt): UInt =
    birthTimeSecs?.takeIf { it > 0 }?.let { time ->
        try {
            resolveHeight(time)
        } catch (e: Exception) {
            0u // resolver contract is never-throw, but the bind must not die on a mapping bug
        }
    } ?: 0u

/** Lowercase-hex decode of a 32-byte SDK wallet id; null if malformed. */
internal fun walletIdFromHex(hex: String): ByteArray? {
    if (hex.length != 64) return null
    val out = ByteArray(32)
    for (i in out.indices) {
        val hi = Character.digit(hex[2 * i], 16)
        val lo = Character.digit(hex[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * The message prefix of the SDK's `createWallet` rejection when the
 * derived wallet id is already registered
 * (`DashSdkError…Generic("Wallet already exists: <hex id>")`). Hit live
 * when the mnemonic-dedup read misses on a transient Keystore failure —
 * `bindAppWallet` recovers the id from the message instead of failing the
 * bind (SDK issue #11: createWallet is not idempotent and offers no
 * lookup-by-mnemonic).
 */
internal const val WALLET_ALREADY_EXISTS_MESSAGE = "Wallet already exists"

private val WALLET_ALREADY_EXISTS_ID = Regex(
    Regex.escape(WALLET_ALREADY_EXISTS_MESSAGE) + """:\s*([0-9a-fA-F]{64})(?![0-9a-fA-F])"""
)

/**
 * Extract the 64-hex wallet id from a [WALLET_ALREADY_EXISTS_MESSAGE]
 * error message, lowercased, or null when the message is not that error
 * or carries no well-formed id. Pure — host-testable.
 */
internal fun walletIdFromAlreadyExistsError(message: String?): String? {
    message ?: return null
    val hex = WALLET_ALREADY_EXISTS_ID.find(message)?.groupValues?.get(1)?.lowercase()
        ?: return null
    return hex.takeIf { walletIdFromHex(it) != null }
}

/**
 * The already-bound wallet id for [mnemonic] among [loadedWalletIdsHex],
 * or null if none matches — the dedup step that makes
 * [DashSdkServiceImpl.bindAppWallet] idempotent. [storedMnemonicFor]
 * resolves a loaded id to its phrase in the SDK's `WalletStorage`
 * (null = watch-only wallet with no stored phrase, or a lookup failure —
 * treated as "no match", falling through to `createWallet`, whose own
 * `storeMnemonic` re-heals a missing phrase for the same derived id).
 */
internal suspend fun findBoundWalletId(
    loadedWalletIdsHex: Collection<String>,
    mnemonic: String,
    storedMnemonicFor: suspend (String) -> String?
): String? {
    val candidate = normalizeMnemonic(mnemonic)
    for (idHex in loadedWalletIdsHex) {
        val stored = storedMnemonicFor(idHex) ?: continue
        if (normalizeMnemonic(stored) == candidate) return idHex
    }
    return null
}

/**
 * Ensure the phrase behind an ALREADY-REGISTERED SDK wallet is persisted
 * in the SDK's `WalletStorage` — the missing half of the already-exists
 * recovery in [DashSdkServiceImpl.bindAppWallet].
 *
 * Live incident: a bind pass ran while the device was locked/dozing and
 * died AFTER `createWallet` registered the wallet but BEFORE its phrase
 * landed in `WalletStorage`. Every later bind then hit the already-exists
 * recovery, which returned the wallet id WITHOUT the phrase — so identity
 * discovery failed permanently with "mnemonic resolver: no mnemonic
 * stored for the supplied wallet_id" until a manual wallet re-create.
 *
 * Probe-then-store: [hasMnemonic] is existence-only (a DataStore key
 * check, no Keystore), so the healthy case costs one read; a probe
 * failure falls through to the store, which is naturally idempotent
 * (same phrase, same entry). [storeMnemonic] needs the Keystore and is
 * allowed to throw (device still locked) — the caller fails the bind
 * pass retryable so the NEXT trigger (screen-on) heals it.
 *
 * @return true when the missing phrase was re-stored, false when it was
 *   already present. Pure wiring (collaborators injected) — host-testable.
 */
internal suspend fun ensureRecoveredMnemonicStored(
    walletIdHex: String,
    mnemonic: String,
    hasMnemonic: suspend (ByteArray) -> Boolean,
    storeMnemonic: suspend (ByteArray, String) -> Unit
): Boolean {
    val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
    val present = try {
        hasMnemonic(walletId)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false // probe failure → attempt the (idempotent) store anyway
    }
    if (present) return false
    storeMnemonic(walletId, mnemonic)
    return true
}

// ── ensureIdentityKeysSignable helpers ────────────────────────────────
//
// Pure (no native, no Android, no I/O of their own) so the per-key
// heal/verify/store decision table is unit-testable on the host JVM —
// the collaborators (Room DAO reads, the FFI derive, Keystore-backed
// storage) are passed in as suspend lambdas.

/** One persisted identity public-key row, reduced to what healing needs. */
internal data class IdentityKeyCandidate(
    val keyId: Int,
    val publicKeyData: ByteArray,
    val readOnly: Boolean = false,
    val disabled: Boolean = false
) {
    val publicKeyHex: String get() = publicKeyData.joinToString("") { "%02x".format(it) }
}

/**
 * Walk an identity's persisted public keys and make each one signable if
 * the wallet's seed can reproduce it — the [DashSdkService
 * .ensureIdentityKeysSignable] decision table (see that KDoc for the
 * why). Per key:
 *
 * 1. already stored ([hasPrivateKey]) → healthy, untouched (idempotency);
 * 2. read-only / disabled → watch-only (permanently not signable);
 * 3. derive the canonical keypair at the key's slot ([deriveKeyPair],
 *    `(private, public)` — the resolver-keyed Rust derive), VERIFY the
 *    public half byte-equals the on-chain key, then [storePrivateKey]
 *    the scalar keyed by the pubkey hex. A mismatch → watch-only WITHOUT
 *    storing (never poison the signer's store with a wrong scalar); a
 *    derive/store throw → failed (transient, retryable).
 *
 * The derived private scalar is zero-filled on every exit path. Nothing
 * key-derived is logged.
 */
internal suspend fun healIdentityKeys(
    candidates: List<IdentityKeyCandidate>,
    hasPrivateKey: suspend (pubkeyHex: String) -> Boolean,
    deriveKeyPair: suspend (keyIndex: Int) -> Pair<ByteArray, ByteArray>,
    storePrivateKey: suspend (pubkeyHex: String, privateKey: ByteArray) -> Unit,
    onKeyOutcome: (keyId: Int, outcome: String) -> Unit = { _, _ -> }
): IdentityKeyHealReport {
    var healthy = 0
    var repaired = 0
    var watchOnly = 0
    var failed = 0
    for (candidate in candidates) {
        try {
            if (hasPrivateKey(candidate.publicKeyHex)) {
                healthy++
                continue
            }
            if (candidate.readOnly || candidate.disabled) {
                watchOnly++
                onKeyOutcome(candidate.keyId, "watch-only (read-only/disabled row)")
                continue
            }
            val (privateKey, publicKey) = deriveKeyPair(candidate.keyId)
            try {
                if (!publicKey.contentEquals(candidate.publicKeyData)) {
                    // The load-bearing guard (mirrors Rust discovery's
                    // validate_private_key_bytes check): this seed does not
                    // own the key — storing the scalar anyway would make the
                    // signer produce protocol-invalid signatures.
                    watchOnly++
                    onKeyOutcome(candidate.keyId, "watch-only (slot derive does not reproduce key)")
                    continue
                }
                storePrivateKey(candidate.publicKeyHex, privateKey)
                repaired++
                onKeyOutcome(candidate.keyId, "repaired")
            } finally {
                privateKey.fill(0)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed++
            onKeyOutcome(candidate.keyId, "failed (${e.javaClass.simpleName})")
        }
    }
    return IdentityKeyHealReport(
        keysChecked = candidates.size,
        healthy = healthy,
        repaired = repaired,
        watchOnly = watchOnly,
        failed = failed
    )
}

/**
 * Default [DashSdkService] implementation — the Phase 3 bootstrap scaffold
 * (`docs/kotlin-sdk-migration-plan.md`).
 *
 * ## Laziness contract (load-bearing)
 *
 * Construction touches NOTHING: no native library, no Room, no Keystore,
 * no `Constants`. Every SDK object lives inside the [SdkRuntime] built by
 * [ensureStarted]; until that is explicitly called this singleton is an
 * inert holder, so Hilt can instantiate it (even eagerly) without changing
 * app behavior or loading `libdash_sdk`. This is verified by a plain-JVM
 * unit test (`DashSdkServiceImplTest`) where constructing the class must
 * not throw `UnsatisfiedLinkError`.
 *
 * The SDK's own classes are also safe to *reference* statically: the only
 * `System.loadLibrary` call sits inside `NativeLoader.ensureLoaded()`,
 * which runs first via `Sdk.initialize()` in [bootstrap] — never from a
 * static initializer.
 *
 * ## Bootstrap order
 *
 * [bootstrap] follows the SDK example app's `AppContainer` faithfully:
 * the container-construction step (database / walletStorage /
 * walletManagerStore) followed by `bootstrap()` (`Sdk.initialize` →
 * logging → per-network `Sdk.create`) and `activateManager()`
 * (`WalletManagerStore.activate` → `loadPersistedWallets`). The example's
 * remaining steps are intentionally deferred:
 *
 * - `loadKnownContractsIntoSdk` — no contracts are persisted yet (3b),
 * - sync-service binding (`platformBalanceSyncService.configure`,
 *   `startPlatformAddressSync` / `startShieldedSync` /
 *   `startDashPaySync`) — Phase 3b/4,
 * - network-switch observer — the app's network is flavor-fixed.
 *
 * @param mnemonicProvider the Phase 3b seed bridge
 *   ([SecurityGuardMnemonicProvider] in production). Not called by this
 *   service — [bindAppWallet] takes already-decrypted words so the
 *   PIN/biometric prompt stays with the caller — but held here so Phase 3c
 *   call sites can reach both halves of the bridge through one injection
 *   point.
 */
@Singleton
class DashSdkServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("unused") private val mnemonicProvider: PlatformMnemonicProvider
) : DashSdkService {

    /** Everything with a native or persistence footprint lives here. */
    private class SdkRuntime(
        val database: DashDatabase,
        val walletStorage: WalletStorage,
        val walletManagerStore: WalletManagerStore,
        val sdk: Sdk,
        val walletManager: PlatformWalletManager
    )

    private val lock = Mutex()

    /** Serializes [bindAppWallet]'s dedup-then-create critical section. */
    private val bindLock = Mutex()

    @Volatile
    private var runtime: SdkRuntime? = null

    override val isStarted: Boolean
        get() = runtime != null

    override fun sdkOrNull(): Sdk? = runtime?.sdk

    override fun databaseOrNull(): DashDatabase? = runtime?.database

    override fun walletManagerOrNull(): PlatformWalletManager? = runtime?.walletManager

    override fun loadedWalletIds(): Set<String> =
        walletManagerOrNull()?.wallets?.value?.keys?.toSet() ?: emptySet()

    override suspend fun ensureStarted() {
        lock.withLock {
            if (runtime == null) {
                runtime = bootstrap()
            }
        }
    }

    override suspend fun stop() {
        lock.withLock {
            val current = runtime ?: return
            runtime = null
            log.info("stopping Dash Platform SDK")
            // Managers first (each closes its native bundle + resolver/signer
            // children), then the SDK handle they were built against, then
            // the database — reverse of the bootstrap order.
            runCatching { current.walletManagerStore.closeAll() }
                .onFailure { log.warn("failed to close wallet managers", it) }
            runCatching { current.sdk.close() }
                .onFailure { log.warn("failed to close SDK handle", it) }
            runCatching { current.database.close() }
                .onFailure { log.warn("failed to close SDK database", it) }
        }
    }

    override suspend fun resolveUsername(name: String): String? {
        ensureStarted()
        val sdk = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }.sdk
        return sdk.dpns.resolve(name)
    }

    /**
     * See [DashSdkService.bindAppWallet] for the contract. Serialized under
     * [bindLock] so two concurrent binds of the same phrase can't both miss
     * the dedup check and double-create.
     *
     * The phrase inevitably exists as a [String] here — the SDK's
     * `createWallet(mnemonic: String)` and `WalletStorage.retrieveMnemonic`
     * both traffic in strings — so there is nothing to scrub; it is never
     * logged and no reference outlives this call.
     */
    override suspend fun bindAppWallet(seedWords: List<String>, birthTimeSecs: Long?): String {
        val mnemonic = joinMnemonicWords(seedWords)
        ensureStarted()
        val current = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }

        bindLock.withLock {
            // Idempotency: bootstrap already ran loadPersistedWallets(), so
            // every previously-bound wallet is in the manager's map with its
            // phrase in the SDK's Keystore-backed WalletStorage.
            val existing = findBoundWalletId(
                loadedWalletIdsHex = current.walletManager.wallets.value.keys,
                mnemonic = mnemonic
            ) { idHex ->
                val walletId = walletIdFromHex(idHex) ?: return@findBoundWalletId null
                // Lookup failures (Keystore hiccup) fall through to
                // createWallet rather than failing the bind.
                runCatching { current.walletStorage.retrieveMnemonic(walletId) }.getOrNull()
            }
            if (existing != null) {
                log.info("app wallet already bound to SDK wallet {}…", existing.take(8))
                return existing
            }

            // First bind: derive + register the SDK wallet. createWallet
            // itself persists the phrase into WalletStorage keyed by the
            // derived id (and rolls everything back on failure), after which
            // the manager's MnemonicResolverAndPersister serves derivations
            // without any further seed hand-off.
            val birthHeight = sdkBirthHeightFor(birthTimeSecs) { time ->
                // Phase 5a: dashj-checkpoint time→height mapping; the
                // resolver contains its own failures (→ 0u / genesis).
                BirthHeightResolver(
                    networkParameters = Constants.NETWORK_PARAMETERS,
                    openCheckpoints = { context.assets.open(Constants.Files.CHECKPOINTS_FILENAME) }
                ).resolve(time)
            }
            val managed = try {
                current.walletManager.createWallet(
                    mnemonic = mnemonic,
                    name = APP_WALLET_NAME,
                    createDefaultAccounts = true,
                    birthHeight = birthHeight
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Live bug: a transient Keystore failure makes the dedup
                // read above miss, and createWallet (same seed → same
                // derived id) rejects with "Wallet already exists: <id>".
                // The wallet IS bound — recover the id from the message,
                // verify it is actually loaded, and bind to it.
                val existingId = walletIdFromAlreadyExistsError(t.message)
                if (existingId != null && current.walletManager.wallets.value.containsKey(existingId)) {
                    log.warn(
                        "createWallet says the wallet already exists ({}…) though the mnemonic " +
                            "dedup read missed (transient Keystore failure?) — binding to the " +
                            "loaded SDK wallet",
                        existingId.take(8)
                    )
                    // Live incident: an earlier bind died AFTER createWallet
                    // registered the wallet but BEFORE the phrase persist, so
                    // "already exists" does NOT prove the mnemonic is stored —
                    // returning the id without it wedges identity discovery
                    // permanently ("no mnemonic stored for the supplied
                    // wallet_id"). Verify/re-store before binding to it.
                    val restored = try {
                        ensureRecoveredMnemonicStored(
                            walletIdHex = existingId,
                            mnemonic = mnemonic,
                            hasMnemonic = { current.walletStorage.hasMnemonic(it) },
                            storeMnemonic = { id, phrase ->
                                current.walletStorage.storeMnemonic(id, phrase)
                            }
                        )
                    } catch (storeFailure: Throwable) {
                        if (storeFailure is kotlinx.coroutines.CancellationException) throw storeFailure
                        // Storing needs the Keystore; a locked device fails here.
                        // Fail the pass (retryable, never latched) instead of
                        // binding without a phrase — the next bind trigger
                        // (screen-on) re-runs this recovery and heals it.
                        log.warn(
                            "mnemonic re-store deferred: keystore unavailable; will retry on " +
                                "the next bind trigger",
                            storeFailure
                        )
                        throw storeFailure
                    }
                    if (restored) {
                        log.warn(
                            "re-stored the missing mnemonic for SDK wallet {}… (an earlier bind " +
                                "registered the wallet but failed before persisting its phrase)",
                            existingId.take(8)
                        )
                    }
                    return existingId
                }
                throw t
            }
            log.info(
                "app wallet bound to new SDK wallet {}… (birthHeight={} via checkpoint mapping; " +
                    "0 = full scan from genesis)",
                managed.walletIdHex.take(8), birthHeight
            )
            return managed.walletIdHex
        }
    }

    /**
     * See [DashSdkService.removeAppWallet] for the cascade contract.
     * Serialized under [bindLock] so a removal can never interleave with
     * a concurrent [bindAppWallet]'s dedup-then-create critical section
     * (a bind racing the removal either finishes first against the doomed
     * wallet — harmless — or runs after and re-creates cleanly).
     */
    override suspend fun removeAppWallet(walletIdHex: String) {
        ensureStarted()
        val current = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        bindLock.withLock {
            if (!current.walletManager.wallets.value.containsKey(walletIdHex)) {
                log.warn(
                    "removeAppWallet skipped: SDK wallet {}… is not loaded (already removed?)",
                    walletIdHex.take(8)
                )
                return
            }
            log.warn(
                "removing SDK wallet {}… — full persistence cascade (Room wallet/identity/TXO/" +
                    "address/shielded rows, Keystore-backed identity keys and mnemonic, native " +
                    "wallet handle); dashj state is untouched and the next bind re-creates the " +
                    "same deterministic wallet id from the app seed",
                walletIdHex.take(8)
            )
            current.walletManager.removeWallet(walletId)
            log.info("SDK wallet {}… removed", walletIdHex.take(8))
        }
    }

    override suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean {
        ensureStarted()
        val current = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }
        val wallet = current.walletManager.wallets.value[walletIdHex] ?: return false
        // syncState is a local managed-identity snapshot read. The FFI throws
        // "ManagedIdentity not found" for identities this wallet doesn't manage
        // (observed live on-device; SDK issue: should be null / typed NotFound),
        // so that error means "not managed" — anything else is a real failure.
        return try {
            wallet.dashpay.syncState(identityId) != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.message?.contains("ManagedIdentity not found") == true) {
                false
            } else {
                throw e
            }
        }
    }

    override suspend fun discoverIdentities(walletIdHex: String, startIndex: Int): List<ByteArray> {
        ensureStarted()
        val current = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }
        val manager = current.walletManager
        val wallet = checkNotNull(manager.wallets.value[walletIdHex]) { "SDK wallet not loaded" }
        // Gap-limit walk over the DIP-9 identity-authentication tree
        // (Rust default gap limit). The app wallet was created from its
        // mnemonic (resident private keys), so the FFI derives in-process
        // and never consults the resolver — the handle is passed for
        // signature completeness only. Discovered identities are folded
        // into Rust's IdentityManager and persisted via the Room bridge.
        val found = manager.identityRegistration.discoverIdentities(
            walletHandle = wallet.handle,
            mnemonicResolverHandle = manager.mnemonicResolverHandle,
            startIndex = startIndex
        )
        log.info(
            "identity discovery on SDK wallet {}… (startIndex={}): {} newly-discovered identity(ies)",
            walletIdHex.take(8), startIndex, found.size
        )
        return found
    }

    /**
     * See [DashSdkService.ensureIdentityKeysSignable] for the full
     * contract. Wiring: key rows from the SDK's Room `PublicKeyDao`
     * (keyed by the identity's base58 id — the persistence bridge's row
     * key), identity slot from `IdentityDao.identityIndex` (0 for the
     * dashj-registered app identity), derive via
     * [PlatformWalletManager.deriveIdentityKeyPair] (the resolver-keyed
     * FFI `IdentityNative.deriveIdentityKeyPairWithResolver`, returning
     * `(private, public)`), store via `WalletStorage.storePrivateKey` —
     * the same call the SDK's own `repairIdentityKey` /
     * `IdentityKeyPrivateKeyDeriver` lands on, with the derived-pubkey
     * verification they omit.
     */
    override suspend fun ensureIdentityKeysSignable(
        walletIdHex: String,
        identityId: ByteArray
    ): IdentityKeyHealReport {
        ensureStarted()
        val current = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }
        val manager = current.walletManager
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }

        // The dashj-registered app identity lives at identity index 0; a
        // missing Room row (persist race) falls back to that same slot.
        val identityIndex = current.database.identityDao()
            .getByIdentityId(identityId)?.identityIndex ?: 0

        // PublicKeyEntity rows are keyed by the identity's base58 id
        // (bitcoin/bs58 alphabet — same encoding as dashj's Identifier).
        val identityBase58 = org.bitcoinj.core.Base58.encode(identityId)
        val rows = current.database.publicKeyDao()
            .observeByIdentityId(identityBase58)
            .first()

        val report = healIdentityKeys(
            candidates = rows.map {
                IdentityKeyCandidate(
                    keyId = it.keyId,
                    publicKeyData = it.publicKeyData,
                    readOnly = it.readOnly,
                    disabled = it.disabledAt != null
                )
            },
            hasPrivateKey = { pubkeyHex -> current.walletStorage.hasPrivateKey(pubkeyHex) },
            deriveKeyPair = { keyIndex ->
                manager.deriveIdentityKeyPair(walletId, identityIndex, keyIndex)
            },
            storePrivateKey = { pubkeyHex, privateKey ->
                current.walletStorage.storePrivateKey(pubkeyHex, privateKey)
            },
            onKeyOutcome = { keyId, outcome ->
                log.info(
                    "identity {}… key #{} (slot {}/{}): {}",
                    identityBase58.take(8), keyId, identityIndex, keyId, outcome
                )
            }
        )
        log.info(
            "identity key heal for {}… on wallet {}…: {} checked, {} healthy, " +
                "{} repaired, {} watch-only, {} failed",
            identityBase58.take(8), walletIdHex.take(8), report.keysChecked,
            report.healthy, report.repaired, report.watchOnly, report.failed
        )
        return report
    }

    /**
     * See [DashSdkService.storeIdentityPrivateKey] for the full contract.
     * Delegates to `WalletStorage.storePrivateKey(pubkeyHex, privateKey,
     * ownerWalletId)` — the SAME single [WalletStorage] instance the FFI
     * [org.dashfoundation.dashsdk.security.KeystoreSigner] resolves signing
     * keys from (wired to both the signer and the SDK runtime at bootstrap,
     * see [bootstrap]), and the same primitive [ensureIdentityKeysSignable]'s
     * heal pass lands on. Passing [walletId] as the owner records the alias
     * in the durable owner index so an in-flight registration's prestored
     * keys are cleaned up on wallet deletion.
     */
    override suspend fun storeIdentityPrivateKey(
        pubkeyHex: String,
        privateKey: ByteArray,
        walletId: ByteArray
    ) {
        ensureStarted()
        val current = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }
        current.walletStorage.storePrivateKey(pubkeyHex, privateKey, walletId)
    }

    /**
     * See [DashSdkService.provisionDashPayContactAccounts] for the full
     * contract. Wiring: resolve the bound [ManagedPlatformWallet] from the
     * manager's live map, run one DashPay sweep
     * ([PlatformWalletManager.dashPaySyncNow] — fetch both contact-request
     * directions, enqueue the receiving/external account builds, reconcile,
     * and lower `synced_height` for registered receival accounts), then —
     * only when the sweep left builds queued — schedule the Keystore-signer
     * drain that actually registers the accounts
     * ([PlatformWalletManager.unlockWalletFromKeystore], a background drain).
     */
    override suspend fun provisionDashPayContactAccounts(
        walletIdHex: String
    ): DashPayContactProvisionReport {
        ensureStarted()
        val current = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }
        val manager = current.walletManager
        val managed = manager.wallets.value[walletIdHex]
        if (managed == null) {
            // The bind hasn't completed (or the wallet was removed): nothing
            // to provision. Not an error — the binder retries on later triggers.
            log.debug("DashPay contact provisioning skipped: SDK wallet {}… not loaded", walletIdHex.take(8))
            return DashPayContactProvisionReport(
                bound = false, syncSuccess = 0, syncErrors = 0, pendingBefore = 0, drainScheduled = false
            )
        }
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }

        // 1. One DashPay sweep: received + sent contact requests for every
        //    managed identity → enqueue RegisterReceiving/RegisterExternal
        //    pending-crypto, reconcile incoming payments, and lower
        //    synced_height for already-registered receival accounts (#846).
        val summary = manager.dashPaySyncNow()

        // 2. Drain only when the sweep queued account builds. The drain
        //    (verify seed → background register) derives our friendship xpub
        //    via the Keystore signer and registers the DashpayReceivingFunds
        //    (ours) + DashpayExternalAccount (watch-only) accounts, whose
        //    addresses then enter the SPV monitored/filter set. A locked
        //    device / seed-verify failure leaves the queue for the next pass.
        val drain = drainContactCryptoQueue(managed, walletId, walletIdHex)

        log.info(
            "DashPay contact provisioning on SDK wallet {}…: sweep success={} errors={}, " +
                "pendingBuilds={}, drainScheduled={}",
            walletIdHex.take(8), summary.success, summary.errors, drain.queuedBefore, drain.drainScheduled
        )
        return DashPayContactProvisionReport(
            bound = true,
            syncSuccess = summary.success,
            syncErrors = summary.errors,
            pendingBefore = drain.queuedBefore,
            drainScheduled = drain.drainScheduled
        )
    }

    /**
     * See [DashSdkService.drainDashPayContactAccountBuilds]. Deliberately does
     * NOT sweep: the sweep is what rewinds the SPV synced height, and this
     * exists precisely so the queue can be drained on launches where the
     * backfill gate skips the sweep.
     */
    override suspend fun drainDashPayContactAccountBuilds(
        walletIdHex: String
    ): DashPayContactDrainReport {
        ensureStarted()
        val current = checkNotNull(runtime) { "SDK runtime missing after ensureStarted()" }
        val managed = current.walletManager.wallets.value[walletIdHex]
            ?: return DashPayContactDrainReport(
                bound = false, queuedBefore = 0, drainScheduled = false, queuedAfter = 0
            )
        val walletId = requireNotNull(walletIdFromHex(walletIdHex)) { "malformed SDK wallet id" }
        return drainContactCryptoQueue(managed, walletId, walletIdHex)
    }

    override suspend fun dashPayPendingAccountBuilds(walletIdHex: String): Int? = try {
        ensureStarted()
        val manager = runtime?.walletManager
        val walletId = walletIdFromHex(walletIdHex)
        if (manager == null || walletId == null) {
            null
        } else {
            manager.contactCryptoPendingCount(walletId)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug("contact-crypto pending count unavailable: {}", e.message)
        null
    }

    /**
     * Schedule the signer-backed drain (only when something is queued) and
     * watch the queue drop for a bounded window, so the caller can state how
     * many builds were queued, built and left rather than only that a drain
     * was scheduled. The drain itself is asynchronous and single-flight
     * Rust-side; polling never drives it, it only observes.
     *
     * Never throws: a seed-verify / Keystore failure leaves the queue intact
     * for the next pass, which is a normal state on a locked device.
     */
    private suspend fun drainContactCryptoQueue(
        managed: org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet,
        walletId: ByteArray,
        walletIdHex: String
    ): DashPayContactDrainReport {
        val manager = checkNotNull(runtime) { "SDK runtime missing" }.walletManager
        val queuedBefore = manager.contactCryptoPendingCount(walletId)
        if (queuedBefore == 0) {
            return DashPayContactDrainReport(
                bound = true, queuedBefore = 0, drainScheduled = false, queuedAfter = 0
            )
        }
        val drainScheduled = try {
            manager.unlockWalletFromKeystore(managed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Non-fatal: the next signer-present pass retries the drain;
            // the sweep rebuilds the queue if it was lost.
            log.warn(
                "DashPay contact-crypto drain deferred on SDK wallet {}… " +
                    "(seed verify / Keystore unavailable): {}",
                walletIdHex.take(8), e.message
            )
            false
        }
        var queuedAfter = queuedBefore
        if (drainScheduled) {
            repeat(DRAIN_OBSERVE_POLLS) {
                kotlinx.coroutines.delay(DRAIN_OBSERVE_INTERVAL_MS)
                queuedAfter = try {
                    manager.contactCryptoPendingCount(walletId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.debug("contact-crypto pending re-count failed: {}", e.message)
                    return@repeat
                }
                if (queuedAfter == 0) return@repeat
            }
        }
        val report = DashPayContactDrainReport(
            bound = true,
            queuedBefore = queuedBefore,
            drainScheduled = drainScheduled,
            queuedAfter = queuedAfter
        )
        log.info(
            "DashPay account-build drain on SDK wallet {}…: queued={} built={} stillQueued={} " +
                "(blocked or still draining), drainScheduled={}",
            walletIdHex.take(8), report.queuedBefore, report.built, report.queuedAfter,
            report.drainScheduled
        )
        return report
    }

    /**
     * See [DashSdkService.readDashPayBackfillSignals]. Pure Room reads via
     * [databaseOrNull] — no [ensureStarted], no native call, no sweep, so a
     * gate consultation can never itself provoke the very rescan it is
     * trying to suppress. Every failure degrades to
     * [DashPayBackfillSignals.UNKNOWN] ("re-run the backfill").
     */
    override suspend fun readDashPayBackfillSignals(
        walletIdHex: String,
        ownerIdentityId: ByteArray
    ): DashPayBackfillSignals {
        return try {
            val database = databaseOrNull() ?: return DashPayBackfillSignals.UNKNOWN
            val walletId = walletIdFromHex(walletIdHex) ?: return DashPayBackfillSignals.UNKNOWN

            // The durable filter-scan watermark — the value the DIP-15
            // backfill lowers and the scan then climbs back up.
            val syncedHeight = database.walletDao().getByWalletId(walletId)?.syncedHeight?.toLong()

            // Diagnostic floor: the lowest contact core height the SDK itself
            // has persisted for us. Never load-bearing (see the KDoc).
            val contactRequests = database.dashpayDao().getContactRequestsByOwner(ownerIdentityId)
            val floor = contactRequests.minOfOrNull { it.coreHeightCreatedAt.toLong() }

            DashPayBackfillSignals(
                syncedHeight = syncedHeight,
                contactCoreHeightFloor = floor,
                contactRequestCount = contactRequests.size
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "failed to read DashPay backfill signals for SDK wallet {}…; " +
                    "treating as unknown (the backfill will be re-run)",
                walletIdHex.take(8), e
            )
            DashPayBackfillSignals.UNKNOWN
        }
    }

    /**
     * One-shot bring-up; caller holds [lock]. On any failure every
     * partially-created resource is torn down and the exception rethrown,
     * leaving the service stopped (retryable).
     */
    private suspend fun bootstrap(): SdkRuntime {
        val network = toSdkNetwork(Constants.NETWORK_PARAMETERS)
        log.info(
            "bootstrapping Dash Platform SDK for network={} (flavor={})",
            network.networkName, BuildConfig.FLAVOR
        )

        // 1. One-time native library load + dash_sdk_init, then logging —
        //    ← AppContainer.bootstrap() steps 1–2. Idempotent.
        Sdk.initialize()
        // FILE logging FIRST — the Rust `tracing` global subscriber is
        // first-installer-wins, and enableLogging() installs a STDOUT
        // subscriber that Android discards while blocking every later one.
        // Routing tracing to a file is what makes the SDK's sync/scan/drain
        // diagnostics reportable from a remote tester's device (the
        // `log::`-facade lines already reach logcat + NativeLogBridge; the
        // `tracing::` lines — the drain warns and the scan progress — went
        // nowhere). INFO, not DEBUG: the file accumulates across days on a
        // mainnet device, and INFO captures warn+info, which includes the
        // per-entry drain failures (tracing::warn) and the scan lines.
        enableSdkFileLogging()
        // Kept for logcat/local debugging: a no-op when the file subscriber
        // installed above won the global slot, and the fallback when it
        // could not (unwritable dir).
        Sdk.enableLogging(if (BuildConfig.DEBUG) Sdk.LogLevel.DEBUG else Sdk.LogLevel.WARN)

        // 2. Storage layer — ← AppContainer construction (database,
        //    walletStorage, walletManagerStore fields). DEVICE_BOUND key
        //    policy (#4060): this app gates wallet access with its own PIN
        //    (SecurityGuard), so the SDK's default AUTH_GATED alias only
        //    added a second, unwired auth layer — identity-key signing died
        //    with "User not authenticated" outside the ~30 s post-unlock
        //    window (observed live: contact-accept dead ends, mid-operation
        //    signing failures). DEVICE_BOUND keys stay hardware-backed and
        //    non-exportable but never throw UserNotAuthenticatedException.
        //    Keys previously wrapped under the AUTH_GATED alias surface as
        //    unhealthy and are re-derived + re-wrapped by the binder's
        //    key-heal pass (repairIdentityKey) — no user action needed.
        var database: DashDatabase? = null
        var sdk: Sdk? = null
        try {
            database = DashDatabase.create(context)
            val walletStorage = WalletStorage(context, KeySecurityPolicy.DEVICE_BOUND)
            val walletManagerStore = WalletManagerStore(database, walletStorage)

            // 3. Per-network SDK build — ← AppState.initializeSdk called from
            //    bootstrap(). Mainnet/testnet need no overrides; devnet would
            //    need a quorum URL (unsupported by this scaffold).
            sdk = Sdk.create(SdkConfig(network = network))

            // 4. Activate the network-locked manager, then restore any
            //    persisted SDK wallets — ← AppContainer.activateManager()
            //    (activate + loadPersistedWallets). None exist in Phase 3,
            //    so this is a fast no-op restore; sync-service binding is
            //    deliberately omitted until Phase 3b/4.
            val walletManager = walletManagerStore.activate(network, sdk)
            val restored = walletManager.loadPersistedWallets()
            log.info(
                "Dash Platform SDK started: version={}, restored {} wallet(s)",
                Sdk.version(), restored.size
            )

            return SdkRuntime(database, walletStorage, walletManagerStore, sdk, walletManager)
        } catch (t: Throwable) {
            log.error("Dash Platform SDK bootstrap failed", t)
            runCatching { sdk?.close() }
            runCatching { database?.close() }
            throw t
        }
    }

    /**
     * Route the SDK's `tracing` output to
     * `files/sdk-logs/platform_wallet/run.log`, with an app-side
     * session-start rotation ([Sdk.enableFileLogging] exposes only a level
     * and a root directory — no size guard of its own): a run.log over
     * [SDK_RUN_LOG_ROTATE_BYTES] is renamed to `run.log.1` (replacing any
     * previous one) before the subscriber opens it, bounding total disk to
     * about twice the cap. Best-effort — a failure here must never block
     * SDK bring-up, and the console subscriber below remains the fallback.
     */
    private fun enableSdkFileLogging() {
        try {
            val sessionRoot = File(context.filesDir, SDK_LOG_DIR_NAME)
            sessionRoot.mkdirs()
            val runLog = File(File(sessionRoot, "platform_wallet"), "run.log")
            if (runLog.length() > SDK_RUN_LOG_ROTATE_BYTES) {
                val rotated = File(runLog.parentFile, "run.log.1")
                rotated.delete()
                if (runLog.renameTo(rotated)) {
                    log.info(
                        "rotated SDK run.log ({} bytes) to run.log.1", rotated.length()
                    )
                } else {
                    log.warn("could not rotate oversized SDK run.log; it will keep growing")
                }
            }
            val installed = Sdk.enableFileLogging(Sdk.LogLevel.INFO, sessionRoot.absolutePath)
            log.info(
                "SDK tracing file logging (INFO) at {}: {}",
                runLog,
                if (installed) "installed" else "NOT installed (subscriber already set or dir unwritable)"
            )
        } catch (t: Throwable) {
            log.warn("failed to enable SDK tracing file logging; falling back to console", t)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(DashSdkServiceImpl::class.java)

        /**
         * Room-row display name stamped on the one SDK wallet bound from
         * the app's dashj seed (the SDK requires no name; this labels the
         * row for debugging/parity with the example app's named wallets).
         */
        internal const val APP_WALLET_NAME = "Dash Wallet"

        /**
         * Directory under `filesDir` handed to [Sdk.enableFileLogging] as
         * the session root; the SDK writes `platform_wallet/run.log`
         * beneath it. The support report tails this file — keep the name
         * in sync with `ContactSupportViewModel`.
         */
        const val SDK_LOG_DIR_NAME = "sdk-logs"

        /** Session-start rotation threshold for the SDK's run.log (20 MB). */
        const val SDK_RUN_LOG_ROTATE_BYTES = 20L * 1024 * 1024

        /**
         * How long a drain pass watches the contact-crypto queue before
         * reporting. Long enough for a small queue to finish (each build is a
         * key derivation plus a register), short enough that the caller — a
         * background provisioning pass — is never held up: the report is
         * observability, and whatever is still queued is picked up next pass.
         */
        private const val DRAIN_OBSERVE_POLLS = 10
        private const val DRAIN_OBSERVE_INTERVAL_MS = 1_000L
    }
}
