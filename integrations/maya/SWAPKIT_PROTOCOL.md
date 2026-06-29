# SwapKit Protocol Integration Documentation

This document describes the SwapKit Protocol API — a candidate alternative/complement to the Maya integration for cross-chain swaps in the Dash Wallet.

## Overview

SwapKit is a cross-chain swap aggregator that routes swaps across multiple liquidity providers (THORChain, MAYAChain, Chainflip, 1inch, Uniswap, Jupiter, PancakeSwap, etc.) behind a single REST API. Where the existing Maya integration talks directly to a single protocol (`mayanode`), SwapKit performs price discovery across many providers and returns ranked routes (`RECOMMENDED`, `FASTEST`, `CHEAPEST`).

Key differences vs. the Maya integration:

| Aspect | Maya (current) | SwapKit |
|---|---|---|
| Routing | Single protocol (Mayanode) | Aggregated across 15+ providers |
| Quote endpoint | `GET /quote/swap` (idempotent) | `POST /v3/quote` (returns `routeId`) |
| Tx construction | Client builds DASH tx with OP_RETURN memo | Server returns ready-to-sign `tx` payload (PSBT / EVM / Cosmos / TRON) |
| API key | None | `x-api-key` header required |
| AML screening | None | Address screening on every `/v3/swap` |
| Asset notation | `CHAIN.ASSET[-CONTRACT]` | Same notation |

## API Endpoints

### Base URLs

- **API root**: `https://api.swapkit.dev/`
- **v3 (price discovery + execution)**: `https://api.swapkit.dev/v3/`
- **Tracker UI**: `https://track.swapkit.dev/?hash={txHash}`
- **Dashboard (API key registration)**: `https://dashboard.swapkit.dev/`

### Authentication

All endpoints require the header:

```
x-api-key: <YOUR_API_KEY>
```

The key is also what binds requests to the partner's affiliate fee/address configuration in the dashboard.

### Endpoint Summary

| Method | Path | Purpose |
|---|---|---|
| GET  | `/providers` | List all aggregated swap providers and their supported chains |
| GET  | `/tokens?provider=NAME` | List supported tokens for a given provider |
| GET  | `/swapTo?sellAsset=…` | Discover what assets a given asset can be swapped **to** |
| GET  | `/swapFrom?buyAsset=…` | Discover what assets can be swapped **into** a given asset |
| POST | `/v3/quote` | Get ranked routes with `routeId` (no transaction data) |
| POST | `/v3/swap` | Build the signable transaction for a chosen `routeId` |
| POST | `/track` | Query swap status by tx hash + chain or by deposit address |
| POST | `/price` | Token price lookup (USD + CoinGecko metadata) |

---

### 1. Providers

**Endpoint**: `GET https://api.swapkit.dev/providers`

Returns a list of every swap provider SwapKit aggregates. Use to discover which providers can handle DASH (currently MAYACHAIN / MAYACHAIN_STREAMING).

**Response** (array of):

- `name`: Provider identifier (e.g. `MAYACHAIN`, `THORCHAIN_STREAMING`, `CHAINFLIP`)
- `provider`: Provider reference name
- `keywords`: Array of keywords
- `count`: Number of supported tokens
- `logoURI`: Provider logo URL
- `url`: URL to the provider's full token list
- `supportedActions`: Array of actions (e.g. `["swap"]`)
- `supportedChainIds`: Array of chain IDs (numeric for EVM, e.g. `"1"`, `"42161"`; named for non-EVM, e.g. `"bitcoin"`, `"solana"`)

**Known providers**: THORCHAIN, THORCHAIN_STREAMING, CHAINFLIP, CHAINFLIP_STREAMING, MAYACHAIN, MAYACHAIN_STREAMING, NEAR, ONEINCH, PANCAKESWAP, TRADERJOE_V2, UNISWAP_V2, UNISWAP_V3, CAVIAR_V1, JUPITER, CAMELOT_V3.

> "Streaming" providers split a swap over multiple sub-swaps for better effective price on larger orders.

---

### 2. Tokens

**Endpoint**: `GET https://api.swapkit.dev/tokens?provider={NAME}`

