# DASHJ KILL LIST — every remaining dashj dependency, and the fewest big steps to zero

Goal per team-lead direction (Brian): **no dashj mirroring/scaffolding — the fastest credible
path to deleting dashj from `build.gradle` entirely.** This inventory is grounded in a full
repo sweep (2026-07-18, branch `claude/blissful-cannon-7d4ac4`).

## Headline numbers

| Module | Files importing `org.bitcoinj` |
|---|---|
| `wallet/src` | 217 |
| `common/src` | 68 |
| `integrations/crowdnode` (the only integration on dashj) | 25 |
| `features/`, `integration-android/`, other integrations | 0 |
| **Total** | **~310** |

Most-used bitcoinj types repo-wide: `Coin` (105 files), `Transaction` (86), `Sha256Hash` (72),
`Address` (65), `Wallet` (60), `NetworkParameters` (48), `MonetaryFormat` (36), `Fiat` (20),
`ExchangeRate` (18), `AuthenticationGroupExtension` (17).

### Gradle declarations to delete at the end

- `build.gradle` (root): `dashjVersion = '22.0.3'`, `dppVersion = "2.0.6-SNAPSHOT"`
- `wallet/build.gradle` L83: `org.dashj:dashj-core:$dashjVersion`
- `wallet/build.gradle` L91–93: `dashj-bls-android`, `dashj-x11-android`, `dashj-scrypt-android`
- `wallet/build.gradle` L556–563: per-flavor `org.dashj.platform:dash-sdk-{java,kotlin,android}`
  (the OLD Java Platform SDK — distinct from the NEW `org.dashj:dash-sdk-android:0.1.0-SNAPSHOT`
  Kotlin SDK we are migrating TO, which stays)
- `wallet/build.gradle` L504–505: packaging of `org/bitcoinj/crypto/mnemonic/wordlist/english.txt`
  and `org/bitcoinj/crypto/cacerts`
- `wallet/build.gradle` L566+: the hand-written java.nio covariant-buffer patch that exists ONLY
  because `dashj-core 22.x` `SPVBlockStore` crashes on Android ≤ 15 — a standing tax of keeping
  the dashj L1 engine
- `common/build.gradle` L56: `org.dashj:dashj-core` (L58–59 bouncycastle stays for the copied BIP70 code)
- `integrations/crowdnode/build.gradle` L51: `org.dashj:dashj-core`

---

## Subsystem inventory

### 1. L1 engine (Wallet, PeerGroup, BlockChain, SPVBlockStore, masternode sync)
- **What it does**: the full dashj SPV node — header chain, bloom-filtered block download,
  peer discovery, masternode list sync, chainlock/islock handlers. Post-cutover (Phase 5d,
  live now behind `CUTOVER_STATE`) this engine is HELD and the Kotlin SDK's Rust SPV
  (`dash-spv` via platform-mobile JNI) owns L1.
- **SDK replacement**: exists and is cutover-gated already — `L1ShadowSyncService` runs the SDK
  SPV, `SdkL1SendService` routes sends, `SdkSourcedQuorums` feeds quorum lookups, and (new)
  `CutoverUiDataService` feeds the home-screen balance/tx-list/notifications.
- **Blast radius**: ~12 files own the engine surface — `wallet/src/de/schildbach/wallet/service/BlockchainServiceImpl.kt`
  (~38 bitcoinj imports), `BlockchainService.java`, `DashSystemService.kt` (wraps
  `org.bitcoinj.manager.DashSystem`), `BlockchainStateDataProvider.kt`, `TestingSPVBlockStore.kt`,
  `ui/{BlockListFragment,BlockListAdapter,PeerListFragment}.java`, `util/AllowLockTimeRiskAnalysis.kt`.
- **Named gaps**: SDK exposes no peer-list/block-list debug surface (those two debug screens die
  with the engine); dashj's `BlockchainState` (sync %, replaying, impediments) must be fully
  derived from `SpvSyncProgressData` (partially wired for the home header already).

### 2. Key derivation / signing / seed handling
- **What it does**: `DeterministicSeed` (10 files), `KeyChainGroup`, `DeterministicKeyChain`,
  `ECKey` (15), `KeyCrypterScrypt`/`KeyCrypterException` (17), `MnemonicCode`, `BIP38PrivateKey`,
  `LinuxSecureRandom`. `SecurityGuard` (38 files) brokers the wallet password/PIN and feeds
  dashj's KeyCrypter for wallet encryption; `PlatformMnemonicProvider`/`SecurityGuardMnemonicProvider`
  already bridge the seed into the Kotlin SDK (the SDK signs with its own derivation — parity-proven).
