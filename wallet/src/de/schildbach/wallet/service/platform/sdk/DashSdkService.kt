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

import org.dashfoundation.dashsdk.Sdk
import org.dashfoundation.dashsdk.wallet.PlatformWalletManager

/**
 * Result of one [DashSdkService.ensureIdentityKeysSignable] pass over an
 * identity's persisted public keys (Phase 3f-b key healing).
 *
 * Counts partition the identity's key rows:
 * - [healthy]: a private key was already stored for the pubkey
 *   (`privkey.<pubkeyHex>` in the SDK's `WalletStorage`) — the signer can
 *   use it as-is.
 * - [repaired]: the private key was missing, the canonical DIP-9 slot
 *   derive REPRODUCED the on-chain public key, and the verified scalar was
 *   stored — signable from now on.
 * - [watchOnly]: permanently un-repairable from this wallet's seed — the
 *   slot derive produced a DIFFERENT public key (foreign / non-ECDSA /
 *   externally-registered key), or the row is read-only or disabled.
 *   Retrying cannot change this (the derivation is deterministic).
 * - [failed]: a transient derive/store failure (Keystore auth window
 *   expired, FFI error) — retrying on a later pass CAN succeed.
 */
data class IdentityKeyHealReport(
    val keysChecked: Int,
    val healthy: Int,
    val repaired: Int,
    val watchOnly: Int,
    val failed: Int
) {
    /** Every persisted key of the identity is signable right now. */
    val allSignable: Boolean get() = keysChecked > 0 && healthy + repaired == keysChecked

    /**
     * Nothing left that a retry could improve: at least one key row was
     * seen and no TRANSIENT failures occurred (watch-only keys are
     * permanent, so they don't block settling). `keysChecked == 0` is NOT
     * settled — key rows may still be landing via the persistence bridge.
     */
    val settled: Boolean get() = keysChecked > 0 && failed == 0
}

/**
 * Lifecycle owner for the Dash Platform Kotlin SDK inside the wallet app —
 * the Phase 3 bootstrap seam of the dashj → Kotlin SDK migration
 * (see `docs/kotlin-sdk-migration-plan.md`, "Phase 3 — Introduce the SDK").
 *
 * ## Phased plan
 *
 * - **Phase 3 (this scaffold):** the SDK can be brought up on demand —
 *   native init, Room database, Keystore-backed secret storage, per-network
 *   SDK build, wallet-manager activation — but NOTHING starts unless
 *   [ensureStarted] is explicitly called. No production code path calls it
 *   yet, so default app behavior is unchanged.
 * - **Phase 3b:** bridge the app's PIN-encrypted dashj BIP39 seed into the
 *   SDK's `WalletStorage` (via [PlatformMnemonicProvider]), port the
 *   `service/platform` stack (`PlatformService`, `PlatformSyncService`,
 *   `PlatformBroadcastService`, repositories) onto the SDK query
 *   namespaces, and wire the DashPay sync loops.
 * - **Phase 4+:** shielded balances, then the L1 cutover.
 *
 * ## Lifecycle rules (mirrors the SDK example app's `AppContainer`)
 *
 * - The SDK instance and its [PlatformWalletManager] are **network-locked**
 *   at creation. Switching networks means closing and rebuilding both —
 *   there is no reconfiguration path. In this app the network is fixed per
 *   build flavor ([de.schildbach.wallet.Constants.NETWORK_PARAMETERS]), so
 *   a rebuild only ever happens through [stop] + [ensureStarted].
 * - [ensureStarted] is idempotent and safe to call from multiple
 *   coroutines; the first caller pays the bootstrap cost.
 */
interface DashSdkService {

    /** True once [ensureStarted] has completed and [stop] has not run. */
    val isStarted: Boolean

