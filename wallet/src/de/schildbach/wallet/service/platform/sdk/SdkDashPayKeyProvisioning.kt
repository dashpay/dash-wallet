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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dashfoundation.dashsdk.identity.IdentityKeyPreview
import org.dashfoundation.dashsdk.identity.IdentityPubkey
import org.dashfoundation.dashsdk.identity.KeyPurpose
import org.dashfoundation.dashsdk.identity.KeyType
import org.dashfoundation.dashsdk.identity.RegistrationKeys
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

// ── Detection model ───────────────────────────────────────────────────

/**
 * One persisted identity public-key row of the SDK's Room `public_keys`
 * table, reduced to what the DashPay key retrofit needs.
 *
 * [purposeRaw] / [keyTypeRaw] are the DECIMAL STRINGS the SDK's persistence
 * bridge writes (`PlatformWalletPersistenceHandler`:1190 stores
 * `(purpose and 0xFF).toString()`), i.e. the DPP discriminants of
 * [KeyPurpose] / [KeyType] — never the enum names.
 */
internal data class PersistedIdentityKey(
    val keyId: Int,
    val purposeRaw: String,
    val keyTypeRaw: String,
    val disabled: Boolean,
    val publicKeyData: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is PersistedIdentityKey &&
            keyId == other.keyId &&
            purposeRaw == other.purposeRaw &&
            keyTypeRaw == other.keyTypeRaw &&
            disabled == other.disabled &&
            publicKeyData.contentEquals(other.publicKeyData)

    override fun hashCode(): Int {
        var result = keyId
        result = 31 * result + purposeRaw.hashCode()
        result = 31 * result + keyTypeRaw.hashCode()
        result = 31 * result + disabled.hashCode()
        result = 31 * result + publicKeyData.contentHashCode()
        return result
    }
}

/**
 * The SDK's local view of ONE managed identity's key set: its DIP-9
 * [identityIndex] (the slot every one of its keys is derived under) plus the
 * persisted key rows.
 */
internal data class IdentityKeySnapshot(
    val identityIndex: Int,
    val keys: List<PersistedIdentityKey>
)

/**
 * Whether the identity can act as the DIP-15 ECDH root of a DashPay contact
 * request — i.e. whether Rust's `select_own_encryption_key`
 * (`rs-platform-wallet/.../contact_requests.rs:989`) will find a key.
 */
enum class DashPayKeyState {
    /** An enabled ECDSA_SECP256K1 ENCRYPTION key exists — nothing to do. */
    PRESENT,

    /**
     * No encryption key AND the identity carries EXACTLY the canonical base
     * key set (keyIds 0..3), so the DashPay pair can be safely appended at
     * keyIds 4/5 (see [SdkDashPayKeyProvisioning] for why the layout must be
     * exact).
     */
    MISSING,

    /**
     * Undecidable or unsupported: the SDK has not persisted this identity's
     * keys yet, or the on-chain key layout is not the canonical 0..3 set.
     * Never provisioned — the caller keeps its existing behaviour.
     */
    UNKNOWN
}

/**
 * The detection decision table — pure, so it is unit-testable on the host
 * JVM without native/Room.
 *
 * 1. no snapshot / no persisted rows → [DashPayKeyState.UNKNOWN]: the SDK
 *    has not (yet) materialized this identity's keys, so "no encryption key"
 *    cannot be distinguished from "not read yet". Never spend credits on a
 *    guess.
 * 2. an ENABLED ECDSA_SECP256K1 ENCRYPTION key → [DashPayKeyState.PRESENT].
 *    This is the exact predicate Rust's `select_own_encryption_key` applies
 *    (purpose ENCRYPTION, key type ECDSA_SECP256K1, `disabled_at` null), so
 *    PRESENT means the contact-request build will not raise "Identity has no
 *    enabled ECDSA_SECP256K1 encryption key".
 * 3. otherwise the key ids must be EXACTLY {0,1,2,3} to be
 *    [DashPayKeyState.MISSING]; anything else is [DashPayKeyState.UNKNOWN].
 *    See [SdkDashPayKeyProvisioning]'s "keyId IS the derivation index" rule.
 */