- **SDK replacement**: the SDK derives/signs everything from the mnemonic (`mnemonicResolverHandle`;
  no private key crosses the JNI boundary). GAPS: BIP38 private-key sweep import, paper-key
  decryption UI, and BIP39 wordlist utilities (`MnemonicCodeExt` checks words locally) have no
  SDK equivalent yet — either the SDK exposes mnemonic/WIF utilities or we keep a tiny
  self-contained BIP39/Base58 util (NOT dashj).
- **Blast radius**: `wallet/src/de/schildbach/wallet/security/{SecurityGuard.java,SecurityFunctions.kt,MnemonicBasedKeyProvider.kt}`,
  `payments/{DeriveKeyTask,DecryptSeedTask,DecodePrivateKeyTask}.java`,
  `livedata/EncryptWalletLiveData.kt`, `ui/{EncryptKeysDialogFragment,BackupWalletToSeedDialogFragment,…}.java`,
  `util/MnemonicCodeExt.kt` — ~40 files.

### 3. Neutral value/address types leaked through `common/` APIs
- **What it does**: `org.dash.wallet.common.WalletDataProvider` exposes ~11 bitcoinj types
  (`Address`, `Coin`, `NetworkParameters`, `Sha256Hash`, `Transaction`, `TransactionBag`,
  `CoinSelector`, `Wallet`, `AuthenticationGroupExtension`, …) — every integration inherits
  bitcoinj through this one interface. `Coin`/`Fiat`/`MonetaryFormat` are the money types of the
  entire UI (61 files); `ScriptPattern` (16 files) parses outputs.
- **SDK replacement**: none needed from the SDK — this is an APP-SIDE abstraction job. The
  neutral `Dash` money type (`org.dash.wallet.common.money.Dash`) and the neutral send overload
  (`sendCoins(address: String, amount: Dash)`) already exist and are used by Coinbase/Maya; the
  new `L1TxUiRecord` covers tx display. Finish the facade: duffs-Long/`Dash` for amounts,
  base58 `String` for addresses, hex `String` for txids.
- **Blast radius**: `common/src/main/java/org/dash/wallet/common/WalletDataProvider.kt` (+Ext),
  `common/.../transactions/*`, `common/.../money/*`, `common/.../data/PaymentIntent.java` —
  68 files in common, ~25 in crowdnode, and every wallet-module implementer.

### 4. Payment protocol / BIP70 / NFC & QR URIs
- **What it does**: `uri.BitcoinURI` (14 files) parses QR/NFC `dash:` URIs;
  BIP70 was already COPIED out of dashj into `common/.../payments/bip70/` (5 files) but still
  imports dashj's `crypto.TrustStoreLoader` types; address parsing uses `Base58`,
  `AddressFormatException`, `PrefixedChecksummedBytes`.