Returns the token list supported by a single provider. Necessary to know what `identifier` strings a given provider will accept in `/v3/quote`.

**Response**:

```json
{
  "provider": "MAYACHAIN",
  "name": "MAYACHAIN",
  "timestamp": "2025-01-11T16:31:04.355Z",
  "version": { "major": 1, "minor": 0, "patch": 0 },
  "keywords": [],
  "count": 10,
  "tokens": [ { ...token... } ]
}
```

**Token object**:

- `chain`: Blockchain identifier (e.g. `BTC`, `DASH`, `ETH`, `SOL`)
- `address`: Contract address (omitted for gas tokens)
- `chainId`: Chain ID (numeric for EVM, named otherwise)
- `ticker`: Symbol (e.g. `DASH`, `USDC`)
- `identifier`: **Primary key** for `/v3/quote` calls — e.g. `DASH.DASH`, `ETH.USDC-0xA0b86991…`
- `symbol`: Symbol with address info
- `name`: Display name
- `decimals`: Decimal precision
- `logoURI`: Token logo URL
- `coingeckoId`: CoinGecko identifier (when available)

---

### 3. Swap Discovery — `/swapTo` and `/swapFrom`

Lightweight endpoints for populating UI selectors.

**`GET /swapTo?sellAsset={identifier}`** → array of identifiers buyable from the given asset.
**`GET /swapFrom?buyAsset={identifier}`** → array of identifiers that can be sold to receive the given asset.

> Note the inversion: `/swapFrom` takes `buyAsset` (not `sellAsset`).

Both return `string[]` — flat arrays of identifiers like `"BTC.BTC"`, `"ETH.USDC-0X…"`. Lists are long for ERC-20 tokens because they aggregate every provider.

---

### 4. Quote — `/v3/quote`

**Endpoint**: `POST https://api.swapkit.dev/v3/quote`

Step 1 of the swap flow. Returns ranked routes; **no transaction data**.

**Request body**:

| Field | Type | Required | Notes |
|---|---|---|---|
| `sellAsset` | string | yes | e.g. `"DASH.DASH"` |
| `buyAsset` | string | yes | e.g. `"BTC.BTC"` |
| `sellAmount` | string | yes | Decimal amount as string (e.g. `"0.1"`, not base units) |
| `slippage` | number | no | Max acceptable slippage % (e.g. `3`) |
| `sourceAddress` | string | no | Enables partial address screening at quote time |
| `destinationAddress` | string | no | Same |
| `providers` | string[] | no | Restrict to specific providers; omit for all |
| `affiliateFee` | number | no | Override in basis points, 0–1000 (max 10%) |
| `cfBoost` | boolean | no | Enable Chainflip boost |
| `maxExecutionTime` | number | no | Drop routes slower than this (seconds) |

**Response**:

```jsonc
{
  "quoteId": "uuid",
  "routes": [
    {
      "routeId": "uuid",                             // valid 60s, kept warm 5min
      "providers": ["MAYACHAIN_STREAMING"],
      "sellAsset": "DASH.DASH",
      "buyAsset": "BTC.BTC",
      "sellAmount": "0.1",
      "expectedBuyAmount": "0.00057",
      "expectedBuyAmountMaxSlippage": "0.00055",
      "fees": [ /* inbound, network, affiliate, service, outbound, liquidity */ ],
      "estimatedTime": { "inbound": 60, "swap": 10, "outbound": 600, "total": 670 },
      "totalSlippageBps": 35.0,
      "legs": [ /* per-step detail */ ],
      "warnings": [],
      "meta": {
        "assets": [ { "asset": "DASH.DASH", "price": 30.5, "image": "…" }, … ],
        "tags": ["RECOMMENDED"]   // or "FASTEST" / "CHEAPEST"
      },
      "nextActions": {
        "method": "POST",
        "url": "/swap",
        "payload": { "routeId": "…" }
      }
    }
  ],
  "providerErrors": [ { "provider": "...", "errorCode": "...", "message": "..." } ],
  "error": null
}
```

**Tags** (`meta.tags`):

- `RECOMMENDED` — best output/speed tradeoff (scoring formula: `outputScore × outputWeight + timeScore × timeWeight`)
- `CHEAPEST` — maximum output
- `FASTEST` — shortest `estimatedTime.total`