internal fun classifyDashPayKeyState(snapshot: IdentityKeySnapshot?): DashPayKeyState {
    val keys = snapshot?.keys.orEmpty()
    if (keys.isEmpty()) return DashPayKeyState.UNKNOWN
    val encryptionPurpose = KeyPurpose.ENCRYPTION.ffiValue.toString()
    val ecdsaType = KeyType.ECDSA_SECP256K1.ffiValue.toString()
    val hasEncryptionKey = keys.any {
        it.purposeRaw == encryptionPurpose && it.keyTypeRaw == ecdsaType && !it.disabled
    }
    if (hasEncryptionKey) return DashPayKeyState.PRESENT
    val expectedBaseIds = (0 until RegistrationKeys.BASE_KEY_COUNT).toSet()
    return if (keys.map { it.keyId }.toSet() == expectedBaseIds) {
        DashPayKeyState.MISSING
    } else {
        DashPayKeyState.UNKNOWN
    }
}

// ── Source seam ───────────────────────────────────────────────────────

/**
 * Seam over the Kotlin SDK surfaces the retrofit needs (SDK Room reads, the
 * DIP-9 key derive, Keystore-backed private-key storage and the
 * `IdentityUpdate` add-key transition), so the orchestration in
 * [SdkDashPayKeyProvisioning] is host-JVM unit-testable — the real calls need
 * `libdash_sdk`.
 */
internal interface DashPayKeyProvisionSource {
    /**
     * The SDK's local key view of [identityId] (32 bytes), or null when the
     * identity has no persisted rows / cannot be read. Local Room read, no
     * network.
     */
    suspend fun identityKeySnapshot(identityId: ByteArray): IdentityKeySnapshot?

    /**
     * Derive the canonical registration key SET for [identityIndex] — the
     * IDENTICAL call the fresh-registration paths make
     * ([TransparentUsernameSource.previewRegistrationKeySet] with
     * `includeDashPayKeys = true`), so row `i` is the DIP-9 key at
     * `m/9'/coin'/5'/0'/0'/identityIndex'/i'`. Pure compute — no RPCs,
     * nothing persisted.
     */
    suspend fun previewRegistrationKeySet(
        walletIdHex: String,
        identityIndex: Int,
        count: Int
    ): List<IdentityKeyPreview>

    /** Same contract as [TransparentUsernameSource.storeIdentityPrivateKey]. */
    suspend fun storeIdentityPrivateKey(walletIdHex: String, pubkeyHex: String, privateKey: ByteArray)

    /**
     * Submit an `IdentityUpdateTransition` adding [keys] to [identityId],
     * signed with the identity's MASTER key (keyId 0). Returns only on a
     * confirmed broadcast; throws otherwise.
     */
    suspend fun addIdentityKeys(walletIdHex: String, identityId: ByteArray, keys: List<IdentityPubkey>)
}

/** Production [DashPayKeyProvisionSource]: boots the SDK on demand. */
internal class DashSdkDashPayKeyProvisionSource(
    private val service: DashSdkService
) : DashPayKeyProvisionSource {

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

    override suspend fun identityKeySnapshot(identityId: ByteArray): IdentityKeySnapshot? {
        service.ensureStarted()
        val database = service.databaseOrNull() ?: return null
        // The dashj-registered app identity lives at DIP-9 identity index 0;
        // a missing row (persist race) means we cannot pin the slot, so the
        // caller must treat it as undecidable rather than assume 0.
        val identityIndex = database.identityDao().getByIdentityId(identityId)?.identityIndex ?: return null
        // PublicKeyEntity rows are keyed by the identity's base58 id — the
        // same encoding the persistence bridge and `ensureIdentityKeysSignable`
        // use.
        val identityBase58 = org.bitcoinj.core.Base58.encode(identityId)
        val rows = database.publicKeyDao().getByIdentityId(identityBase58)
        return IdentityKeySnapshot(
            identityIndex = identityIndex,
            keys = rows.map {
                PersistedIdentityKey(
                    keyId = it.keyId,
                    purposeRaw = it.purpose,
                    keyTypeRaw = it.keyType,
                    disabled = it.disabledAt != null,
                    publicKeyData = it.publicKeyData
                )
            }
        )
    }

    override suspend fun previewRegistrationKeySet(
        walletIdHex: String,
        identityIndex: Int,
        count: Int
    ): List<IdentityKeyPreview> {
        val manager = manager()
        return manager.identityRegistration.previewRegistrationKeySet(
            walletHandle = wallet(walletIdHex).handle,
            mnemonicResolverHandle = manager.mnemonicResolverHandle,
            identityIndex = identityIndex,
            count = count
        )
    }

    override suspend fun storeIdentityPrivateKey(
        walletIdHex: String,
        pubkeyHex: String,
        privateKey: ByteArray
    ) {
        service.storeIdentityPrivateKey(pubkeyHex, privateKey, walletId(walletIdHex))
    }

    override suspend fun addIdentityKeys(
        walletIdHex: String,
        identityId: ByteArray,
        keys: List<IdentityPubkey>
    ) {
        val manager = manager()
        manager.identityUpdates.update(
            walletHandle = wallet(walletIdHex).handle,
            identityId = identityId,
            addPublicKeys = keys,
            disablePublicKeyIds = emptyList(),
            signerHandle = manager.signerHandle
        )
    }
}