    /**
     * Bring the SDK up if it is not already running. Performs, once, in
     * order (faithful to the example app's `AppContainer.bootstrap()` /
     * `activateManager()`):
     *
     * 1. `Sdk.initialize()` — native library load + `dash_sdk_init`,
     * 2. SDK logging setup,
     * 3. Room [org.dashfoundation.dashsdk.persistence.DashDatabase] creation,
     * 4. [org.dashfoundation.dashsdk.security.WalletStorage] init,
     * 5. per-network `Sdk.create(...)` for the network mapped from
     *    `Constants.NETWORK_PARAMETERS`,
     * 6. [org.dashfoundation.dashsdk.wallet.WalletManagerStore] activation,
     * 7. `loadPersistedWallets()`.
     *
     * Sync-service binding (platform-address / shielded / DashPay loops)
     * is deliberately NOT performed — that lands in Phase 3b/4.
     *
     * Throws if bootstrap fails; the service stays stopped and a retry is
     * permitted.
     */
    suspend fun ensureStarted()

    /**
     * Tear down: close all wallet managers, the SDK handle and the Room
     * database. The native library itself stays loaded (process-wide).
     * Safe to call when not started.
     */
    suspend fun stop()

    /** The live SDK handle, or null if [ensureStarted] has not completed. */
    fun sdkOrNull(): Sdk?

    /**
     * The SDK's Room database, or null if [ensureStarted] has not
     * completed. Read-only consumers only (the shielded note/activity
     * flows in [ShieldedBalanceService]); writes stay with the SDK's own
     * persistence bridge.
     */
    fun databaseOrNull(): org.dashfoundation.dashsdk.persistence.DashDatabase?

    /**
     * The activated wallet manager for the app's network, or null if
     * [ensureStarted] has not completed.
     */
    fun walletManagerOrNull(): PlatformWalletManager?

    /**
     * Wallet ids currently loaded in the SDK manager — a cheap snapshot,
     * empty when the SDK isn't started (never triggers a bring-up). The
     * app's model is ONE wallet: more than one entry means stale leftovers
     * of an earlier (reset/wiped) app wallet, which stall every
     * `singleOrNull()`-based bound-wallet lookup.
     */
    fun loadedWalletIds(): Set<String>

    /**
     * Proof-of-life read-only query: resolve a DPNS username to its record
     * (JSON), or null if unregistered. Internally calls [ensureStarted].
     *
     * NOT called from any production code path — it exists so Phase 3b has
     * a verified end-to-end entry point (JNI → Rust → DAPI) to build on.
     */
    suspend fun resolveUsername(name: String): String?

    /**
     * Bind the app's dashj wallet to the SDK: derive an SDK wallet from the
     * same BIP39 phrase so both stacks operate on one seed — the Phase 3b
     * bridge (`docs/kotlin-sdk-migration-plan.md`). Internally calls
     * [ensureStarted]. NOT called from any production code path yet; the
     * Phase 3c `service/platform` port becomes the first caller.
     *
     * ## Contract
     *
     * - **Input**: [seedWords] straight from
     *   [PlatformMnemonicProvider.getMnemonicWords] — i.e. the caller has
     *   already authenticated the user and decrypted the dashj seed. Words
     *   are never logged or persisted app-side.
     * - **Idempotent**: the SDK's `createWallet` does NOT dedup (re-running
     *   it re-registers the same derived wallet id), so this call first
     *   matches the phrase against the mnemonics already persisted in the
     *   SDK's Keystore-backed `WalletStorage` for the wallets
     *   `loadPersistedWallets()` restored; on a match it returns the
     *   existing id without touching native wallet creation.
     * - **Resolver wiring**: on first bind, `createWallet` persists the
     *   phrase into the SDK's `WalletStorage` keyed by the derived wallet
     *   id (Kotlin-side, after the FFI returns the id — verified against
     *   `PlatformWalletManager.createWallet`); the manager's
     *   `MnemonicResolverAndPersister` reads from that same storage, so
     *   every post-bind derivation resolves WITHOUT re-prompting the user.
     * - **Birth height**: the SDK wants a block *height* to start compact-
     *   filter scanning from, but the app only knows a birth *time*
     *   ([org.bitcoinj.wallet.Wallet.getEarliestKeyCreationTime]), and a
     *   too-high height silently skips funds while `0` (genesis) is merely
     *   slower. Phase 5a maps time → height via the app's dashj checkpoint
     *   files ([BirthHeightResolver]: checkpoint at-or-before the birth
     *   time minus a ~1-week safety margin; `0` when unresolvable). The
     *   height only applies to a FIRST-time bind — a wallet bound before
     *   the mapping landed keeps its stored `birthHeight = 0`, because
     *   re-binding dedups on the persisted mnemonic and never re-creates.
     *
     * @param seedWords the wallet's BIP39 words, already decrypted.
     * @param birthTimeSecs the dashj wallet's earliest-key time (Unix
     *   seconds), or null if unknown (null → full scan from genesis).
     * @return the bound SDK wallet id as lowercase hex (64 chars).
     */
    suspend fun bindAppWallet(seedWords: List<String>, birthTimeSecs: Long?): String

