# Proposal: Persist Gift Card Order ID in Transaction Metadata Backup

## Problem

Multi-quantity PiggyCards orders produce one Dash transaction with **N** gift cards
attached. Locally these are stored as N rows in the `gift_cards` table, keyed by
`(txId, index)`. The merchant API (PiggyCards) is the authoritative source for each
card's number, PIN, and barcode — the app re-fetches them using the **order ID**,
which is stored in `GiftCard.note` for `index = 0`.

After a wallet restore from the Dash Platform metadata backup, all N cards are gone
except for whatever can be reconstructed from `TransactionMetadataDocument`. Today
that reconstruction:

1. Restores **only Card #0** — `WalletTransactionMetadataProvider.updateGiftCardMetadata`
   gates the cache write on `giftCard.index == 0`.
2. **Drops the order ID entirely** — neither `TransactionMetadataCacheItem` nor
   `TransactionMetadataDocument` (nor the wire-format `TxMetadataItem` in DPP) has a
   `note` field. The order ID is never serialized.

The combined effect: after restore, the local DB has one card with no order ID, the
PiggyCards branch of `GiftCardDetailsViewModel.fetchGiftCardInfo` bails immediately
with `"piggycards order # is missing"`, and there is no way to recover cards #1..N.

A short-term mitigation already shipped (`maybeRecoverMissingPiggyCards` in
`GiftCardDetailsViewModel.kt`) triggers a one-shot merchant fetch when a single
PiggyCards card is present and still has its order ID — but the order ID is exactly
the field that gets wiped on restore, so the mitigation does not help the restore
case. It only helps forced-refresh and pre-restore scenarios.

## Goal

After a wallet restore, opening the details screen for a multi-quantity PiggyCards
purchase should automatically recover all N cards (number, PIN, barcode) by:

1. Reading the order ID from the restored metadata.
2. Calling the PiggyCards merchant API.
3. Letting the existing fetch path (`fetchGiftCardInfo` lines 437-470) add
   placeholders for missing siblings and populate them.

We do **not** need to back up cards #1..N individually — the merchant API is the
source of truth. Persisting the order ID is sufficient.

## Proposed Solution

Add a `note` field to the transaction metadata document type so the PiggyCards order
ID survives the round trip through Dash Platform.

The field travels through four representations and each needs to be updated:

| Layer | Type | Repo |
|---|---|---|
| DPP data contract | `txMetadata` document schema | **dashpay-platform (DPP)** — separate repo |
| SDK wrapper | `org.dashj.platform.wallet.TxMetadataItem` | **dashj-platform** — separate repo (re-published with new DPP) |
| Outgoing local cache | `TransactionMetadataCacheItem` | this repo |
| Incoming local mirror | `TransactionMetadataDocument` | this repo |

### Phase 1 — DPP / SDK changes (out-of-repo)

1. **DPP `txMetadata` document type** — add `note` (string, optional, max length
   matching existing free-text fields such as `memo`). Bump contract version per DPP
   conventions and confirm the platform contract owner ships the update.

2. **dashj-platform SDK** — regenerate / hand-edit `TxMetadataItem` so the new
   `note` field is included in the constructor, serialization, and deserialization
   paths. Ship a new SDK version. The wallet's `wallet/build.gradle` `dppVersion`
   then bumps to the new SDK.

These two steps must land first because the wallet's serialization path goes through
the SDK. Until they ship, the local Room columns can exist but the value cannot
leave the device.

### Phase 2 — Local entity + DAO changes (this repo)

1. **`TransactionMetadataCacheItem`**
   ```kotlin
   @Entity(tableName = "transaction_metadata_cache")
   data class TransactionMetadataCacheItem(
       // existing fields …
       var note: String? = null
   )
   ```
   Update the constructor that takes a `GiftCard`:
   ```kotlin
   constructor(transactionMetadata: TransactionMetadata, giftCard: GiftCard? = null, …) : this(
       …
       giftCard?.note     // <-- new
   )
   ```
   Include `note` in `isNotEmpty`, `compare`, and the `minus` operator.

2. **`TransactionMetadataDocument`** — add the same `var note: String? = null`.

3. **Room migration** — add two `ALTER TABLE` statements bumping the database
   version (current version + 1):
   ```sql
   ALTER TABLE transaction_metadata_cache ADD COLUMN note TEXT;
   ALTER TABLE transaction_metadata_platform ADD COLUMN note TEXT;
   ```
   Register a new `Migration(from, to)` in `AppDatabaseMigrations.kt` and add it to
   the migration chain in `AppDatabase`.