// ── Service ───────────────────────────────────────────────────────────

/**
 * RETROFIT the DashPay ENCRYPTION (keyId 4) + DECRYPTION (keyId 5) pair onto
 * an EXISTING Dash Platform identity, so its contact requests stop falling
 * back to dashj.
 *
 * ## The problem
 *
 * Every identity registered before the six-key registration change carries
 * only the base key set (keyIds 0..3). Rust's `select_own_encryption_key`
 * (`rs-platform-wallet/.../contact_requests.rs:989`) demands an ENABLED
 * ECDSA_SECP256K1 ENCRYPTION key as the DIP-15 ECDH root and throws
 * "Invalid identity data: Identity has no enabled ECDSA_SECP256K1 encryption
 * key" while BUILDING the contact request — strictly pre-broadcast, so
 * [classifyBroadcastFailure] classifies it [SdkWriteResult.NotBroadcast] and
 * [SdkDashPayWrites] falls back to dashj on every single contact request,
 * forever. Fresh registrations already commit the pair
 * ([SdkTransparentUsernameCreation]); an identity whose key set is already on
 * chain can only grow it with an `IdentityUpdateTransition`.
 *
 * ## The load-bearing invariant: keyId IS the DIP-9 key index
 *
 * The contact-request path does NOT look the ECDH scalar up by public key —
 * it RE-DERIVES it, at
 * `identity_auth_derivation_path(network, ECDSA, identity_index, key.id())`
 * (`contact_requests.rs:534`), i.e. `m/9'/coin'/5'/0'/0'/identity_index'/keyId'`.
 * The SDK's post-broadcast local apply stamps the same breadcrumb
 * (`network/update.rs:231`: `(wallet_id, identity_index, key.id())`).
 *
 * So a key added at on-chain keyId N is only usable if its private half is the
 * DIP-9 key at INDEX N of the same identity slot. Consequences, all enforced
 * below:
 *
 * - the private keys are derived with `previewRegistrationKeySet(identityIndex,
 *   count = RegistrationKeys.keyCount(includeDashPayKeys = true))` — the
 *   IDENTICAL derive the fresh-registration paths use (rows 0..5 = key indices
 *   0..5 at the same identity slot), and rows 4 / 5 are taken verbatim;
 * - the rows are stamped by [RegistrationKeys.buildRegistrationRows] (via the
 *   shared [registrationRowsFor]), which maps list index → keyId, so keyId 4
 *   ENCRYPTION / keyId 5 DECRYPTION, both bound to the DashPay
 *   `contactRequest` document type — byte-for-byte what a fresh registration
 *   commits;
 * - provisioning runs ONLY when the identity's key ids are EXACTLY {0,1,2,3}
 *   ([classifyDashPayKeyState]). On any other layout the next free key ids
 *   would not be 4/5, keyId would stop equalling the derivation index, and the
 *   added keys would be permanently unusable (and un-re-derivable after a
 *   restart). Those identities are left alone — [DashPayKeyState.UNKNOWN];
 * - before submitting, the derived public keys for slots 0..3 are VERIFIED to
 *   reproduce the identity's persisted on-chain keys. That proves this seed +
 *   identity index really is the slot the identity was registered from; a
 *   mismatch aborts WITHOUT submitting anything (the same guard
 *   [healIdentityKeys] applies before storing a scalar).
 *
 * ## Trigger, idempotency and cost
 *
 * Driven lazily from [SdkDashPayWrites]'s contact-request preflight — the one
 * write that needs the key — and therefore only when
 * `USE_KOTLIN_SDK_DASHPAY_WRITES` is on, the wallet is bound and the identity
 * is managed. It is FIRE-AND-FORGET: the triggering contact request is NOT
 * blocked; it takes the unchanged dashj fallback while the transition lands,
 * and the NEXT contact request routes through the SDK.
 *
 * Idempotency is guaranteed by the DETECTION itself, not by a flag: a
 * successful update makes the SDK persist the keyId 4/5 `public_keys` rows
 * (`network/update.rs:230` → `ManagedIdentity::add_key`), after which
 * [classifyDashPayKeyState] reports [DashPayKeyState.PRESENT] forever — across
 * process restarts, with no app-side bookkeeping to drift. A per-process memo
 * ([outcomes]) additionally short-circuits the Room read once satisfied, and a
 * [Mutex]-guarded in-flight set makes concurrent triggers single-flight.
 *
 * An `IdentityUpdateTransition` costs credits from the identity's own balance,
 * so failures must not spam:
 * - [SdkWriteResult.NotBroadcast] (provably pre-broadcast — validation,
 *   derive/store failure, insufficient credits) is retryable but capped at
 *   [MAX_ATTEMPTS] per process;
 * - [SdkWriteResult.Ambiguous] (the transition MAY have landed) is STICKY for
 *   the process — never re-submitted, because a duplicate keyId would be
 *   rejected on chain after paying the fee. The next process start re-reads
 *   the key rows, so a landed-but-unapplied update self-heals via detection
 *   once the SDK re-syncs the identity.
 * Once blocked, [state] reports [DashPayKeyState.UNKNOWN] so the write path
 * behaves exactly as it did before this class existed.
 */
