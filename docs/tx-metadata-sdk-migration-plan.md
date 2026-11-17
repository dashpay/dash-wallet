# txMetadata migration plan for `feat/kotlin-sdk-phase-1`

**Updated:** 2026-11-17

**Target:** Replace `org.dashj.platform` txMetadata reads and writes with the pinned Kotlin SDK, preserve existing encrypted documents, and restore every document for an identity. This is the txMetadata work needed for [dash-wallet #1540](https://github.com/dashpay/dash-wallet/issues/1540) and [#1541](https://github.com/dashpay/dash-wallet/issues/1541).

**Status:** Plan only; no implementation or PR submitted.

## Current state and decisions

- `feat/kotlin-sdk-phase-1` at `d3ca768727bc6b59c2edd8b455907e0643d7f86e` pins `org.dashj:dash-sdk-android:5.1.0-int36-20261106.165805-1`. Its production metadata fetch and publish still call legacy `BlockchainIdentity.getTxMetaData` and `publishTxMetaData` in `PlatformSyncService`. The existing `SdkTxMetadataSource` supplies local default transaction rows; it does **not** fetch Platform metadata. The debug decrypt probe's production source throws `UnsupportedOperationException` because its earlier encrypted-document API never merged.
- The pinned SDK's generic `Documents.search(..., startAt: Int)` forwards to native `dash_sdk_document_search`, where any positive `start_at` fails with `start_at pagination is not yet implemented. Use limit instead.` The 100-document limit is **per generic query**. The current legacy metadata query also fetches one page at a time, so repeated timestamp-based sweeps may accumulate more than 100, but a full restore is not guaranteed when a page is full or many documents share `$updatedAt`.
- Contact requests use a different Rust query that pages with `Start::StartAfter(document_id)`; that does not make generic `Documents.search` paginated. Swift's generic `documentList` calls the same native search and converts its `startAfter`/`startAt` strings to a numeric `start_at`; it has the same generic-query limitation. This says nothing about any separate Swift contact-request sync path.
- Use a **small generic Platform query PR** for document-ID cursor pagination, plus a narrow SDK key-derivation primitive if needed. Keep the txMetadata codec and sync orchestration in the Android wallet. The earlier encrypted-document work in [Platform #4277](https://github.com/dashpay/platform/pull/4277) was reverted by [#4279](https://github.com/dashpay/platform/pull/4279); [#4243](https://github.com/dashpay/platform/pull/4243) did not merge. Do not assume those APIs exist in the pinned AAR.

## Work list, in dependency order

### 1. Lock the compatibility contract and test fixtures

- [ ] Record the exact legacy `wallet-utils.txMetadata` contract ID on each supported network and its `keyIndex`, `encryptionKeyIndex`, `encryptedMetadata` fields, 32–4096-byte encrypted payload bound, and `($ownerId, $updatedAt)` index. Confirm that no contract change is needed for existing documents.
- [ ] Capture legacy-authored encrypted documents and the known plaintext as test fixtures, including version 0 CBOR, version 1 protobuf, multiple batches, nondefault key indices, and a restored wallet. Verify the exact hardened derivation path, key bytes, AES-256-CBC padding, payload header/IV layout, and txid byte order against the legacy implementation. Do this before replacing production reads.
- [ ] Define the restore acceptance case: more than 100 documents, including more than 100 with the same `$updatedAt`, must all be fetched exactly once per sweep. A restart and a later update must not lose or duplicate metadata.

### 2. Submit a Platform PR for generic document pagination

- [ ] Add a document-ID cursor to the Rust FFI search API and map it to `DocumentQuery.start = Start::StartAfter(id)` (optionally `StartAt` for inclusive callers). Do not reinterpret the current numeric `start_at` as a document ID or silently change the existing C struct layout; use an ABI-safe addition or versioned API.
- [ ] Preserve query order in returned documents, expose each document ID to the caller, and specify how the next cursor is chosen from the **last fetched document in query order**. Keep `$ownerId` filtering and `$updatedAt ASC` ordering working on the `txMetadata` index. Verify that identifier-valued where clauses and encrypted byte arrays survive FFI JSON conversion.
- [ ] Expose the cursor in Kotlin `Documents.search` as a document ID, alongside the existing query arguments. Reject incompatible/ambiguous numeric `startAt` use rather than promising offset pagination. Update JNI bindings, native headers, API docs, and SDK examples as required by the chosen FFI shape.
- [ ] Fix Swift `documentList` to pass a real document-ID cursor through the new FFI API. Remove its numeric string-to-`UInt32` cursor conversion and cover both `whereClause` and no-`whereClause` branches. This is SDK parity; no Swift app change is assumed here.
- [ ] Test 0, 1, 100, 101, and 200+ results; tied timestamps; multiple pages; invalid cursor; and no duplicate/missing IDs. Check the cursor's behavior when a full page includes absent/failed document results, and document whether `total_count` is a page count rather than a global total.

### 3. Add only the missing SDK key primitive

- [ ] Verify whether the current wallet manager/mnemonic resolver can derive the exact legacy metadata path. Its public `deriveIdentityKeyPair(walletId, identityIndex, keyIndex)` stops at an identity key slot and does not expose the additional hardened metadata children.
- [ ] If it cannot, add a narrow wallet-scoped derivation API through Rust/JNI/Kotlin for the legacy metadata key. Keep seed and private-key handling in the SDK's established resolver/security boundary; clear transient key material and do not log it. Include mainnet/testnet and multiple-index parity vectors.
- [ ] Keep this separate from generic document encryption. A dedicated encrypted-document SDK API is only necessary if the wallet-owned codec fails a compatibility or security review.

### 4. Publish and pin a usable SDK build

- [ ] Merge the Platform changes, build and publish an Android AAR containing the matching native library and Kotlin bindings, and update the `dashSdkVersion` pin on `feat/kotlin-sdk-phase-1` using its release recipe.
- [ ] Verify the **published AAR**, not only Platform source: `startAfter` actually advances the native query, the metadata key vector matches legacy, and the returned document JSON contains the fields needed for decryption.
- [ ] Verify the Swift package/build uses the matching native FFI before claiming Swift pagination is fixed.

### 5. Implement the Android txMetadata codec and document adapter

- [ ] Implement legacy-compatible decryption and decoding of version 0 CBOR and version 1 protobuf documents. Preserve all present metadata fields, especially memos, fiat exchange rates, tax/payment categories, service, sent time, and gift-card fields. Reject/record malformed or undecryptable documents without losing the rest of the page.
- [ ] Implement version 1 protobuf encoding, legacy AES-256-CBC envelope construction with a fresh IV, correct key indices, and document splitting so `encryptedMetadata` stays within the 4096-byte contract maximum. Keep encrypted content opaque to the Platform document API.
- [ ] Use SDK generic document create for the wallet-utils contract and parse/verify the confirmed document ID and revision. Define collision-free key-index allocation after restore; do not rely on the current `1 + local document count` when local records can be incomplete. Preserve legacy document ownership and network selection.
- [ ] Put SDK fetch/create and the codec behind a narrow wallet-side source so the sync logic can be tested without native code. Replace the debug probe's unsupported fetch with a real read-only compatibility check once the required SDK surface exists.

### 6. Replace metadata restore and incremental sync in the wallet

- [ ] Query the identity's `txMetadata` documents in `$updatedAt ASC` order, page until exhaustion with the document-ID `startAfter` cursor, and use the timestamp only as a **between-sweep high-water mark**. Never advance a timestamp watermark from an incomplete page or use it as the only within-sweep cursor.
- [ ] Reuse the existing document-ID deduplication, Room document records, local-change conflict handling, batched SDK-only transaction-row lookup, and metadata import path. Make page application and checkpointing restart-safe, including a full page of documents sharing one timestamp.
- [ ] Preserve the current overlap/recheck behavior for recent updates, but prove it cannot hide unprocessed documents. Add visible fetch totals and failure state so a partial restore is not reported as complete.
- [ ] Route the existing settings, worker, ticker, and on-demand refresh calls through the new source. Continue to honor wallet reset/cancellation and identity availability gates.

### 7. Replace publishing and retire the legacy dependency

- [ ] Route the existing metadata change queue and privacy/save settings through the new encoder and SDK document create. Preserve batching, progress, cancellation between documents, and retry behavior.
- [ ] Remove queued changes only after each corresponding document is confirmed and locally recorded. Handle a partial publish or ambiguous network result without silently dropping changes or creating duplicate metadata on retry.
- [ ] After read/write parity is proven, remove the legacy metadata calls and related `org.dashj.platform` types from these paths. Then complete the wider #1540/#1541 dependency-removal checks; txMetadata alone is one blocker, not the whole cutover.

### 8. Verification and rollout gate

- [ ] Cross-version tests: legacy Android write → new Android restore; new Android write → legacy Android read; app restart, seed restore, offline/retry, and >100-document pagination with timestamp ties. Include a live testnet document created by the old client.
- [ ] Test no plaintext metadata appears in Platform document properties, logs, or persisted query/debug output. Verify decryption failures do not prevent later pages from being fetched.
- [ ] Check Swift generic `documentList` pagination with 101+ documents on the same SDK release. Separately confirm any iOS txMetadata consumer can read documents written by the new Android codec before declaring cross-client write compatibility.
- [ ] Gate production cutover on a full paginated restore, decryption/field parity, safe partial-publish recovery, and a published/pinned SDK build. Track the Platform PR and Android PR separately, with both targeting the canonical `dashpay` repositories.

## Source anchors

- [Pinned Platform native search rejects `start_at`](https://github.com/dashpay/platform/blob/9766bab24a14af7cfbcafe33b466a777db8f868c/packages/rs-sdk-ffi/src/document/queries/search.rs#L199-L215); [Kotlin `Documents.search` forwards it](https://github.com/dashpay/platform/blob/9766bab24a14af7cfbcafe33b466a777db8f868c/packages/kotlin-sdk/sdk/src/main/kotlin/org/dashfoundation/dashsdk/queries/PlatformQueries.kt#L884-L898).
- [Contact-request cursor implementation](https://github.com/dashpay/platform/blob/9766bab24a14af7cfbcafe33b466a777db8f868c/packages/rs-sdk/src/platform/dashpay/contact_request_queries.rs#L110-L175); [Swift generic `documentList` cursor conversion](https://github.com/dashpay/platform/blob/9da740bcb22bd16e7e7738e5ede7940ea45c6c22/packages/swift-sdk/Sources/SwiftDashSDK/FFI/PlatformQueryExtensions.swift#L754-L869).
- [Wallet-utils `txMetadata` schema](https://github.com/dashpay/platform/blob/9766bab24a14af7cfbcafe33b466a777db8f868c/packages/wallet-utils-contract/schema/v1/wallet-utils-contract-documents.json); [wallet branch metadata fetch/publish](https://github.com/dashpay/dash-wallet/blob/d3ca768727bc6b59c2edd8b455907e0643d7f86e/wallet/src/de/schildbach/wallet/service/platform/PlatformSyncService.kt#L1879-L1904); [wallet branch SDK pin](https://github.com/dashpay/dash-wallet/blob/d3ca768727bc6b59c2edd8b455907e0643d7f86e/build.gradle#L7-L12).
