# Transaction Display Performance Improvements

**Date:** 2026-02-25
**Scope:** `MainActivity`, `MainViewModel`, `WalletFragment`, `WalletTransactionsFragment`
**Goal:** Improve transaction list display speed for large wallets (thousands of transactions) with transaction metadata and DashPay platform contacts.

---

## Current Architecture

### Data Sources (5 concurrent streams)

| Source | Type | Purpose |
|--------|------|---------|
| `walletData.observeTransactions()` | Flow | Raw blockchain transactions |
| `metadataProvider.observePresentableMetadata()` | Flow | Tx metadata (memo, service, gift card, etc.) |
| `platformRepo.observeContacts()` | Flow | DashPay contacts |
| `walletData.observeTotalBalance()` | Flow | Balance display |
| `exchangeRatesProvider.observeExchangeRate()` | Flow | Fiat conversion |

### Data Flow Summary

```
Data Sources
├── walletData.observeTransactions()       → List<Transaction>          (filtered, batched 500ms)
├── metadataProvider.observePresentableMetadata() → Map<Sha256Hash, PresentableTxMetadata>
├── platformRepo.observeContacts()         → List<Contact> + DashPayProfile
├── walletData.observeTotalBalance()       → Coin
└── exchangeRatesProvider.observeExchangeRate() → ExchangeRate

        ↓ MainViewModel (viewModelWorkerScope — single thread)

Transformation Pipeline
├── refreshTransactions(filter)
│   ├── wrapAllTransactions() — CoinJoin, CrowdNode, Standard wrappers
│   ├── filter + sort all transactions
│   ├── create TransactionRowView for each
│   ├── group by LocalDate
│   └── queue contact lookups for eligible transactions
│
├── refreshTransactionBatch(batch, filter, contacts)
│   ├── update/insert individual transactions into existing map
│   └── queue contact lookups for new transactions
│
└── updateContactsAndMetadata()
    ├── merge metadata changes
    ├── associate DashPayProfile with transactions
    └── replace full _transactions map

        ↓ StateFlow<Map<LocalDate, List<TransactionRowView>>>

UI (WalletTransactionsFragment)
├── collect transactions StateFlow
├── flatten to List<HistoryRowView> with date headers
└── adapter.submitList() → DiffUtil → RecyclerView
```

### Key Implementation Details
- **Batching period:** 500ms on transaction stream updates
- **Worker scope:** Single-threaded (`viewModelWorkerScope`)
- **In-memory cache:** `txByHash: Map<String, TransactionRowView>` + grouped map held simultaneously
- **Contact time window:** Only transactions after `minContactCreatedDate` are considered for contact resolution

---

## Identified Bottlenecks

### 1. Full blocking refresh on startup
`refreshTransactions()` iterates **all transactions synchronously** — wrapping, filtering, grouping, and
creating `TransactionRowView` objects for every transaction before emitting anything to the UI. For a
wallet with thousands of transactions, the user sees a loading spinner until all processing is complete.

**Location:** `MainViewModel.kt` — `refreshTransactions()`

### 2. Per-transaction platform contact calls
`getContactsAndMetadataForTransactions()` calls `blockchainIdentity.getContactForTransaction(tx)` once
per transaction. For a wallet with thousands of transactions within the contact time window, this fans
out into potentially thousands of serial async calls.

**Location:** `MainViewModel.kt` — `getContactsAndMetadataForTransactions()` / `getContactForTransaction()`

### 3. Full state map replaced on every update
Every batch update performs `_transactions.value = items` with a full `Map<LocalDate, List<TransactionRowView>>`
copy. The adapter's `submitList()` then runs `DiffUtil` across the entire dataset — O(N) cost even for
a single-transaction update.

**Location:** `MainViewModel.kt` — `refreshTransactionBatch()`, `updateContactsAndMetadata()`

### 4. No pagination — everything in memory
All `TransactionRowView` objects live in `txByHash` and the grouped map simultaneously. For thousands
of transactions this is significant memory pressure with no lazy eviction or off-screen data release.

**Location:** `MainViewModel.kt` — `txByHash`, `_transactions`

### 5. Single-threaded worker scope
`viewModelWorkerScope` uses 1 thread of parallelism, serializing all transaction processing, metadata
updates, and contact resolution behind a single queue.

**Location:** `MainViewModel.kt` — `viewModelWorkerScope` initialization

### 6. Two-phase enrichment causes visible re-renders
Transactions display first without contact info, then `updateContactsAndMetadata()` fires a second
full-map replacement. The adapter re-diffs and re-binds the large list twice: once on initial load
and once when contacts arrive.

**Location:** `MainViewModel.kt` — `updateContactsAndMetadata()` called after initial `refreshTransactions()`

---

## Proposed Improvements

### Priority 1 — Show something fast (latency)

#### a) Paginate initial load — show first 50 transactions immediately

Instead of processing all transactions before emitting, emit the first page instantly and continue
loading in background chunks:

```kotlin
private fun refreshTransactions(filter: TxDirectionFilter) {
    walletData.wallet?.let { wallet ->
        val allWrapped = walletData.wrapAllTransactions(crowdNodeWrapperFactory, coinJoinWrapperFactory)
            .filter { it.passesFilter(filter, metadata) }
            .sortedByDescending { it.groupDate }

        // Emit first page immediately so the UI is responsive
        val firstPage = allWrapped.take(PAGE_SIZE)
        _transactions.value = firstPage.toGroupedMap()

        // Load the rest in background chunks, yielding between each
        viewModelWorkerScope.launch {
            allWrapped.drop(PAGE_SIZE).chunked(PAGE_SIZE).forEach { page ->
                delay(0) // yield to allow UI frame to render
                appendTransactions(page)
            }
            transactionsLoaded = true
        }
    }
}
```

#### b) Adopt Jetpack Paging 3

Move `wrapAllTransactions()` behind a `PagingSource` backed by the wallet's transaction store.
`RecyclerView` will only bind visible items, and a `RemoteMediator` can handle contact/metadata
enrichment as pages load. This is the most scalable long-term solution.

**Key changes:**
- Create `TransactionPagingSource : PagingSource<Int, TransactionRowView>`
- Wire through `Pager` + `cachedIn(viewModelScope)` in `MainViewModel`
- Update `WalletTransactionsFragment` to use `PagingDataAdapter` instead of `ListAdapter`

---

### Priority 2 — Reduce per-update cost (throughput)

#### c) Batch contact lookups instead of per-transaction calls

Replace the per-transaction `getContactForTransaction(tx)` loop with a single bulk resolution
against the in-memory `contacts` map:

```kotlin
private fun getContactsAndMetadataForTransactions(txs: List<Transaction>) {
    if (txs.isEmpty() || contacts.isEmpty()) return

    viewModelWorkerScope.launch {
        // Extract identity IDs from all transactions at once
        val identityIdByTxId = txs
            .filterNot { it.isEntirelySelf(walletData.transactionBag) }
            .mapNotNull { tx ->
                // Use cached identity resolution — avoid per-tx platform calls
                resolveIdentityIdFromTx(tx)?.let { tx.txId to it }
            }.toMap()

        // Single bulk lookup against the in-memory contacts map
        val contactsMap = identityIdByTxId.mapNotNull { (txId, identityId) ->
            contacts[identityId]?.let { txId to it }
        }.toMap()

        if (contactsMap.isNotEmpty()) {
            updateContactsAndMetadata(mapOf(), metadata, contactsMap)
        }
    }
}
```

This avoids calling into `blockchainIdentity` for each transaction when the contact is already cached.

#### d) Emit metadata as a diff, not a full replacement

Track which `txId`s actually changed between metadata snapshots and only recompute those rows:

```kotlin
metadataProvider.observePresentableMetadata()
    .onEach { newMetadata ->
        val oldMetadata = this.metadata
        this.metadata = newMetadata

        // Only recompute rows where metadata actually changed
        val changedIds = (newMetadata.keys - oldMetadata.keys) +
            oldMetadata.keys.filter { newMetadata[it] != oldMetadata[it] }

        updateContactsAndMetadataForIds(changedIds.toSet(), newMetadata)
    }
    .launchIn(viewModelWorkerScope)
```

#### e) Targeted adapter updates instead of full-list submitList

Maintain a flat `List<HistoryRowView>` in addition to the grouped map, and when only a single
date-bucket changes, update only the affected range:

```kotlin
fun updateBucket(date: LocalDate, newItems: List<TransactionRowView>) {
    val flatList = adapter.currentList.toMutableList()
    val bucketStart = flatList.indexOfFirst { it is HistoryRowView && it.date == date }
    val bucketEnd = flatList.indexOfLast { /* end of this date group */ }
    flatList.subList(bucketStart, bucketEnd + 1).clear()
    flatList.addAll(bucketStart, newItems.withDateHeader(date))
    adapter.submitList(flatList)
}
```

---

### Priority 3 — Architecture (scalability)

#### f) Cache computed TransactionRowView in Room

Persist the computed display representation to a Room table. On startup, serve cached rows
immediately while the blockchain sync catches up — users see full transaction history instantly,
even before the wallet finishes syncing.

**Proposed schema:**
```kotlin
@Entity(tableName = "tx_display_cache")
data class TxDisplayCacheEntry(
    @PrimaryKey val txId: String,
    val serializedRowView: String,  // JSON or Protobuf
    val dateKey: Long,              // epoch day for grouping
    val lastModified: Long
)
```

**Invalidation strategy:** Only recompute a cached row when its underlying transaction, metadata,
or contact changes.

#### g) Split display pipeline into base and enriched streams

Decouple fast transaction display from slow contact enrichment:

```kotlin
// Stream 1: fast — raw tx data, no contacts, emits immediately
private val _baseTransactions = MutableStateFlow<Map<LocalDate, List<TransactionRowView>>>(mapOf())

// Stream 2: slow — merges contacts + metadata, emits after enrichment
private val _enrichedTransactions = MutableStateFlow<Map<LocalDate, List<TransactionRowView>>>(mapOf())

// UI observes enriched, falls back to base while enrichment is in-flight
val transactions = combine(_baseTransactions, _enrichedTransactions) { base, enriched ->
    if (enriched.isNotEmpty()) enriched else base
}
```

This eliminates the visible two-phase jank where transactions appear and then re-render with contacts.

---

### Priority 4 — Reduce unnecessary work

#### h) Parallelize contact resolution with Dispatchers.IO

Replace the single-threaded `viewModelWorkerScope` for contact lookups with parallel coroutines:

```kotlin
viewModelWorkerScope.launch {
    val contactResults = txs.map { tx ->
        async(Dispatchers.IO) {
            tx.txId to platformRepo.blockchainIdentity.getContactForTransaction(tx)
        }
    }.awaitAll()
     .mapNotNull { (txId, contact) -> contact?.let { txId to it } }
     .toMap()

    updateContactsAndMetadata(mapOf(), metadata, contactResults)
}
```

#### i) Add `distinctUntilChanged` before UI collection

Prevent unnecessary re-renders when the state hasn't meaningfully changed:

```kotlin
// In WalletTransactionsFragment
viewModel.transactions
    .distinctUntilChanged()
    .onEach { transactionViews -> /* render */ }
    .launchIn(viewLifecycleOwner.lifecycleScope)
```

---

## Summary and Recommended Order of Implementation

| # | Improvement | Latency Impact | Throughput Impact | Complexity |
|---|-------------|---------------|-------------------|------------|
| a | Paginated first-page load (show 50 fast) | **High** | Low | **Low** |
| c | Batch contact lookups (bulk vs per-tx) | **High** | **High** | **Low** |
| d | Metadata diff emission (not full map) | Medium | **High** | Medium |
| i | `distinctUntilChanged` on StateFlow | Low | Medium | **Low** |
| h | Parallelize contact resolution | Medium | Medium | **Low** |
| e | Targeted adapter bucket updates | Medium | Medium | Medium |
| g | Split base/enriched pipelines | **High** | Medium | Medium |
| f | Cache TransactionRowView in Room | **High** | **High** | High |
| b | Jetpack Paging 3 integration | **High** | **High** | High |

**Recommended sequence:**
1. Start with **(a)**, **(c)**, and **(i)** — highest impact for least effort.
2. Follow with **(d)** and **(h)** — eliminate redundant work in the hot path.
3. Plan **(g)** and **(f)** as a sprint to eliminate two-phase jank and add persistence.
4. Evaluate **(b)** (Paging 3) as a longer-term architecture investment if the wallet regularly exceeds 5,000+ transactions.

❯ with each priority, 1 through 4, is it possible to work on priority 1 before 2, and 3 and 4

⏺ Yes, with a few important caveats about conflicts between items. Let me break it down:
                                                                                                                                                                                                                                                        
---                                                                                                                                                                                                                                                   
Priority 1 first — Yes, but pick (a) OR (b), not both

Items (a) (paginated initial load) and (b) (Jetpack Paging 3) are mutually exclusive. Item (a) is a stopgap fix inside the current ListAdapter architecture. Item (b) replaces that architecture entirely with PagingDataAdapter. Doing (a) and then  
(b) means throwing away (a)'s work.

Decision point before starting Priority 1:
- If Paging 3 is planned in the near term → skip (a), go straight to (b)
- If Paging 3 is a long-term investment → do (a) now for a quick win

  ---
Priority 2 after Priority 1 — Yes, mostly independent

Items (c) (batch contact lookups) and (d) (metadata diff) are fully independent — they only touch MainViewModel logic and don't care whether the UI uses a ListAdapter or PagingDataAdapter. Safe to do in any order.

Item (e) (targeted adapter bucket updates) conflicts with Paging 3 (1b). If Paging 3 is chosen, skip (e) entirely — Paging 3 handles incremental updates natively.

  ---
Priority 3 after Priority 2 — Yes, but (g) depends on pipeline stability

Item (f) (Room cache) is independent and can be done at any point.

Item (g) (split base/enriched pipelines) restructures the StateFlow pipeline that Priority 1 and 2 changes will also touch. Doing it after Priority 1 and 2 are merged means you only restructure once rather than refactoring mid-stream.

  ---
Priority 4 anytime — Fully independent

Items (h) and (i) have no dependencies. They can be done as standalone PRs at any point — even before Priority 1 if you want quick easy wins.

  ---
Conflict-free sequencing

If going with Paging 3 (1b):
P4: h, i  →  P2: c, d  →  P1: b  →  P3: g, f
(skip 1a and 2e entirely)

If skipping Paging 3 (quick-fix path):
P4: h, i  →  P1: a  →  P2: c, d, e  →  P3: g, f

The only hard rule is: don't implement both (1a) and (1b), and don't implement (2e) if you're planning (1b).