    /**
     * Destroy the bound SDK wallet and its ENTIRE persisted SDK-side
     * state — the definitive shadow-state recovery step
     * ([L1ShadowSyncService.recoverByRecreatingWallet]): when per-wallet
     * scan state is corrupt beyond what row deletion can heal, the only
     * SDK surface that provably discards it is the full wallet-removal
     * cascade, after which a fresh [bindAppWallet] re-creates the wallet
     * from the same seed (same deterministic id) with a clean slate.
     *
     * ## What the SDK's `PlatformWalletManager.removeWallet` deletes
     * (traced through the SDK sources — `PlatformWalletManager.kt:521`,
     * `PlatformWalletPersistenceHandler.deleteWalletData`, line 2087, one
     * Room transaction):
     *
     * 1. every identity private key of the wallet's identities from the
     *    Keystore-backed `WalletStorage` (`privkey.<pubkeyHex>` entries);
     * 2. the Rust wallet (native unregister + in-memory manager map entry
     *    + native handle close) — including the in-Rust sync/scan state
     *    rehydration source;
     * 3. the Room cascade: identity rows (+ their CASCADE children:
     *    public keys, DPNS names, DashPay profiles/contact requests/
     *    payments, documents), `txos`, `pending_inputs`, `asset_locks`,
     *    `platform_addresses`, all four shielded tables
     *    (`shielded_notes`, `shielded_outgoing_notes`,
     *    `shielded_activities`, `shielded_sync_states` — the shielded
     *    balance therefore needs a full re-sync after re-creation), the
     *    `wallets` row itself (CASCADE → accounts → core/platform
     *    addresses), and an orphaned-`transactions` sweep;
     * 4. LAST, the wallet's mnemonic from `WalletStorage`
     *    (`mnemonic.<walletIdHex>`).
     *
     * The mnemonic deletion is safe for re-creation: the app's canonical
     * seed lives in the (untouched) dashj wallet, and the next
     * [bindAppWallet] hands freshly-decrypted words to `createWallet`,
     * which re-derives the SAME network-scoped wallet id (deterministic
     * from the seed — `rs-platform-wallet/src/manager/wallet_lifecycle.rs`)
     * and re-stores the phrase keyed by it.
     *
     * Does NOT touch: any dashj state (wallet file, block store, keys),
     * the app's own Room database, or the shadow SPV dataDir (chain data
     * — deleted separately by the recovery path). Internally calls
     * [ensureStarted]. No-op (logged) when the wallet id is not loaded.
     *
     * @param walletIdHex the bound wallet id ([bindAppWallet]'s return).
     */
    suspend fun removeAppWallet(walletIdHex: String)

    /**
     * True when [identityId] (32 bytes) is a *managed* identity of the SDK
     * wallet [walletIdHex] — i.e. the Rust `IdentityManager` holds its slot
     * and can derive/sign with its keys (the precondition every
     * [SdkDashPayWrites] preflight probes). Local snapshot read
     * (`dashpay.syncState`), no network. Internally calls [ensureStarted].
     * Returns false when the wallet id is not loaded.
     */
    suspend fun isIdentityManaged(walletIdHex: String, identityId: ByteArray): Boolean