@Singleton
class SdkDashPayKeyProvisioning internal constructor(
    private val source: DashPayKeyProvisionSource,
    /** Scope for [requestProvisioning]; null (tests' default) makes it inert. */
    private val executorScope: CoroutineScope?
) {
    @Inject
    constructor(
        sdkService: DashSdkService,
        applicationScope: CoroutineScope
    ) : this(
        source = DashSdkDashPayKeyProvisionSource(sdkService),
        executorScope = applicationScope
    )

    /** Test seam: the update transition blocks on network I/O — keep it off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Per-process retry/short-circuit memo, keyed by identity id hex. */
    private sealed class Memo {
        /** The DashPay pair is on chain — never look again this process. */
        object Satisfied : Memo()

        /** [count] retryable failures so far. */
        data class Retryable(val count: Int) : Memo()

        /** Terminal for this process (ambiguous outcome or attempts exhausted). */
        data class Blocked(val reason: String) : Memo()
    }

    private val lock = Mutex()
    private val outcomes = HashMap<String, Memo>()
    private val inFlight = HashSet<String>()

    /**
     * Whether [identityId] (32 bytes) can act as a DashPay ECDH root.
     * Cheap: a per-process memo hit, otherwise ONE local SDK Room read (no
     * network). Contained — any failure reads as [DashPayKeyState.UNKNOWN],
     * which leaves the caller's behaviour unchanged.
     */
    suspend fun state(walletIdHex: String, identityId: ByteArray): DashPayKeyState {
        val key = identityKey(identityId)
        when (lock.withLock { outcomes[key] }) {
            is Memo.Satisfied -> return DashPayKeyState.PRESENT
            is Memo.Blocked -> return DashPayKeyState.UNKNOWN
            else -> Unit
        }
        val classified = try {
            classifyDashPayKeyState(source.identityKeySnapshot(identityId))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            log.warn("DashPay key detection failed for identity {}…", key.take(8), t)
            return DashPayKeyState.UNKNOWN
        }
        if (classified == DashPayKeyState.PRESENT) {
            lock.withLock { outcomes[key] = Memo.Satisfied }
        }
        return classified
    }

    /**
     * Start (at most one) background provisioning run for [identityId] on the
     * application scope. Returns false — and does NOTHING — when there is no
     * scope, a run is already in flight, or the identity is memoized
     * Satisfied/Blocked. Never throws, never blocks the caller.
     */
    suspend fun requestProvisioning(walletIdHex: String, identityId: ByteArray): Boolean {
        val scope = executorScope ?: return false
        val key = identityKey(identityId)
        val start = lock.withLock {
            when (outcomes[key]) {
                is Memo.Satisfied, is Memo.Blocked -> false
                else -> if (inFlight.add(key)) true else false
            }
        }
        if (!start) return false
        scope.launch {
            try {
                val result = try {
                    withContext(ioDispatcher) { provision(walletIdHex, identityId) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // provision() contains its own failures; anything escaping
                    // is a wiring fault — count it, never crash the app scope.
                    SdkWriteResult.NotBroadcast("provisioning run failed", t)
                }
                recordOutcome(key, result)
            } finally {
                // NonCancellable: a cancelled scope must still release the
                // single-flight slot, or the identity is wedged for the process.
                withContext(NonCancellable) { lock.withLock { inFlight.remove(key) } }
            }
        }
        return true
    }

    private suspend fun recordOutcome(key: String, result: SdkWriteResult<Int>) {
        lock.withLock {
            outcomes[key] = when (result) {
                is SdkWriteResult.Broadcast -> Memo.Satisfied
                is SdkWriteResult.Ambiguous ->
                    Memo.Blocked("outcome unconfirmed — never re-submitted this process")
                is SdkWriteResult.NotBroadcast -> {
                    val attempts = ((outcomes[key] as? Memo.Retryable)?.count ?: 0) + 1
                    if (attempts >= MAX_ATTEMPTS) {
                        Memo.Blocked("gave up after $attempts attempt(s): ${result.reason}")
                    } else {
                        Memo.Retryable(attempts)
                    }
                }
            }
        }
    }

    /**
     * The provisioning pipeline for ONE identity — a single
     * `IdentityUpdateTransition` adding keyId 4 (ENCRYPTION) + keyId 5
     * (DECRYPTION). Re-runs detection first, so a stale caller decision can
     * never cause a duplicate submission. Returns the number of keys added on
     * success; the [SdkWriteResult] three-valued contract holds
     * ([SdkWriteResult.Ambiguous] is never retried by anyone).
     */
    suspend fun provision(walletIdHex: String, identityId: ByteArray): SdkWriteResult<Int> {
        val idHex = identityKey(identityId)

        val snapshot = try {
            source.identityKeySnapshot(identityId)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(idHex, "identity key snapshot read failed", t)
        }
        when (classifyDashPayKeyState(snapshot)) {
            // Already satisfied (a concurrent run landed, or the caller's
            // decision was stale). Reported as Broadcast(0) — zero keys added
            // — so the memo records SATISFIED rather than burning a retry.
            DashPayKeyState.PRESENT -> return SdkWriteResult.Broadcast(0)
            DashPayKeyState.UNKNOWN ->
                return notBroadcast(idHex, "identity key layout not eligible for the DashPay retrofit", null)
            DashPayKeyState.MISSING -> Unit
        }
        val existing = checkNotNull(snapshot)

        // The DIP-9 slot every one of this identity's keys lives under. The
        // new keys MUST come from the same slot at key indices 4/5 — see the
        // class KDoc's "keyId IS the derivation index" rule.
        val keys = try {
            source.previewRegistrationKeySet(
                walletIdHex = walletIdHex,
                identityIndex = existing.identityIndex,
                count = RegistrationKeys.keyCount(includeDashPayKeys = true)
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return notBroadcast(idHex, "DashPay key derivation failed", t)
        }

        // Everything past this point runs inside the scrub `finally` — the
        // previews carry raw private scalars.
        try {
            val expectedCount = RegistrationKeys.keyCount(includeDashPayKeys = true)
            if (keys.size != expectedCount) {
                return notBroadcast(
                    idHex,
                    "expected $expectedCount derived DashPay keys, got ${keys.size}",
                    null
                )
            }

            // PROOF-OF-SLOT GUARD. Re-derive the identity's EXISTING keys
            // (0..3) and require them to reproduce the on-chain public keys.
            // This is what makes the retrofit safe: it proves the wallet seed
            // + identityIndex really is the slot this identity was registered
            // from, so the keys we are about to commit at keyIds 4/5 will
            // re-derive at (identityIndex, 4) / (identityIndex, 5) for every
            // future contact request. A mismatch means we would put
            // permanently unusable keys on chain — abort without submitting.
            val mismatch = existing.keys.firstOrNull { row ->
                row.keyId < RegistrationKeys.BASE_KEY_COUNT &&
                    !keys[row.keyId].publicKey.contentEquals(row.publicKeyData)
            }
            if (mismatch != null) {
                return notBroadcast(
                    idHex,
                    "slot ${existing.identityIndex} does not reproduce existing key #${mismatch.keyId}; " +
                        "refusing to add un-derivable DashPay keys",
                    null
                )
            }

            // Canonical rows: index → keyId, DashPay contract bounds on 4/5.
            // Same single source of truth the fresh-registration paths use.
            val rows = registrationRowsFor(keys)
            val dashPayRows = rows.filter { it.keyId >= RegistrationKeys.BASE_KEY_COUNT }
            check(dashPayRows.map { it.keyId } == listOf(4, 5)) {
                "unexpected DashPay row key ids ${dashPayRows.map { it.keyId }}"
            }

            // MANDATORY KEY PERSIST before the transition — the FFI signer
            // resolves identity signing keys by LOOKUP (retrievePrivateKey by
            // pubkey hex), never by derivation, so an unstored key throws
            // SigningKeyUnavailable the moment anything tries to sign with it.
            // Only the two NEW rows are stored; 0..3 are already in the store
            // (and are healed by `ensureIdentityKeysSignable`, not here).
            try {
                dashPayRows.forEach { row ->
                    val preview = keys[row.keyId]
                    source.storeIdentityPrivateKey(walletIdHex, preview.publicKeyHex, preview.privateKey)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Nothing was submitted — provably pre-broadcast, retry-safe.
                return notBroadcast(idHex, "DashPay key persist failed", t)
            }

            // THE state transition. Signed with keyId 0 (MASTER/AUTH) Rust-side
            // — DPP accepts nothing else for an identity update.
            return try {
                source.addIdentityKeys(walletIdHex, identityId, dashPayRows)
                log.info(
                    "provisioned DashPay ENCRYPTION/DECRYPTION keys (ids {}) on identity {}… at DIP-9 slot {}",
                    dashPayRows.map { it.keyId }, idHex.take(8), existing.identityIndex
                )
                SdkWriteResult.Broadcast(dashPayRows.size)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                when (val classified = classifyBroadcastFailure(t)) {
                    is SdkWriteResult.NotBroadcast -> {
                        log.warn(
                            "DashPay key provisioning for {}… rejected pre-broadcast; contact requests " +
                                "keep using the dashj fallback",
                            idHex.take(8), t
                        )
                        classified
                    }
                    else -> {
                        log.error(
                            "DashPay key provisioning for {}… outcome unconfirmed — the IdentityUpdate MAY " +
                                "be on chain; NOT re-submitting this process (a duplicate key id would be " +
                                "rejected after paying the fee)",
                            idHex.take(8), t
                        )
                        SdkWriteResult.Ambiguous(t)
                    }
                }
            }
        } finally {
            // Every derived scalar — including the four we never stored —
            // leaves no unscrubbed key material behind, on every exit path.
            keys.forEach { it.privateKey.fill(0) }
        }
    }

    private fun notBroadcast(idHex: String, reason: String, cause: Throwable?): SdkWriteResult.NotBroadcast {
        log.info("DashPay key provisioning for {}… not attempted ({})", idHex.take(8), reason, cause)
        return SdkWriteResult.NotBroadcast(reason, cause)
    }

    private fun identityKey(identityId: ByteArray): String =
        identityId.joinToString("") { "%02x".format(it) }

    companion object {
        private val log = LoggerFactory.getLogger(SdkDashPayKeyProvisioning::class.java)

        /**
         * Retryable (provably pre-broadcast) provisioning attempts allowed per
         * process. Bounds the credit spend of a persistently failing identity
         * without wedging a wallet that hits one transient failure.
         */
        internal const val MAX_ATTEMPTS = 3
    }
}