**Errors** (top-level `error` field):

| Code | HTTP | Meaning |
|---|---|---|
| `noRoutesFound` | 404 | No path between assets |
| `blackListAsset` | 400 | Asset blacklisted |
| `apiKeyInvalid` | 401 | Bad/missing key |
| `unauthorized` | 401 | Auth failure |
| `invalidRequest` | 400 | Body malformed |

---

### 5. Swap — `/v3/swap`

**Endpoint**: `POST https://api.swapkit.dev/v3/swap`

Step 2. Validates balance + AML and returns a signable transaction.

**Request body**:

| Field | Type | Required | Notes |
|---|---|---|---|
| `routeId` | string | yes | From a `/v3/quote` response. **Older than 60s → quote auto-refreshed; older than 5min → not cached, returns `swapRouteNotFound`** |
| `sourceAddress` | string | yes | Sending address (for sell asset's chain) |
| `destinationAddress` | string | yes | Receiving address (for buy asset's chain) |
| `disableBuildTx` | boolean | no | Skip building the signable tx |
| `disableBalanceCheck` | boolean | no | Skip on-chain balance check (default false) |
| `disableEstimate` | boolean | no | Skip on-chain gas estimation |
| `allowSmartContractSender` | boolean | no | Allow source as contract |
| `allowSmartContractReceiver` | boolean | no | Allow destination as contract |
| `disableSecurityChecks` | boolean | no | Bypass address format/security checks |
| `overrideSlippage` | boolean | no | Bypass the 5% deviation guard if quote refreshed |

**Response** (extends quote route):

| Field | Type | Notes |
|---|---|---|
| `swapId` | string | UUID of this swap response |
| `providers`, `sellAsset`, `buyAsset`, `sellAmount` | … | Echoed |
| `expectedBuyAmount`, `expectedBuyAmountMaxSlippage` | string | Refreshed pricing |
| `tx` | varies by chain | See below |
| `approvalTx` | object | EVM ERC-20 approval tx if needed (else absent) |
| `targetAddress` | string | Vault / contract / channel to deposit into |
| `inboundAddress` | string | Address being monitored for the deposit |
| `memo` | string | Routing instruction (e.g. THORChain `=:b:bc1q…` style) |
| `fees`, `estimatedTime`, `legs`, `warnings`, `meta`, `nextActions` | … | As in quote |
| `txType` | string | `"PSBT"`, `"EVM"`, etc. (also under `meta.txType`) |

**`tx` payload by source chain**:

| Chain family | Format |
|---|---|
| EVM (ETH, ARB, BSC, AVAX, BASE, …) | Ethers v6-style object: `{ to, from, gas, gasPrice, value, data }` |
| UTXO (BTC, BCH, LTC, DOGE) | Base64-encoded PSBT |
| ZCash | Base64 PSBT (BitGoJS UtxoPsbt) by default; unsigned PCZT on request |
| TRON | TronWeb `TransactionBuilder` object |
| Cosmos (THOR, MAYA) | Native Cosmos transaction object |
| **DASH** | **Not documented as a SwapKit-source chain — DASH appears as a destination via Maya, but check `/tokens?provider=MAYACHAIN` to confirm whether SwapKit accepts DASH as `sellAsset`. If so, the format is most likely PSBT (UTXO).** |

> **Important for the Dash Wallet**: DASH-as-source through SwapKit needs verification. Maya treats DASH as a first-class chain; Chainflip and THORChain do not. If SwapKit only routes DASH via Maya, the `tx` payload may bottom out at Maya's familiar pattern (vault deposit + OP_RETURN memo) — but the PSBT/encoding question must be answered before any client work.

**SLIP-0024 signing**: optionally available; contact SwapKit to enable signed payload verification.

**Errors**:

| Code | HTTP | Meaning |
|---|---|---|
| `swapRouteNotFound` | 404 | `routeId` expired (>5min) or invalid |
| `isSanctionedAddress` | 400 | Address flagged by Chainalysis/Elliptic |
| `apiKeyInvalid` / `unauthorized` | 401 | Bad/missing key |
| `insufficientBalance` | 400 | Source lacks the sell amount |
| `insufficientAllowance` | 400 | EVM token needs approval first (use `approvalTx`) |
| `unableToBuildTransaction` | — | Balance present but can't cover network fees |
| `invalidSourceAddress` / `invalidDestinationAddress` | 400 | Format / SC / security failure |
| `outputAmountDeviationTooHigh` | 400 | Refreshed quote diverged >5%; pass `overrideSlippage` to ignore |
| `noRoutesFound` | 404 | Liquidity dried up between quote and swap |

**Latency note**: `/v3/swap` is materially slower than `/v3/quote` because it fetches UTXOs, builds the tx, runs balance check, and runs full address screening. NEAR/Chainflip deposit-channel opening can add ~2.5s.

---

### 6. Track — `/track`

**Endpoint**: `POST https://api.swapkit.dev/track`

**Request body** — at least one identifier required:

| Field | Type | Notes |
|---|---|---|
| `hash` | string | Tx hash (must be paired with `chainId`) |
| `chainId` | string | Chain ID matching the hash |
| `depositAddress` | string | NEAR Intents alternative to hash+chainId |

**Response** (top level + a `legs[]` of the same shape for cross-chain stages):

| Field | Type | Notes |
|---|---|---|
| `chainId`, `hash`, `block` | … | Tx coordinates |
| `type` | string | `swap`, `token_transfer`, … |
| `status` | enum | `not_started` / `pending` / `swapping` / `completed` / `refunded` / `unknown` / `failed` |
| `trackingStatus` | enum | **Deprecated** — use `status` |
| `fromAsset`, `fromAmount`, `fromAddress` | … | Source side |
| `toAsset`, `toAmount`, `toAddress` | … | Destination side |
| `finalisedAt` | number | UNIX seconds |
| `meta.provider`, `meta.providerAction`, `meta.images` | … | Branding for UI |
| `payload.memo` | string | Routing memo |
| `payload.evmCalldata` | string? | Present for EVM-driven swaps |
| `payload.thorname` | string? | Present for THORName usage |
| `legs[]` | array | Each leg has the same shape; represents inbound vs outbound chain |

> Use the hosted UI as a fallback / deep link: `https://track.swapkit.dev/?hash={hash}`.

---

### 7. Price — `/price`

**Endpoint**: `POST https://api.swapkit.dev/price`

Batch USD price + CoinGecko metadata lookup. Useful for the wallet's price display, fiat amount preview, and 24h change badges — and as an alternative to the current Maya client-side USD pool derivation.

**Request body**:

```json
{
  "tokens": [
    { "identifier": "DASH.DASH" },
    { "identifier": "BTC.BTC" },
    { "identifier": "ETH.USDC-0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48" }
  ],
  "metadata": true
}
```

| Field | Type | Notes |
|---|---|---|
| `tokens` | array | Required; each item is `{ "identifier": "..." }` |
| `metadata` | boolean | Documented but currently always-included regardless of value |

**Response** (array, one per requested token):

| Field | Type | Notes |
|---|---|---|
| `identifier` | string | Echoed identifier |
| `provider` | string | Empty in current responses |
| `price_usd` | number | **0 when token is unknown / misnamed** — treat 0 as "not found", not as "free" |
| `timestamp` | number | Milliseconds |
| `cg.id` | string | CoinGecko ID |
| `cg.name` | string | Display name |
| `cg.market_cap` | number | USD |
| `cg.total_volume` | number | 24h USD volume |
| `cg.price_change_24h_usd` | number | Absolute |
| `cg.price_change_percentage_24h_usd` | number | Percent |
| `cg.sparkline_in_7d` | number[] | For chart widgets |
| `cg.timestamp` | string | ISO 8601 |

---

## Asset Notation

Same convention as Maya: `CHAIN.ASSET[-CONTRACT]`.

- `DASH.DASH`
- `BTC.BTC`
- `ETH.ETH`
- `ETH.USDC-0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48`
- `ARB.ARB-0x912ce59144191c1204e64559fe8253a0e49e6548`
- `SOL.SOL`

The `identifier` returned by `/tokens` is always the canonical key — never construct identifiers by hand from a ticker.

---

## Implementation Architecture (Proposed)

If integrated into the wallet, a layered structure analogous to Maya would be:

```
SwapKitApi (Interface)
    ↓
SwapKitApiAggregator
    ↓
├── SwapKitWebApi (HTTP layer)
│   └── SwapKitEndpoint (Retrofit — api.swapkit.dev)
│
├── (Per-chain transaction builders/signers)
│   ├── DASH: PSBT / native DASH tx (depends on what /v3/swap returns for DASH)
│   ├── EVM:  ethers-style tx (likely out of scope for this wallet)
│   └── …
│
└── FiatExchangeRateApi (or use /price directly)
```

**Suggested files** (when implementation begins):

- `api/SwapKitApi.kt` — public interface
- `api/SwapKitWebApi.kt` — HTTP wiring
- `api/SwapKitEndpoint.kt` — Retrofit interface
- `api/RemoteDataSource.kt` — Retrofit factory with `x-api-key` interceptor
- `di/SwapKitModule.kt` — Hilt bindings
- `model/` — `Provider.kt`, `TokensResponse.kt`, `Token.kt`, `QuoteRequest.kt`, `QuoteResponse.kt`, `Route.kt`, `SwapRequest.kt`, `SwapResponse.kt`, `TrackRequest.kt`, `TrackResponse.kt`, `PriceRequest.kt`, `PriceResponse.kt`
- `utils/SwapKitConstants.kt` — base URL, default slippage, `routeId` TTLs

### Suggested Swap Flow (DASH → X)

1. **Discover**: `GET /providers` → confirm `MAYACHAIN(_STREAMING)` supports `dash`. `GET /tokens?provider=MAYACHAIN` → get `DASH.DASH` identifier.
2. **Quote**: `POST /v3/quote` with `sellAsset=DASH.DASH`, `buyAsset=…`, `sellAmount`, `slippage`. Pick a route (RECOMMENDED by default).
3. **Show user**: route summary, fees, estimated time, slippage warning. Refresh if `routeId` is older than ~60 s.
4. **Build**: `POST /v3/swap` with `routeId`, `sourceAddress`, `destinationAddress`. Handle errors:
   - `swapRouteNotFound` → re-quote.
   - `outputAmountDeviationTooHigh` → re-quote, optionally `overrideSlippage`.
   - `insufficientBalance` / `unableToBuildTransaction` → ask user to lower amount.
   - `isSanctionedAddress` → reject.
5. **Sign & broadcast**: decode/handle the `tx` payload according to `txType`. For DASH (UTXO via Maya) this is most likely a PSBT or a Maya-style vault-deposit + OP_RETURN-memo transaction.
6. **Track**: `POST /track` with the broadcast hash + DASH chain ID; poll until `status === "completed"` (or refunded/failed).
7. **Display price**: optionally use `POST /price` for live USD/fiat display.

---

## Important Considerations

### Authentication & Affiliate

- The `x-api-key` header is mandatory.
- The same key drives the affiliate-fee/affiliate-address configuration in the partner dashboard. `affiliateFee` in `/v3/quote` overrides per-request (basis points, 0–1000).
- **Do not ship API keys in the client.** A proxy or remote-config secret is required, similar to how Uphold/Coinbase keys are handled today.

### AML & Address Screening

- Quote-time screening is partial; **full screening runs on every `/v3/swap` call**.
- A working quote does **not** guarantee a working swap — addresses can be refused at swap time (`isSanctionedAddress`).

### Quote Lifecycle

- Routes expire 60 s after issuance; the cache window is 5 min.
- After 60 s, `/v3/swap` will auto-refresh and may return `outputAmountDeviationTooHigh` if pricing drifted >5%.
- After 5 min, `/v3/swap` returns `swapRouteNotFound` and the client must call `/v3/quote` again.

### Fees

Up to six fee categories are returned per route:

- **Inbound** — paid from the user's wallet (the only one that comes out of the source side).
- **Network** — chain transaction fee.
- **Affiliate** — per `affiliateFee` config.
- **Service** — SwapKit's operational fee.
- **Outbound** — destination-chain delivery fee.
- **Liquidity** — provider/liquidity-pool fee.

Output amounts shown are already net of all fees except inbound.

### Provider Errors vs Top-Level Errors

`/v3/quote` returns:

- A top-level `error` for request-level failures (auth, malformed body, no routes at all).
- `providerErrors[]` for per-provider failures while other providers still produced routes — **do not treat these as fatal**; they're informational.

### Compatibility With Existing Maya Module

- Asset notation and the general routing model overlap heavily, so `model/Amount.kt`, `model/SwapQuoteRequest.kt`, and the existing fiat-rate stack can mostly be reused.
- The biggest delta is that **SwapKit returns the transaction**, where the current Maya integration **builds the DASH transaction client-side** (vault deposit + OP_RETURN memo, no BIP69 sorting). For SwapKit-originated DASH swaps, the wallet must learn to parse and sign whatever payload SwapKit delivers (likely PSBT).

---

## Detecting Maya-only Assets (for the cryptocurrency list screen)

> Verified live against the API on 2026-06-08.

Some coins are routable from DASH **only via MAYACHAIN** — no other provider can carry them. These inherit Maya's two liabilities: (a) halt exposure with **no fallback** (when Maya halts, the coin is simply unavailable — see "how to tell if trading is halted" below), and (b) the OP_RETURN-memo requirement that makes Maya unusable for the **buy** direction from an external wallet. The list screen may want to flag or hide these.

### Why it's tractable

Only **two** SwapKit providers route DASH at all — confirmed from `/providers` (the only entries whose `supportedChainIds` contain `dash`):

- `NEAR` (NEAR Intents)
- `MAYACHAIN_STREAMING`

(`MAYACHAIN` non-streaming returns `noTokenListsFound` — only the streaming variant carries a token list.) Because DASH originates through exactly these two, **"Maya-only" reduces to "NEAR can't route it."**

### Method — provider token-list intersection (3 static, cacheable calls)

Do **not** infer this from a normal quote's `noRoutesFound` — that error is ambiguous (halt vs. no liquidity vs. amount-too-small). Membership in provider token lists is a clean *capability* signal, independent of live liquidity or halt state.

1. `GET /tokens?provider=NEAR` → set of identifiers NEAR can route.
2. `GET /tokens?provider=MAYACHAIN_STREAMING` → Maya's set.
3. `GET /swapTo?sellAsset=DASH.DASH` → everything reachable from DASH.

Then classify each reachable identifier:

```
maya_only  =  reachable  AND  (id ∈ MAYA list)  AND  (id ∉ NEAR list)
```

Compare identifiers case-insensitively; the `identifier` from `/tokens` is canonical (never hand-build from a ticker).

### Verified result (2026-06-08)

Counts: NEAR 140 tokens, MAYACHAIN_STREAMING 19, `/swapTo` from DASH = 150 reachable. **11 coins were Maya-only:**

```
MAYA.CACAO, MAYA.MAYA, THOR.RUNE,
ETH.MOCA, ETH.WSTETH,
ARB.GLD, ARB.LEO, ARB.USDT, ARB.WBTC, ARB.WSTETH, ARB.YUM
```

The other ~139 reachable coins (BTC, ETH, major tokens) are NEAR-capable and therefore survive a Maya halt.

**Cross-checked with provider-forced quotes** (`providers: [...]` on `/v3/quote`), and the prediction held exactly:

- `MAYA.CACAO`, `THOR.RUNE` forced to `["NEAR"]` → `noRoutesFound`; forced to `["MAYACHAIN_STREAMING"]` → route returned. ✅ Maya-only.
- `BTC.BTC` → both providers returned routes. ✅ not Maya-only.

### Caveats

1. **Capability, not live status.** "Maya-only" means *only Maya can ever route it* — it does not mean Maya is up right now. Combine with halt detection: a Maya-only coin **during** a Maya halt is unavailable with no fallback, exactly the set to grey out / flag first.
2. **Lists drift** — provider token lists change as listings come and go. Refresh on a cadence; do **not** hardcode the 11.
3. **Single-leg assumption.** This treats reachability as "both assets on the same provider." SwapKit can in principle multi-leg, but since DASH originates only via NEAR or Maya and the Maya-only coins returned `noRoutesFound` when NEAR was forced, there is no NEAR→…→coin path in practice today. If SwapKit ever adds DASH to a third provider, re-derive the DASH-provider set from `/providers` first.
4. **Buy direction.** Maya-only coins are precisely the ones unusable as a *buy* source through an external wallet (OP_RETURN memo). Hiding the Maya-only set on the buy screen specifically is a reasonable use of this classification.

---

## Testing Endpoints

```bash
# Providers
curl -H "x-api-key: $KEY" "https://api.swapkit.dev/providers"

# Tokens for MAYACHAIN
curl -H "x-api-key: $KEY" "https://api.swapkit.dev/tokens?provider=MAYACHAIN"

# What can DASH be swapped to?
curl -H "x-api-key: $KEY" "https://api.swapkit.dev/swapTo?sellAsset=DASH.DASH"

# Quote DASH -> BTC
curl -X POST -H "x-api-key: $KEY" -H "Content-Type: application/json" \
  -d '{"sellAsset":"DASH.DASH","buyAsset":"BTC.BTC","sellAmount":"0.1","slippage":3}' \
  "https://api.swapkit.dev/v3/quote"

# Build the swap
curl -X POST -H "x-api-key: $KEY" -H "Content-Type: application/json" \
  -d '{"routeId":"<from-quote>","sourceAddress":"<dash-addr>","destinationAddress":"<btc-addr>"}' \
  "https://api.swapkit.dev/v3/swap"

# Track
curl -X POST -H "x-api-key: $KEY" -H "Content-Type: application/json" \
  -d '{"hash":"<txid>","chainId":"dash"}' \
  "https://api.swapkit.dev/track"

# Price
curl -X POST -H "x-api-key: $KEY" -H "Content-Type: application/json" \
  -d '{"tokens":[{"identifier":"DASH.DASH"},{"identifier":"BTC.BTC"}],"metadata":true}' \
  "https://api.swapkit.dev/price"
```

---

## Open Questions for the Dash Wallet

These need to be answered before any client code is written:

1. **Does SwapKit accept `DASH.DASH` as a `sellAsset`?** (Almost certainly yes via MAYACHAIN — verify against `/tokens?provider=MAYACHAIN` and a live `/v3/quote`.)
2. **What `txType` does `/v3/swap` return for DASH?** PSBT, or a JSON object describing a vault-deposit + OP_RETURN like the current Maya path?
3. **Where does the API key live?** In-app (insecure), proxied through a backend, or fetched from remote config like the Uphold/Coinbase keys?
4. **Affiliate fee policy.** What basis-point split, and configured at the dashboard or per request?
5. **Does SwapKit duplicate Maya's offering enough to *replace* the direct integration, or should it be an additional swap source presented alongside Maya?**

---

## Official Documentation

- **API Introduction**: https://docs.swapkit.dev/swapkit-api/introduction
- **Quote & Swap Flow**: https://docs.swapkit.dev/swapkit-api/quote-and-swap-implementation-flow
- **Providers**: https://docs.swapkit.dev/swapkit-api/providers-request-supported-chains-by-a-swap-provider
- **Tokens**: https://docs.swapkit.dev/swapkit-api/tokens-request-supported-tokens-by-a-swap-provider
- **swapFrom**: https://docs.swapkit.dev/swapkit-api/swapfrom-request-sell-swap-options
- **swapTo**: https://docs.swapkit.dev/swapkit-api/swapto-request-buy-swap-options
- **/v3/quote**: https://docs.swapkit.dev/swapkit-api/v3-quote-request-a-swap-quote
- **/v3/swap**: https://docs.swapkit.dev/swapkit-api/v3-swap-obtain-swap-transaction-details
- **/track**: https://docs.swapkit.dev/swapkit-api/track-request-the-status-of-a-swap
- **/price**: https://docs.swapkit.dev/swapkit-api/price-lookup-token-prices
- **Swagger UI**: https://api.swapkit.dev/docs
- **Tracker UI**: https://track.swapkit.dev/
- **Dashboard**: https://dashboard.swapkit.dev/

## References

- SwapKit: https://swapkit.dev/
- Underlying providers leveraged for DASH swaps: Maya Protocol (https://www.mayaprotocol.com/) — see `MAYA_PROTOCOL.md` in this directory.