- **SDK replacement**: none required — URI parsing and BIP70 are pure-JVM; finish the copy-out
  (self-contained Base58/bech32 already exists: `common/.../payments/parsers/{Bech32.java,SegwitAddress.java}`,
  and the sdk package's `Bech32m.kt`). The FFI validates addresses Rust-side on send.
- **Blast radius**: `common/.../payments/{parsers,bip70}/*`, `wallet/.../ui/util/InputParser.java`,
  `WalletUri.java`, `SendCoinsQrActivity.java`, `offline/*` (NFC/Bluetooth payments) — ~25 files.

### 5. Identity / DashPay signing (the OLD Java Platform SDK, `org.dashj.platform.*`)
- **What it does**: 41–44 files use `dpp.identifier.Identifier`, `sdk.platform.Names`,
  `dashpay.BlockchainIdentity`, DAPI clients, voting types; bitcoinj-side identity funding uses
  `AssetLockTransaction` (10 files) + `AuthenticationGroupExtension` (17 files) + BLS types.
- **SDK replacement**: the Kotlin SDK already carries the write paths (`SdkDashPayWrites`,
  `SdkShieldedUsernameCreation`, `SdkShieldedInviteCreation`, `SdkVotingQueries`,
  `SdkIdentityVerifyWrites`, `SdkProfileQueries`, …) behind `USE_KOTLIN_SDK_*` flags. The kill
  is flipping those flags to unconditional and deleting the dashj-platform twins in
  `PlatformRepo`/`PlatformBroadcastService`/`CreateIdentityService`.
- **Named gaps**: identity RESTORE/topup edge flows and the `AuthenticationGroupExtension`
  key-usage report (masternode keys screen) still have no SDK query equivalents.
- **Blast radius**: `wallet/.../ui/dashpay/**`, `wallet/.../service/platform/**` — ~50 files,
  plus the per-flavor `dash-sdk-{java,kotlin,android}` Gradle lines.

### 6. Checkpoints / birth height / chain bootstrap
- **What it does**: `CheckpointManager` (3 files) + `wallet/assets/checkpoints{,-testnet}.txt`
  fast-forward dashj's chain; `Constants.java` (11 bitcoinj imports) holds `NetworkParameters`.
- **SDK replacement**: the SDK SPV has its own bootstrap; `BirthHeightResolver.kt` already maps
  the dashj checkpoint file to an SDK birth height. Post-dashj, keep the checkpoint TEXT files
  (they are just data) with a tiny local parser, or move birth-height mapping into the SDK.
- **Blast radius**: `Constants.java`, `BlockchainServiceImpl.kt`, `BlockchainStateDataProvider.kt`,
  `service/platform/sdk/BirthHeightResolver.kt` — 5 files.

### 7. InstantSend / ChainLock verification (pre-cutover display + spend gating)
- **What it does**: `TransactionConfidence` (14 files; `IXType`, `isChainLocked`) drives
  Sending/Sent/Processing display and `ChainLockedCoinSelector` spend gating;
  `org.bitcoinj.quorums.*` (3 files) verifies islocks pre-cutover.
- **SDK replacement**: the SDK's Rust core verifies islocks/chainlocks itself and persists the
  verdict as the `transactions.context` column (0=mempool, 1=instantSend, 2=inBlock,
  3=inChainLockedBlock) — the new `CutoverUiDataService`/`L1TxUiRecord` already consumes it
  post-cutover. Dies fully with the L1 engine (step B below).
- **Blast radius**: `payments/ChainLockedCoinSelector.kt`, `common/.../filters/LockedTransaction.kt`,
  tx display cluster (~28 files touch confidence-based logic).

### 8. Wallet file format / backup / encryption
- **What it does**: `WalletProtobufSerializer` (5 files) + `Protos` read/write the `.wallet`
  file; `WalletEx` (12 files) is the concrete wallet type; encrypted protobuf backups
  (`BackupWalletDialogFragment`, `Crypto.java`).
- **SDK replacement**: the SDK persists its own wallet (Room + Rust state). The `.wallet` file
  is retained READ-ONLY through the cutover horizon for rollback; at SETTLED it is dead weight.
  GAP: user-facing backup/restore interop — a backup made post-dashj must still restore into
  old app versions or be explicitly versioned; seed-phrase (BIP39) restore is the durable path.
- **Blast radius**: `service/WalletFactory.kt`, `WalletApplication.java`, `ui/backup/*`,
  `util/{WalletUtils,Crypto}.java`, `ui/util/InputParser.java` — ~10 files.

### 9. CoinJoin remnants
- **What it does**: the mixing engine is GONE (verified); only the classification enum
  `org.bitcoinj.coinjoin.utils.CoinJoinTransactionType` remains in 6 files, labeling HISTORIC
  mixing txs in the list/CSV export.
- **Replacement**: none needed — historic labels live in the Room display cache
  (`tx_display_cache` persists resolved title strings). One rebuild-less release later the enum
  usage can be deleted outright; SDK direction code 3 (coinJoin) covers any residual need.
- **Blast radius**: `ui/transactions/TxResourceMapper.kt`, `transactions/coinjoin/*` (3),
  `service/WalletTransactionMetadataProvider.kt`, `transactions/CSVExporter.kt`,
  `service/platform/PlatformSyncService.kt` — 6 files.

### 10. Exchange rates / fiat / money formatting
- **What it does**: `Fiat` (20), `ExchangeRate` (18), `MonetaryFormat` (36) — 61 files of pure
  JVM arithmetic/formatting, zero networking, zero consensus.
- **Replacement**: no SDK capability needed. Either (a) lift the three classes' logic into
  `common/.../money/` (small, Apache-licensed, self-contained — `FiatValue.kt`/`Dash` already
  half-do this), or (b) keep them as the last deleted piece of step D.
- **Blast radius**: `common/.../money/*`, `common/.../util/*`, `CurrencyAmountView.java`,
  enter-amount UI, send flow — 61 files but a mechanical type swap.

### 11. Transaction display / wrapping
- **What it does**: `TransactionWrapper`/`TxResourceMapper`/`TransactionRowView` (~21 files)
  render dashj `Transaction`s into rows; `TxDisplayCacheService` persists them into the neutral
  Room cache (`TxDisplayCacheEntry` — primitives only).
- **SDK replacement**: `tx_display_cache` IS the neutral model; post-cutover
  `CutoverUiDataService` already writes rows straight from SDK records. The kill: make the SDK
  the ONLY row producer, render history once from the frozen dashj wallet (final cache build),
  then delete the wrapper/mapper pipeline. GAP (named): tx DETAIL surfaces
  (`TransactionResultViewBinder`) still require a live dashj `Transaction` — needs an SDK
  tx-detail query (inputs/outputs/addresses/fee) or an extended cache row.
- **Blast radius**: `ui/transactions/*`, `ui/main/TransactionAdapter.kt`,
  `service/TxDisplayCacheService.kt`, `common/.../transactions/*` — ~21 files.

### 12. Everything else
- `core.Context` propagation (33 files) + `utils.Threading` (12) — dies with the engine.
- `core.VersionMessage` (peer UA, 2 files), `core.Utils`/`Base58`/`VarInt` scattered utils.
- `integrations/crowdnode` (25 files): tx-matchers typed on `Transaction`/`ScriptPattern` — needs
  the neutral facade of §3 plus an outputs-by-tx SDK query.
- The `service/platform/sdk/` bridge layer itself (30 files) intentionally imports bitcoinj to
  translate between worlds; it shrinks to nothing as each twin dies.

---

## Kill order — FOUR big steps

**Step A — Neutralize the facade (no dashj types across module boundaries).**
Rewrite `WalletDataProvider` + `common/.../transactions/*` + `PaymentIntent` on neutral types
(duffs `Long`/`Dash`, base58/hex `String`s, `L1TxUiRecord`), port crowdnode's matchers, and
absorb `Fiat`/`ExchangeRate`/`MonetaryFormat` into `common/.../money/`. Finish the BIP70/URI
copy-out. Deletes `dashj-core` from **common** and **crowdnode** Gradle files. (§3, §4, §10, half §12)

**Step B — Settle the L1 cutover and delete the engine.**
Drive CUT_OVER → SETTLED, then delete `BlockchainServiceImpl`'s engine half, `DashSystemService`,
block/peer debug UIs, checkpoints wiring (keep the data files + `BirthHeightResolver`),
`ChainLockedCoinSelector`, confidence-based status logic, and `Context`/`Threading` propagation.
Requires closing the named SDK gaps first: tx-detail query, `BlockchainState` derivation,
send-all (GAP: `sendToAddresses` exposes no drain strategy). (§1, §6, §7, most §11, §12)

**Step C — Retire the old Java Platform SDK.**
Flip every `USE_KOTLIN_SDK_*` DashPay flag unconditional, delete the dashj-platform twins in
`PlatformRepo`/`PlatformBroadcastService`/`CreateIdentityService`, port identity-funding
bookkeeping off `AssetLockTransaction`/`AuthenticationGroupExtension`. Deletes the per-flavor
`org.dashj.platform:*` lines + `dashj-bls-android`/`dashj-x11-android`. (§5)

**Step D — Kill the wallet-of-record and the last utils.**
At SETTLED: stop writing the `.wallet` file, move backup to seed-phrase + SDK export, replace
`SecurityGuard`'s KeyCrypter usage with the SDK's encryption, swap `MnemonicCode` for a local
BIP39 util, drop the CoinJoin enum labels, delete `WalletFactory`/`WalletEx`/serializer code —
then remove `org.dashj:dashj-core` and `dashj-scrypt-android` from `wallet/build.gradle`, the
wordlist/cacerts packaging lines, and the java.nio SPVBlockStore patch. **dashj is gone.** (§2, §8, §9)

Order rationale: A is pure refactor (shippable anytime, unblocks every module), B rides the
already-running cutover machinery and removes the biggest runtime cost (double SPV, the
Android-15 buffer patch), C and D are then local to the wallet module with no cross-module
consumers left.