4. **`TransactionMetadataChangeCacheDao`** — extend `insertGiftCardData`:
   ```kotlin
   @Query(
       """INSERT INTO transaction_metadata_cache
          (txId, cacheTimestamp, giftCardNumber, giftCardPin, merchantName,
           originalPrice, merchantUrl, note)
          VALUES (:txId, :cacheTimestamp, :giftCardNumber, :giftCardPin,
                  :merchantName, :originalPrice, :merchantUrl, :note)"""
   )
   suspend fun insertGiftCardData(
       txId: Sha256Hash,
       giftCardNumber: String?,
       giftCardPin: String?,
       merchantName: String?,
       originalPrice: Double?,
       merchantUrl: String?,
       note: String?,
       cacheTimestamp: Long = System.currentTimeMillis()
   )
   ```

### Phase 3 — Provider + sync changes (this repo)

1. **`WalletTransactionMetadataProvider.updateGiftCardMetadata`** — pass `note` into
   the cache write. The `index == 0` gate stays (one cache row per txid is the
   correct shape; the order ID is the same across all cards in the order):
   ```kotlin
   if (giftCard.index == 0) {
       transactionMetadataChangeCacheDao.insertGiftCardData(
           giftCard.txId,
           giftCard.number,
           giftCard.pin,
           giftCard.merchantName,
           giftCard.price,
           giftCard.merchantUrl,
           giftCard.note     // <-- new
       )
   }
   ```

2. **`WalletTransactionMetadataProvider.insertOrUpdateGiftCard`** — preserve `note`
   on updates and accept it on inserts:
   ```kotlin
   val updatedGiftCard = existingGiftCard.copy(
       // existing fields …
       note = giftCard.note ?: existingGiftCard.note
   )
   ```

3. **`PlatformSyncService.publishTransactionMetadata`** — extend the
   `TxMetadataItem` builder once the SDK exposes the new field:
   ```kotlin
   TxMetadataItem(
       it.txId.reversedBytes,
       it.sentTimestamp,
       it.memo,
       it.rate?.toDouble(),
       it.currencyCode,
       it.taxCategory?.name?.lowercase(),
       it.service,
       it.customIconUrl,
       it.giftCardNumber,
       it.giftCardPin,
       it.merchantName,
       it.originalPrice,
       it.barcodeValue,
       it.barcodeFormat,
       it.merchantUrl,
       it.note          // <-- new
   )
   ```

4. **`PlatformSyncService` fetch path** (around line 1080, the `merchantUrl` block
   is the closest analogue) — on inbound metadata documents, copy `note` onto both
   the `metadataDocumentRecord` and the live `giftCard`:
   ```kotlin
   metadata.note?.let { orderId ->
       metadataDocumentRecord.note = orderId
       log.info("processing TxMetadata: note change")
       if (cachedItems.find {
               it.txId == txIdAsHash && it.cacheTimestamp > doc.updatedAt!! &&
                   it.note != null && it.note != orderId
           } == null
       ) {
           giftCard.note = orderId
       }
   }
   ```

5. **`mergeTransactionMetadataDocuments`** — include `note` in the last-write-wins
   reduction:
   ```kotlin
   note = docs.lastOrNull { it.note != null }?.note
   ```

6. **Change-detection plumbing** — wherever cache items are diffed to detect new
   uploads (`compare`, `minus`, `isNotEmpty`), include `note`.

### Phase 4 — Restore-time recovery (already in place)

`GiftCardDetailsViewModel.maybeRecoverMissingPiggyCards` already triggers a one-shot
merchant fetch when a single PiggyCards card has a non-empty `note`. Once Phases
1-3 are in place this path becomes effective on restore: the restored Card #0 will
have its `note` populated, the probe fires, `fetchGiftCardInfo` adds placeholders
for siblings, and the PiggyCards API fills them in.

No further change to `GiftCardDetailsViewModel` is required.

## Backwards Compatibility

- **Local DB:** new `note` column is nullable; existing rows migrate to `note =
  NULL` and the app behaves as today for those rows.
- **DPP documents:** old SDK versions reading new documents will silently ignore
  the unknown `note` field (standard protobuf behavior). New SDK reading old
  documents reads `note = null`.
- **Old orders made before this lands:** `note` is `NULL` in their backup. After
  restore they continue to lose cards #1..N — no way to retrofit. Document this in
  the release notes.

## Sequencing

1. Land DPP contract update + new dashj-platform SDK release.
2. Bump `dppVersion` in `wallet/build.gradle` to the new SDK.
3. Land Phases 2-3 in this repo, including the Room migration, in a single PR.
4. Verify end-to-end on testnet: purchase 2-card PiggyCards order, force a metadata
   publish, wipe the local app data, restore from seed, open the order, confirm
   both cards reappear.

## Out of Scope

- **Backing up cards #1..N individually.** Not needed; the merchant API is the
  source of truth and the existing fetch path handles recovery once the order ID
  is available.
- **Restructuring the cache schema to support multiple gift cards per txid.** Same
  reason — would be required only if we wanted the metadata document itself to be
  self-sufficient without API access.
- **CTX (DashSpend / CTXSpend) flow.** CTX fetches by `txid`, not by `note`, so it
  is unaffected by this gap and unaffected by this change.