    /**
     * Phase 3f identity discovery: scan the bound SDK wallet's DIP-9
     * identity-authentication tree (`m/9'/coin'/5'/0'/0'/identity_index'`)
     * and ATTACH every identity registered on Platform for one of those
     * keys to the wallet's Rust `IdentityManager` (persisted via Room), so
     * [isIdentityManaged] turns true and the SDK can derive its keys.
     *
     * This is the op that adopts the app's EXISTING dashj-registered
     * identity (registered at identity index 0 — see the [SdkDashPayWrites]
     * key-derivation parity note) into the SDK wallet: the FFI
     * (`platform_wallet_discover_identities`) derives consecutive MASTER
     * keys, queries Platform by unique pubkey hash, and stops after the
     * gap limit. Network I/O — one Platform query per probed slot.
     *
     * @param walletIdHex the bound wallet ([bindAppWallet]'s return).
     * @param startIndex first identity index to probe; 0 forces a full
     *   rescan (the app identity lives at index 0), negative resumes from
     *   the wallet's cached scan cursor.
     * @return the NEWLY-discovered 32-byte identity ids (already-managed
     *   identities are not re-reported).
     * @throws Exception if the wallet is not loaded or the scan fails.
     */
    suspend fun discoverIdentities(walletIdHex: String, startIndex: Int = 0): List<ByteArray>

    /**
     * Phase 3f-b key healing: make a MANAGED identity's private keys
     * actually signable, so [SdkDashPayWrites] doesn't die at the FFI
     * signer with "no private key stored for <pubkeyHex>".
     *
     * ## Why discovery alone is not enough
     *
     * [discoverIdentities] attaches the identity and persists its public
     * keys with per-key derivation breadcrumbs; the SDK's persistence
     * bridge then auto-derives each private scalar and encrypts it into
     * `WalletStorage` (`privkey.<pubkeyHex>`) — but that store runs under
     * the Keystore's AUTH-GATED keys alias
     * (`KeystoreManager.KEYS_ALIAS`, `setUserAuthenticationRequired`,
     * 30-second window) and the persistence handler SWALLOWS a failed
     * derive/store ("key stays watch-only"). A discovery pass running in
     * the background more than ~30s after the last device unlock therefore
     * attaches the identity but leaves ZERO private keys stored — the
     * exact state observed live (Galaxy S22, testnet). The FFI signer
     * ([org.dashfoundation.dashsdk.security.KeystoreSigner]) resolves
     * signing keys ONLY from that `WalletStorage` store, so such an
     * identity is managed yet unsignable.
     *
     * ## What this op does (the example app's key-health "Repair" flow)
     *
     * For every persisted public key of [identityId] (SDK Room
     * `PublicKeyDao`): if `WalletStorage.hasPrivateKey(pubkeyHex)` is
     * false, re-derive the canonical keypair at
     * `(identity.identityIndex, keyId)` via the resolver-keyed FFI
     * (`IdentityNative.deriveIdentityKeyPairWithResolver` — mnemonic never
     * leaves Rust, scalar scrubbed after use), VERIFY the derived public
     * half reproduces the on-chain key (the same guard Rust discovery
     * applies — a mismatching key stays watch-only rather than poisoning
     * the store with a wrong scalar), and store the verified scalar via
     * `WalletStorage.storePrivateKey` — the identical call the SDK's own
     * `PlatformWalletManager.repairIdentityKey` lands on, plus the
     * verification it omits.
     *
     * Idempotent (already-stored keys are left untouched) and prompt-free:
     * a store hitting an expired Keystore auth window is counted in
     * [IdentityKeyHealReport.failed] for a later retry, never surfaced as
     * a prompt. Local I/O only (Room + Keystore + in-process FFI derive);
     * no network. Internally calls [ensureStarted].
     *
     * @param walletIdHex the bound wallet ([bindAppWallet]'s return).
     * @param identityId the 32-byte identity id (must already be managed —
     *   its key rows come from the discovery/persist bridge).
     * @return per-key outcome counts; see [IdentityKeyHealReport].
     * @throws Exception on wiring failures (SDK not started, malformed
     *   wallet id, Room read failure) — per-key derive/store failures are
     *   contained in the report instead.
     */
    suspend fun ensureIdentityKeysSignable(
        walletIdHex: String,
        identityId: ByteArray
    ): IdentityKeyHealReport
}
