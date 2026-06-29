# NEAR Intents (1Click API) Protocol Integration Documentation

This document describes the **NEAR Intents 1Click API** — a candidate cross-chain swap backend for the Dash Wallet, evaluated alongside the existing Maya integration and the SwapKit aggregator POC (see `MAYA_PROTOCOL.md` and `SWAPKIT_PROTOCOL.md` in this directory).

> **Status:** In progress. This doc is being filled in one endpoint at a time. Endpoints marked _(TODO)_ have not yet been documented from the source pages.

## Overview

**NEAR Intents** is an intent-based cross-chain settlement system. Instead of the user constructing and signing a chain-specific transaction with routing instructions (Maya's OP_RETURN memo) or the wallet decoding a provider-built payload (SwapKit's `tx`), the user expresses an *intent* ("I want X of asset B for my Y of asset A") and a network of solvers competes to fill it. Settlement happens by the user depositing the source asset to a generated **deposit address**; the solver delivers the destination asset to the user's recipient address.

The **1Click API** is the REST front-end to NEAR Intents — it abstracts the intent/solver machinery behind a simple quote → deposit → track flow. It is the same provider SwapKit surfaces as the `NEAR` route (NEAR Intents), which in live testing was the memo-free, RECOMMENDED/CHEAPEST route for DASH swaps.

### Why this matters for the Dash Wallet

| Concern | Maya (current) | SwapKit | NEAR Intents (1Click) |
|---|---|---|---|
| Routing | Single protocol (Mayanode) | Aggregated across 15+ providers | Solver network |
| Tx construction | Client builds DASH tx + OP_RETURN memo | Server returns signable `tx` | **Plain deposit to address — no memo required** |
| Source address requirement | n/a (wallet builds tx) | `sourceAddress` mandatory on `/v3/swap` | Refund address supplied at quote time (TBD — verify) |
| DASH support | First-class chain | Via Maya only | **First-class** (`dash` in supported blockchains) |
| API key | None | `x-api-key` required | JWT (see Authentication) |

The standout property for the **buy direction** (X → DASH): NEAR Intents routes DASH natively and memo-free, which is exactly the gap that makes Maya unusable for buys. This is why it's worth documenting on its own rather than only through SwapKit's wrapper.

---

## API Endpoints

### Base URL

- **API root**: `https://1click.chaindefuser.com`
- **OpenAPI spec**: `https://1click.chaindefuser.com/docs/v0/openapi.yaml`
- **API version**: `0.1.10` (`/v0/` path prefix)

### Authentication

Most read endpoints (e.g. `/v0/tokens`) require **no authentication** (`security: []` in the OpenAPI spec). Quote/swap execution accepts **either** an `X-API-Key` header **or** a **JWT Bearer token** (`/v0/quote` documents both). Fee collection uses the JWT flow.

> _(TODO: document the JWT acquisition flow from `distribution-channels/1click-api/authentication.md` once that page is provided.)_

### Endpoint Summary

| Method | Path | Purpose | Status |
|---|---|---|---|
| GET  | `/v0/tokens` | List supported tokens across all chains | ✅ Documented below |
| POST | `/v0/quote` | Request a swap quote (assets, amount, slippage, recipient/refund) | ✅ Documented below |
| POST | `/v0/deposit/submit` | Notify the service a deposit tx was sent (by hash) | ✅ Documented below |
| GET  | `/v0/status` | Check swap execution status by deposit address + memo | ✅ Documented below |
| GET  | `/v0/any-input/withdrawals` | List withdrawals for an ANY_INPUT quote (filter/paginate/sort) | ✅ Documented below |
| GET  | `/v0/transactions` | Retrieve transaction data | _(TODO)_ |

> Exact paths for the TODO rows are placeholders inferred from the docs index (`llms.txt`) and will be corrected against each source page as it's added.

---

### 1. Get Supported Tokens

**Endpoint**: `GET https://1click.chaindefuser.com/v0/tokens`

