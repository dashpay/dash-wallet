# Proposal: Restore Gift Card Merchant Icons in the Transaction Display Cache

## Problem

Starting with **v11.8.0**, gift card (DashSpend) transactions on the home screen
transaction list no longer show the **merchant logo** (DoorDash, Amazon, etc.).
Instead they fall back to a default icon. This is a regression introduced by the
`tx_display_cache` Room table that was added to speed up home-screen startup
(commits `3d3f46e17` #1473 and `5a126463a` #1482).

### Root cause

A gift card row is normally rendered by
`TransactionRowView.fromTransaction` (`TransactionRowView.kt:111-179`) with **two**
icon fields:

- `icon = R.drawable.ic_gift_card_tx` — a small generic gift-card badge
  (`TransactionRowView.kt:134`)
- `iconBitmap = metadata?.icon` — the **merchant logo bitmap**, decoded from the
  `icon_bitmap` table via `customIconId` (`TransactionRowView.kt:168`,
  `WalletTransactionMetadataProvider.kt:560-561`)

The adapter prefers the bitmap (`TransactionAdapter.kt:138-146`): when
`iconBitmap != null` it loads the **merchant logo as the large primary icon** and
demotes `ic_gift_card_tx` to a small secondary badge. That merchant logo is what
users recognize as "the gift card icon."

The display cache cannot represent it:

1. **`TxDisplayCacheEntry` has no bitmap field.** It stores `iconType` only as a
   small int enum (`ICON_GIFT_CARD = 4`, etc. — `TxDisplayCacheEntry.kt:54, 92`).
2. **On write,** `fromTransactionRowView` maps `row.icon` to that enum and
   **discards `row.iconBitmap` entirely** (`TxDisplayCacheEntry.kt:113-152`).
3. **On read-back,** `toTransactionRowView` hardcodes `iconBitmap = null` with the
   comment *"service icons loaded separately from metadata"*
   (`TxDisplayCacheEntry.kt:195`) — but that promise is never fulfilled.
4. **The live `RoomLive` paging path** simply calls
   `entry.toTransactionRowView(contactsByTxId[entry.rowId])`
   (`TxDisplayCacheService.kt:174-176`) and **never re-injects the bitmap**.
   `toTransactionRowView()` doesn't even accept a metadata parameter.

Result: gift-card rows always reach the adapter with `iconBitmap == null`, fall into
the `else` branch (`TransactionAdapter.kt:147-154`), and show only the static
drawable — the merchant logo is gone.

### Secondary timing hole

`updateDisplayCache` renders entries from `metadata[txId]`
(`TxDisplayCacheService.kt:534`). If a transaction is cached **before** its DashSpend
metadata has loaded, `ServiceName.isDashSpend(...)` is `false` and the entry is frozen
as `ICON_RECEIVED` / `ICON_SENT` until a later metadata-change re-render happens to
fire. Depending on timing the user sees *either* the generic gift-card badge *or* the
plain sent/received icon — both of which read as "the default icon."

## Goal

Gift-card rows served from `tx_display_cache` should display the merchant logo bitmap,
matching pre-v11.8.0 behavior, in both:

- the **`RoomLive`** Paging3 path (steady state), and
- the **`_cachedRows`** fast-startup path (cold cache, before the in-memory
  `metadata` map is populated).

## Implemented Solution

> **Status: implemented** (branch `fix/crashes-anrs`). Verified on device — existing
> and new gift card rows show the merchant logo. Build: `./gradlew
> :wallet:compile_testNet3DebugKotlin` succeeds and the Room schema exports cleanly.

The bitmap itself is **not** stored in the cache table. It is re-injected at display
time from the merchant logos that already live in memory (and, for the very first
cold-start frame, from the `icon_bitmaps` table). A nullable `customIconId` column is
added to the entry, but the design is deliberately **in-memory-first**: the live path
does not depend on that column being populated, so gift card rows cached by an older
app version recover their logo on the next launch without any rebuild.

### How the bitmap reaches each read path

`observePresentableMetadata` (`WalletTransactionMetadataProvider.kt:545-572`) already
decodes **every** merchant logo into `PresentableTxMetadata.icon` and the service keeps
that map in memory as `metadata: Map<Sha256Hash, PresentableTxMetadata>`
(`TxDisplayCacheService.kt:137`). Two helpers re-inject from it:

```kotlin
// Live (RoomLive) path — non-blocking, no disk I/O. Intentionally NOT gated on
// customIconId, so rows written by an older version still get their logo from the
// in-memory map. metadata.icon is only non-null for gift cards, so other rows are
// unaffected; a group row whose rowId isn't a tx hash fails the wrap and returns null.
private fun iconBitmapForEntry(entry: TxDisplayCacheEntry): Bitmap? {
    val txId = try { Sha256Hash.wrap(entry.rowId) } catch (e: IllegalArgumentException) { return null }
    return metadata[txId]?.icon
}

// Cold-start (_cachedRows) path — runs before the metadata map is populated, so it
// falls back to a disk read by icon id, which requires the cached customIconId.
private suspend fun iconBitmapForEntryCold(entry: TxDisplayCacheEntry): Bitmap? {
    val iconId = entry.customIconId ?: return null
    iconBitmapForEntry(entry)?.let { return it }
    return try { metadataProvider.getIcon(Sha256Hash.wrap(iconId)) } catch (e: IllegalArgumentException) { null }
}
```

### Resulting behavior by scenario

| Scenario | When the merchant logo appears |
|---|---|
| **New gift-card purchase** (after upgrade) | Immediately — the row is written with `customIconId` and shows the logo on both the cold-start and live paths. |
| **Existing rows, live list** (`RoomLive`) | As soon as the live data loads on the next launch — the in-memory metadata supplies the bitmap regardless of the (null) column. No rebuild or new transaction needed. |
| **Existing rows, first cold-start frame** | Static icon for a brief moment on the *first* launch after upgrade (column still null, metadata not loaded yet), then the live path fills it in. The column is backfilled on the first metadata emission, so every later cold start is instant. |

### Change 1 — `customIconId` column on the cache entry

`wallet/.../database/entity/TxDisplayCacheEntry.kt`

- Added nullable `customIconId: String?` to `TxDisplayCacheEntry`.
- `fromTransactionRowView(row, context, filterFlags, customIconId)` gained an optional
  `customIconId` parameter (the value comes from `metadata[txId]?.customIconId` at each
  cache-write site, since `TransactionRowView` doesn't carry the id).

### Change 2 — `toTransactionRowView` accepts a bitmap

`wallet/.../database/entity/TxDisplayCacheEntry.kt`

- `toTransactionRowView(contact, iconBitmap)` gained an optional `iconBitmap: Bitmap?`
  used in place of the previously hardcoded `iconBitmap = null`.

### Change 3 — inject on the `RoomLive` path

`wallet/.../service/TxDisplayCacheService.kt`

- The Paging `map` now calls `entry.toTransactionRowView(contactsByTxId[entry.rowId],
  iconBitmapForEntry(entry))`.

### Change 4 — inject on the cold-start `_cachedRows` path

`wallet/.../service/TxDisplayCacheService.kt`

- The fast-startup build now calls
  `entry.toTransactionRowView(contacts[entry.rowId], iconBitmapForEntryCold(entry))`.

### Change 5 — late-arriving bitmap race

`wallet/.../service/TxDisplayCacheService.kt`

`PresentableTxMetadata.equals` ignores the `@Ignore` icon bitmap, so a metadata
emission that only adds/changes a merchant logo (a later `observeBitmaps` emission)
produces an empty `changedIds` and would otherwise not refresh the list. The empty
branch now calls `_currentPagingSource.value?.invalidate()` so the live pager re-maps
and picks up the newly available bitmap — without rewriting any rows.

The five `fromTransactionRowView` call sites all pass `metadata[txId]?.customIconId`,
and the metadata-merge branch preserves an existing `customIconId`
(`entry.copy(customIconId = entry.customIconId ?: existing.customIconId)`).

### Change 6 — Room migration & schema bump

- `AppDatabase` version `19 → 20`.
- `migration19to20`: `ALTER TABLE tx_display_cache ADD COLUMN customIconId TEXT`,
  registered in `DatabaseModule`.
- Exported schema `wallet/schemas/.../20.json` regenerated (contains `customIconId`).

Existing rows have `customIconId = NULL` after migration, but — per the in-memory-first
design — still show their logo on the live path; the column is backfilled on the first
metadata emission, and `rebuildIfCacheIncomplete` / `forceRebuildTransactionCache`
remain as a backstop.

## Alternatives considered

- **Store the bitmap bytes directly in `TxDisplayCacheEntry`.** Rejected: bloats every
  cache row, duplicates data already in `icon_bitmap`, and the bitmap can change.
- **Drop the cache for gift-card rows and always render live.** Rejected: defeats the
  startup-performance purpose of the cache and reintroduces the wallet-access cost on
  the hot path.

## Affected files

| File | Change |
|---|---|
| `wallet/.../database/entity/TxDisplayCacheEntry.kt` | add `customIconId` column; `fromTransactionRowView` param; `toTransactionRowView` bitmap param |
| `wallet/.../service/TxDisplayCacheService.kt` | `iconBitmapForEntry` / `iconBitmapForEntryCold` helpers; inject bitmap on the `RoomLive` map and `_cachedRows` build; invalidate pager on empty-`changedIds` metadata emission; pass `customIconId` at all five `fromTransactionRowView` call sites; preserve `customIconId` in the metadata-merge branch |
| `wallet/.../database/AppDatabaseMigrations.kt` | `migration19to20` |
| `wallet/.../di/DatabaseModule.kt` | register `migration19to20` |
| `wallet/.../database/AppDatabase.kt` | version `19 → 20` |
| `wallet/schemas/.../20.json` | regenerated schema (new, untracked — commit with the change) |

## Verification

- ✅ Build: `./gradlew :wallet:compile_testNet3DebugKotlin` — BUILD SUCCESSFUL.
- ✅ Room schema export succeeded; `20.json` contains `customIconId` on `tx_display_cache`.
- ✅ On device: merchant logos appear on the home tx list for **existing** gift-card
  purchases (next launch, no rebuild) and for **new** purchases (immediately).