Lists every token the 1Click API can swap, across all supported chains. Use to populate source/destination asset pickers and to map a chain + symbol to the `assetId` that quote calls require. Also carries live USD price, so it can double as a price source (like SwapKit's `/price`).

**Authentication**: None.

**Request parameters**: None (no query, path, or body).

**Response**: `200 OK`, `application/json` — an **array** of `TokenResponse` objects.

**`TokenResponse` object**:

| Field | Type | Required | Notes |
|---|---|---|---|
| `assetId` | string | yes | **Primary key** for quote calls. NEAR-native format, e.g. `nep141:wrap.near`. This is *not* the `CHAIN.ASSET` notation Maya/SwapKit use — it's the NEAR Intents asset identifier. |
| `decimals` | number | yes | Token precision (e.g. `24` for wNEAR). Authoritative for base-unit conversion. |
| `blockchain` | string (enum) | yes | Chain the token lives on (see enum below). |
| `symbol` | string | yes | Display symbol (e.g. `BTC`, `ETH`, `wNEAR`). |
| `price` | number | yes | Current USD price (e.g. `2.79`). |
| `priceUpdatedAt` | string (ISO 8601 date-time) | yes | When the price was last refreshed. |
| `contractAddress` | string | no | Token contract address (omitted for gas/native tokens), e.g. `wrap.near`. |

**Supported `blockchain` enum values**:

```
near, eth, base, arb, btc, sol, ton, dash, doge, xrp, zec, gnosis, bera,
bsc, pol, tron, sui, op, avax, cardano, ltc, xlayer, monad, bch, adi,
plasma, scroll, starknet, aleo
```

> **For the Dash Wallet**: `dash` is a first-class blockchain value — DASH is supported natively, not only as a Maya-routed asset. Find the DASH entry (filter `blockchain == "dash"`) to obtain its `assetId` for quote requests, and read `decimals` from there rather than hardcoding 8.

**Notes**:

- No rate limits documented.
- The asset identifier scheme (`nep141:…`, etc.) differs from Maya/SwapKit's `CHAIN.ASSET[-CONTRACT]`. Any shared model code with the Maya module must translate at the boundary — **do not** assume `DASH.DASH`-style strings.

---

### 2. Request a Swap Quote — `/v0/quote`

**Endpoint**: `POST https://1click.chaindefuser.com/v0/quote`

Step 1 of the swap flow. Returns pricing **and** the generated `depositAddress` in a single call (unlike SwapKit's two-step quote→swap). The response is signed by the service.

**Authentication**: `X-API-Key` header **or** JWT Bearer token.

**Request body — required fields**:

| Field | Type | Notes |
|---|---|---|
| `dry` | boolean | `true` = price-only dry run (no deposit address committed). Use for indicative quotes; set `false` to get a live deposit address. |
| `swapType` | string (enum) | `EXACT_INPUT` \| `EXACT_OUTPUT` \| `FLEX_INPUT` \| `ANY_INPUT` — how `amount` is interpreted (see below). |
| `slippageTolerance` | number | **Basis points** (100 = 1%), not percent. Contrast SwapKit's `slippage` which was whole-percent. |
| `originAsset` | string | Source `assetId` from `/v0/tokens` (e.g. `nep141:…`). |
| `destinationAsset` | string | Destination `assetId`. |
| `amount` | string | Base amount in **smallest unit, integer only, no decimals** — opposite of SwapKit/Maya which take human decimals. Use the token's `decimals` from `/v0/tokens` to scale. |
| `depositType` | string (enum) | `ORIGIN_CHAIN` \| `INTENTS` \| `CONFIDENTIAL_INTENTS` — where the deposit comes from. For an external-chain deposit (our buy case), `ORIGIN_CHAIN`. |
| `recipient` | string | Destination address (the user's DASH receive address for buys). |
| `recipientType` | string (enum) | `DESTINATION_CHAIN` \| `INTENTS` \| `CONFIDENTIAL_INTENTS`. For delivery to a normal DASH address, `DESTINATION_CHAIN`. |
| `refundTo` | string | **Refund address** — supplied at quote time. This is the recurring concern for buys (see notes). |
| `refundType` | string (enum) | `ORIGIN_CHAIN` \| `INTENTS` \| `CONFIDENTIAL_INTENTS`. |
| `deadline` | string (ISO 8601) | When the deposit address becomes inactive; must be far enough out to cover mining time. |

**Request body — optional fields**:

| Field | Type | Notes |
|---|---|---|
| `depositMode` | string (enum) | `SIMPLE` (default) \| `MEMO`. **`SIMPLE` = plain transfer, no memo** — this is the memo-free deposit that makes the buy direction viable. |
| `refundFee` | string | Refund fee in smallest unit. |
| `connectedWallets` | array | Connected wallet addresses. |
| `sessionId` | string | Client session id. |
| `virtualChainRecipient` / `virtualChainRefundRecipient` | string | EVM addresses for virtual-chain routing (not relevant to DASH). |
| `customRecipientMsg` | string | Message for `ft_transfer_call` (experimental). |
| `confidentiality` | string (enum) | `public` (default) \| `basic` \| `advanced`. |
| `referral` | string | Distribution-channel identifier (lowercase) — the Dash affiliate handle. |
| `rebates` | array | Up to 3 rebate recipients with share percentages. |
| `quoteWaitingTimeMs` | number | Ms to wait for a relay quote (default 0). |
| `appFees` | array | Recipient fee objects — where the Dash app fee is configured per-request. |

**Response — top level**:

| Field | Type | Notes |
|---|---|---|
| `correlationId` | string | Request-tracing id. |
| `timestamp` | string (ISO 8601) | Timestamp used to derive the deposit address. |
| `signature` | string | Service signature attesting the quote. |
| `quoteRequest` | object | Echo of the submitted request. |
| `quote` | object | Pricing + deposit details (below). |

**Response — `quote` object**:

| Field | Type | Notes |
|---|---|---|
| `depositAddress` | string | Address on the origin chain (or Intents verifier contract) the user deposits into. **For DASH-as-destination buys, this is the address on the *source* chain.** |
| `depositMemo` | string | Memo, only when `depositMode: MEMO` was requested or the chain requires it. Absent for `SIMPLE`. |
| `amountIn` / `amountInFormatted` / `amountInUsd` | string | Input amount (smallest unit / human / USD). |
| `minAmountIn` | string | Minimum input for execution. |
| `amountOut` / `amountOutFormatted` / `amountOutUsd` | string | **Expected output** (smallest unit / human / USD) — for buys, the DASH the user receives. |
| `minAmountOut` | string | Output floor after slippage — the guaranteed minimum. |
| `deadline` | string (ISO 8601) | When the deposit address goes inactive. |
| `timeWhenInactive` | string (ISO 8601) | When the address goes "cold" (still works, slower processing). |
| `timeEstimate` | number | Seconds to execute after the deposit confirms. |
| `refundFee` | string | Refund fee in smallest unit. |
| `virtualChainRecipient` / `virtualChainRefundRecipient` / `customRecipientMsg` | string | Echoed virtual-chain / experimental fields. |

**`swapType` semantics** (directly relevant to the buy-flow "user sent the wrong amount" problem):

- **`EXACT_INPUT`** — fixed input, variable output; **excess deposit is refunded**.
- **`EXACT_OUTPUT`** — fixed output, input adjusted within slippage; **surplus refunded**.
- **`FLEX_INPUT`** — partial deposits accepted; valid range bounded by slippage on both sides.
- **`ANY_INPUT`** — accepts whatever is deposited (see the ANY_INPUT withdrawals endpoint). This is the mode that removes the fixed-amount-deposit failure mode: the user can send an arbitrary amount and it's swapped at execution.

> **Buy-flow implication**: `ANY_INPUT` (or `FLEX_INPUT`) is the answer to a long-standing concern — with a human paying from an external wallet, the deposited amount rarely matches a quote to the satoshi. These modes let the swap proceed (or refund the excess) instead of failing outright.

---

### 3. Submit Deposit Transaction Hash — `/v0/deposit/submit`

**Endpoint**: `POST https://1click.chaindefuser.com/v0/deposit/submit`

**Optional but recommended.** Tells the service the deposit tx hash so it can proactively verify and start processing instead of waiting to discover the deposit by polling the chain. Omitting it does **not** block the swap — it just makes detection slower.

**Authentication**: `X-API-Key` (recommended) **or** JWT Bearer (legacy).

**Request body**:

| Field | Type | Required | Notes |
|---|---|---|---|
| `txHash` | string | **yes** | Hash of the deposit transaction. |
| `depositAddress` | string | **yes** | The quote's `depositAddress`. |
| `nearSenderAccount` | string | no | Sender account when the deposit originates on NEAR (e.g. `relay.tg`). Not relevant for an external-chain (e.g. BTC) deposit. |
| `memo` | string | no | Deposit memo, only if one was issued (`depositMode: MEMO`). |

**Response**: **identical shape to `/v0/status`** — `{ correlationId, status, updatedAt, quoteResponse, swapDetails }` with the same `status` enum and `swapDetails`/`TransactionDetails` sub-objects (see §4). In effect this is "submit the hash *and* get the current status back in one call."

**Errors**:

| HTTP | Meaning |
|---|---|
| `400` | `{ "message": "…" }` — malformed `txHash`/`depositAddress` or missing required field. |
| `401` | Invalid API key / JWT. |
| `404` | Not in the spec, but possible if the deposit/correlation id isn't found. |

> **Where this fits the buy flow**: For a buy paid from an *external* wallet, the wallet usually won't have the user's source-chain `txHash` — so this call is typically **skipped** for buys, and we rely on `/v0/status` polling (which discovers `originChainTxHashes` on its own). It's only useful when the wallet itself broadcast the deposit (i.e. the **sell** direction, DASH → X) and therefore knows the hash — there it's worth calling to cut detection latency.

---

### 4. Check Swap Execution Status — `/v0/status`

**Endpoint**: `GET https://1click.chaindefuser.com/v0/status`

Poll this with the `depositAddress` from the quote to track a swap end-to-end. **Tracking works by deposit address alone** (plus memo only when one was issued) — the same convenient property SwapKit's `/track` had, and exactly what the buy flow needs since the wallet knows the deposit address but not the user's source-chain tx hash.

**Authentication**: `X-API-Key` (recommended) **or** JWT Bearer (legacy).

**Request — query parameters**:

| Parameter | Type | Required | Notes |
|---|---|---|---|
| `depositAddress` | string | **yes** | The `quote.depositAddress` returned by `/v0/quote`. |
| `depositMemo` | string | no | Required **only** if the quote issued a `depositMemo` (i.e. `depositMode: MEMO`). For `SIMPLE` deposits, omit. |

**Response — top level**:

| Field | Type | Notes |
|---|---|---|
| `correlationId` | string (UUID) | Request-tracing id. |
| `status` | string (enum) | Lifecycle state (below). |
| `updatedAt` | string (ISO 8601) | Last state change. |
| `quoteResponse` | object | Full echo of the original `/v0/quote` response, including the `signature` — **retain client-side for dispute resolution**. |
| `swapDetails` | object | Execution detail (below). |

**`status` enum**:

| Value | Meaning |
|---|---|
| `PENDING_DEPOSIT` | Waiting for the user's deposit to arrive. |
| `KNOWN_DEPOSIT_TX` | Deposit tx seen (e.g. via `/v0/deposit/submit`) but not yet confirmed/processed. |
| `INCOMPLETE_DEPOSIT` | A deposit arrived but is short of the required amount (relevant to `FLEX_INPUT`/partial). |
| `PROCESSING` | Deposit accepted; solver executing the swap. |
| `SUCCESS` | Destination asset delivered to `recipient`. |
| `REFUNDED` | Funds returned to `refundTo` (see `refundReason`). |
| `FAILED` | Swap failed. |

> No `EXPIRED` state is listed — an unfunded address presumably stays `PENDING_DEPOSIT` past `deadline`. Confirm behavior of a deposit that lands *after* `deadline`/`timeWhenInactive`.

**`swapDetails` object**:

| Field | Type | Req | Notes |
|---|---|---|---|
| `intentHashes` | string[] | yes | All NEAR-Intents intent hashes for this swap. |
| `nearTxHashes` | string[] | yes | NEAR transactions executed. |
| `amountIn` / `amountInFormatted` / `amountInUsd` | string | yes | Exact input (smallest unit / human / USD). |
| `amountOut` / `amountOutFormatted` / `amountOutUsd` | string | yes | Exact output delivered — for buys, the DASH received. |
| `slippage` | number | yes | **Actual** slippage % realized (vs. the requested tolerance). |
| `originChainTxHashes` | `TransactionDetails[]` | yes | Source-chain tx hash(es) + explorer URL — i.e. **the user's deposit tx, discovered for us**. |
| `destinationChainTxHashes` | `TransactionDetails[]` | yes | Destination-chain (DASH) delivery tx + explorer URL. |
| `depositedAmount` / `…Formatted` / `…Usd` | string | no | What actually landed on-chain (may differ from quote). |
| `refundedAmount` / `…Formatted` / `…Usd` | string | no | Amount returned to `refundTo`. |
| `refundReason` | string | no | e.g. `"PARTIAL_DEPOSIT"`. |
| `referral` | string | no | Referral identifier echoed back. |

**`TransactionDetails` sub-object**: `{ hash: string, explorerUrl: string }` — both required. The `explorerUrl` is ready-made for a "view on explorer" deep link, no per-chain URL templating needed.

**Errors**:

| HTTP | Meaning |
|---|---|
| `401` | Invalid API key / JWT. |
| `404` | `{ "message": "…" }` — deposit address does not exist. |

> **Buy-flow win**: `originChainTxHashes` means the wallet learns the user's source-chain deposit tx *without the user pasting it* — solving the awkward tracking gap noted for SwapKit. Poll `/v0/status` by deposit address; surface `SUCCESS` (with `destinationChainTxHashes` for the incoming DASH) or `REFUNDED` (with `refundReason` + `refundedAmount`).

---

### 5. Get ANY_INPUT Withdrawals — `/v0/any-input/withdrawals`

**Endpoint**: `GET https://1click.chaindefuser.com/v0/any-input/withdrawals`

Companion to `/v0/status` **specifically for `ANY_INPUT` quotes**. Because an `ANY_INPUT` deposit address accepts *any* amount and can be funded **multiple times**, a single swap state (as `/v0/status` returns) isn't enough — each deposit produces its own withdrawal to the recipient. This endpoint lists those individual withdrawals, with filtering/pagination/sorting.

> **When you need it**: only if the wallet uses `swapType: ANY_INPUT`. For `EXACT_INPUT` buys (one deposit, one payout) `/v0/status` is sufficient. `ANY_INPUT` is attractive for a "reusable DASH top-up address" UX (send any coin, any number of times, receive DASH each time) — this endpoint is how you'd reconcile those payouts.

**Authentication**: `X-API-Key` (recommended) **or** JWT Bearer (legacy).

**Request — query parameters**:

| Parameter | Type | Required | Notes |
|---|---|---|---|
| `depositAddress` | string | **yes** | The `ANY_INPUT` quote's deposit address to list withdrawals for. |
| `depositMemo` | string | no | Memo, if the deposit address required one. |
| `timestampFrom` | string (ISO 8601) | no | Only withdrawals at/after this time. |
| `page` | number | no | Page number, default `1`. |
| `limit` | number | no | Per page, **default 50, max 50**. |
| `sortOrder` | string (enum) | no | `asc` \| `desc`. |

**Response — root object**:

| Field | Type | Notes |
|---|---|---|
| `asset` | string | Destination `assetId` being delivered (for buys, DASH's id). |
| `recipient` | string | Recipient wallet address. |
| `affiliateRecipient` | string | Affiliate recipient address. |
| `withdrawals` | `AnyInputQuoteWithdrawal[]` | The individual payouts (below). |

> The docs describe `withdrawals` as a single object but it is the list of payouts; treat it as an array. Pagination metadata fields (total/totalPages) are not documented explicitly — paginate via `page`/`limit` and stop when a short page returns. _(Confirm against a live response.)_

**`AnyInputQuoteWithdrawal` object**:

| Field | Type | Notes |
|---|---|---|
| `status` | string (enum) | `SUCCESS` \| `FAILED` (per-withdrawal, narrower than `/v0/status`'s lifecycle enum). |
| `amountOut` / `amountOutFormatted` / `amountOutUsd` | string | Payout amount (smallest unit / human / USD). |
| `withdrawFee` / `withdrawFeeFormatted` / `withdrawFeeUsd` | string | Per-withdrawal fee (smallest unit / human / USD). |
| `timestamp` | string (ISO 8601) | When the withdrawal occurred. |
| `hash` | string | Destination-chain tx hash of the payout. |

**Errors**:

| HTTP | Meaning |
|---|---|
| `401` | Invalid API key / JWT. |
| `404` | Deposit address not found (`BadRequestResponse` with `message`). |

---

## Detecting Unavailable / Offline / Halted Assets

> Findings below were verified live against the public API on 2026-06-08 (no auth required for `/v0/tokens` or `dry` quotes).

Unlike SwapKit — where a halt, a no-liquidity condition, and an amount-too-small all collapse into the same generic `noRoutesFound` (see `SWAPKIT_PROTOCOL.md` → "how can we tell if trading is halted") — 1Click lets you **distinguish unsupported from temporarily-unavailable from out-of-range**, via two layers.

### Layer 1 — Permanently unsupported (static)

`/v0/tokens` carries **no** `enabled` / `available` / `halted` flag — only static metadata. The single static signal is **presence in the list**:

- Build the asset picker from `/v0/tokens`; an asset absent from the list is unsupported, full stop.
- If a quote is attempted with an unlisted `assetId`, the quote rejects with a specific message:

| Condition | HTTP | `message` |
|---|---|---|
| Origin asset not in token list | 400 | `tokenIn is not valid` |
| Destination asset not in token list | 400 | `tokenOut is not valid` |

### Layer 2 — Temporarily unavailable / offline (dynamic)

An asset can be listed in `/v0/tokens` yet have **no solver/liquidity to fill the swap right now**. Probe this with a **`dry: true` quote** (free, no deposit address committed, no auth). The `message` on a `400` disambiguates the cause:

| Condition | HTTP | `message` | Interpretation |
|---|---|---|---|
| Healthy pair | 200 | _(returns `quote` with `amountOut`)_ | Swappable now |
| No solver/liquidity for the pair (or amount too large to fill) | 400 | `No liquidity available` | **The closest thing to "offline / halted"** — asset is supported but not fillable right now |
| Amount below the bridge minimum | 400 | `Amount is too low for bridge, try at least <minBaseUnits>` | **Not** unavailable — just under-minimum; the message gives the exact threshold so the UI can guide the user |
| Amount far below minimum (dust) | 400 | `Failed to get quote` | Effectively under-minimum / unquotable |
| Recipient malformed for the destination chain | 400 | `recipient is not valid` | Caller error, not availability |

**So `dry: true` is the live availability probe.** A `200` means swappable this moment; a `400` tells you *why not* in an actionable way:
- `No liquidity available` → treat as "temporarily offline" for that asset/pair (banner: "Swaps for X are temporarily unavailable"). This is the signal SwapKit could not give us.
- `Amount is too low…` → do **not** mark the asset unavailable; show the minimum and let the user raise the amount.

### Caveats

1. **Errors are free-text `message` strings, not codes.** The 400 body is just `{ "message": "..." }` — there is no machine-readable error-code field. Substring matching (`"No liquidity"`, `"too low"`, `"not valid"`) is fragile to wording changes: always keep a generic fallback, and re-confirm the strings against the OpenAPI spec before hardcoding.
2. **Availability is per-`assetId`, not per-coin.** A coin may have multiple representations (e.g. BTC as both `nep141:btc.omft.near` and `1cs_v1:btc:native:coin`); one can have liquidity while the other does not. Probe the specific `assetId` you intend to use (prefer the canonical `nep141:…omft.near` family — that is what quoted successfully for DASH↔BTC).
3. **Under-minimum ≠ unavailable.** DASH has a real bridge minimum (observed ≈ `14574142` base units ≈ 0.146 DASH in a DASH→BTC test); small swaps are rejected as under-minimum, not as offline — surface them differently.
4. **No bulk status endpoint and no global "all trading halted" flag** (verified against the OpenAPI spec, 2026-06-08). The full spec exposes only `/v0/auth/*`, `/v0/account/balances`, `/v0/tokens`, `/v0/quote`, `/v0/status`, `/v0/any-input/withdrawals`, `/v0/deposit/submit` — there is **no** `/v0/chains` / `/v0/health` / status-all endpoint, the spec contains zero `halt`/`paused`/`health`/`disabled` terms, and `/v0/tokens` carries no availability flag (and ignores `?available=…`). This is the key difference from Maya, which returns per-chain `halted` plus a global `HALTTRADING` in a single `inbound_addresses`/`mimir` call (see `SWAPKIT_PROTOCOL.md`). On 1Click, "which coins are offline right now?" can only be answered by **fanning out one `dry` quote per asset** (O(N) calls) and treating `No liquidity available` as offline — there is no O(1) bulk query. Mitigation: cache `/v0/tokens` for the static list and probe assets lazily on selection, or run a periodic background sweep if a full availability map is ever needed.

---

## Asset Notation

Unlike Maya/SwapKit (`CHAIN.ASSET[-CONTRACT]`), 1Click uses **NEAR Intents asset identifiers**:

- `nep141:wrap.near` (wNEAR)
- _(further examples to be added as observed from `/v0/tokens` and quote docs)_

The `assetId` returned by `/v0/tokens` is the canonical key for quote calls — never construct it by hand from a symbol. A chain + symbol → `assetId` lookup table built from `/v0/tokens` is the safe approach.

---

## Implementation Notes (for the Dash Wallet)

- **DASH is native.** No Maya dependency for routing means the global-Maya-halt blind spot documented in `SWAPKIT_PROTOCOL.md` may not apply the same way here — though NEAR Intents' own solver availability becomes the new "is it up?" question (to be investigated once status/quote endpoints are documented).
- **Memo-free deposits** make the buy direction (X → DASH) viable from an external wallet — the core blocker for Maya buys.
- **Asset-ID translation** is the main integration friction: the existing Maya/SwapKit DTOs assume `CHAIN.ASSET` strings; 1Click uses `nep141:…`-style IDs and per-token `decimals`.
- **Refund address handling** (the recurring theme for buys) needs confirmation from the quote endpoint — whether it's supplied at quote time and how ownership/validation is treated.

---

## Open Questions

1. **Refund address** — _Partially answered._ Supplied at quote time via `refundTo` + `refundType` (required). Still to confirm: validation/ownership rules, and refund behavior under each `swapType`. Note this is *cleaner than SwapKit*: refund is an explicit first-class field, and `EXACT_INPUT`/`EXACT_OUTPUT` explicitly refund excess/surplus.
2. **Quote / deposit-address lifetime** — _Answered._ Controlled by the client via the `deadline` request field; the response also returns `timeWhenInactive` (address goes "cold"/slower) distinct from `deadline` (fully inactive). We choose the window, within service limits (TBD).
3. **JWT auth** — where does the token / API key live (in-app, proxied, remote config)? Same key-management concern as Uphold/Coinbase/SwapKit. 1Click accepts either `X-API-Key` or JWT.
4. **Fees** — _Partially answered._ Per-request via `appFees[]` (recipient fee objects) and `referral` (distribution channel); `rebates[]` allows up to 3 split recipients. Still to document: exact `appFees` object shape and any service/solver fee taken implicitly. (See `fee-config.md`.)
5. **`ANY_INPUT` mode** — _Answered._ Yes. `ANY_INPUT` accepts any deposited amount; `FLEX_INPUT` accepts partials within slippage; `EXACT_INPUT` refunds the excess. Any of these defuses the "user sent a different amount than quoted" failure mode — a major advantage over fixed-amount deposit flows for the buy direction. `ANY_INPUT` addresses are also **reusable / multi-deposit**, with each payout reconciled via `/v0/any-input/withdrawals` (enables a "reusable DASH top-up address" UX). Trade-off: `/v0/status` alone no longer captures the full picture for `ANY_INPUT` — the wallet must also poll the withdrawals endpoint.
6. **Status semantics** — _Answered._ `/v0/status` tracks **by deposit address alone** (memo only if one was issued). Enum: `PENDING_DEPOSIT` → `KNOWN_DEPOSIT_TX` → `PROCESSING` → `SUCCESS` / `REFUNDED` / `FAILED`, plus `INCOMPLETE_DEPOSIT` for shorts. Refunds carry `refundReason` (e.g. `PARTIAL_DEPOSIT`) and `refundedAmount`. The response also surfaces the user's deposit tx (`originChainTxHashes`) and the DASH delivery tx (`destinationChainTxHashes`), each with a ready `explorerUrl`. Still to confirm: behavior of a deposit landing after `deadline`/`timeWhenInactive` (no explicit `EXPIRED` state).
7. **`dry` quotes** — _Answered._ `dry: true` returns a full `quote` with `amountOut`/`minAmountOut` and **no** deposit address committed; no auth required. Safe to call on amount/asset change for live UI preview.
8. **Detecting offline/halted assets** — _Answered._ See "Detecting Unavailable / Offline / Halted Assets" above. No availability flag on `/v0/tokens`; use list-membership (static) + a `dry` quote whose `400` message (`No liquidity available` vs `Amount is too low…` vs `tokenIn/Out is not valid`) classifies the cause. No protocol-wide halt endpoint exists.

---

## Official Documentation

- **Get Supported Tokens**: https://docs.near-intents.org/api-reference/oneclick/get-supported-tokens
- **Request a Swap Quote**: https://docs.near-intents.org/api-reference/oneclick/request-a-swap-quote
- **Check Swap Execution Status**: https://docs.near-intents.org/api-reference/oneclick/check-swap-execution-status
- **Submit Deposit Transaction Hash**: https://docs.near-intents.org/api-reference/oneclick/submit-deposit-transaction-hash
- **Get ANY_INPUT Withdrawals**: https://docs.near-intents.org/api-reference/oneclick/get-any_input-withdrawals
- **1Click API Overview**: https://docs.near-intents.org/distribution-channels/1click-api/about-1click-api
- **Quickstart**: https://docs.near-intents.org/distribution-channels/1click-api/quickstart
- **Authentication**: https://docs.near-intents.org/distribution-channels/1click-api/authentication
- **Fee Configuration**: https://docs.near-intents.org/distribution-channels/1click-api/fee-config
- **Swap SDK**: https://docs.near-intents.org/distribution-channels/1click-api/sdk
- **OpenAPI Spec**: https://1click.chaindefuser.com/docs/v0/openapi.yaml

## References

- NEAR Intents docs: https://docs.near-intents.org/
- Surfaced via SwapKit as the `NEAR` provider — see `SWAPKIT_PROTOCOL.md`.
