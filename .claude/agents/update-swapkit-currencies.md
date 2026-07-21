---
name: "update-swapkit-currencies"
description: "Fetches tokens from the SwapKit API and updates MayaCurrencyList with any new coins or tokens reachable from DASH via the providers the wallet uses. Use this agent whenever you need to sync the app's supported currency list with what SwapKit can route."
tools: ["*"]
---

# Update SwapKit Currency List

## Purpose
Sync `MayaCurrencyList` in `MayaCryptoCurrency.kt` with the live tokens exposed by the SwapKit API. The SwapKit and Maya backends share `MayaCurrencyList` (same `CHAIN.ASSET[-CONTRACT]` notation), so a single curated list backs both `MayaApiAggregator` and `SwapKitApiAggregator`. This agent uses SwapKit as the source of truth for what assets the wallet should support when SwapKit is the active swap backend.

## Key Files
- **Currency list**: `integrations/maya/src/main/java/org/dash/wallet/integrations/maya/payments/MayaCryptoCurrency.kt`
- **String resources**: `integrations/maya/src/main/res/values/strings-maya.xml`
- **Parsers directory**: `integrations/maya/src/main/java/org/dash/wallet/integrations/maya/payments/parsers/`
- **SwapKit constants** (provider list, API key): `integrations/maya/src/main/java/org/dash/wallet/integrations/maya/swapkit/SwapKitConstants.kt`

## Source of Truth

**`GET /swapTo?sellAsset=DASH.DASH` is the source of truth.** This is the exact endpoint `SwapKitApiAggregator.refreshPools()` calls to populate the wallet's currency picker — every identifier it returns must have a matching entry in `MayaCurrencyList` so the picker can render it. Do NOT filter by provider during discovery: `DASH_SUPPORTED_PROVIDERS` applies at quote time only, and the picker shows everything `/swapTo` returns.

`/tokens?provider=NAME` is supplementary — use it only to look up display metadata (`name`, `decimals`, `coingeckoId`) for an identifier already in the target set. Do not intersect.

## Steps

### 1. Fetch the target set from `/swapTo`

The SwapKit API requires the `x-api-key` header. Read the key from `SwapKitConstants.API_KEY` (or override via the `SWAPKIT_API_KEY` env var if set):

```bash
KEY="${SWAPKIT_API_KEY:-$(grep -E 'API_KEY' integrations/maya/src/main/java/org/dash/wallet/integrations/maya/swapkit/SwapKitConstants.kt | head -1 | sed -E 's/.*"([^"]+)".*/\1/')}"
curl -s -H "x-api-key: $KEY" "https://api.swapkit.dev/swapTo?sellAsset=DASH.DASH" | jq -r '.[]' | sort -u
```

The full response is the **target set**. It includes everything reachable from DASH across every aggregated provider (MAYACHAIN, NEAR Intents, CHAINFLIP, GARDEN, FLASHNET, …). Do NOT filter by provider — `SwapKitApiAggregator` calls this endpoint without a provider filter and surfaces every result in the picker.

Each entry is a `CHAIN.SYMBOL[-CONTRACT]` identifier. Uppercase the hex contract suffix when comparing against `MayaCurrencyList` (Maya stores `0X` uppercase).

### 1b. Fetch token metadata for naming

For each chain prefix that appears in the target set, fetch the token list of any provider that supports that chain — this is just to get human-readable `name`, `decimals`, and `coingeckoId` for the new identifiers. Reasonable starting points:

```bash
# Provider lookup: which provider serves a given chain?
curl -s -H "x-api-key: $KEY" "https://api.swapkit.dev/providers" \
  | jq -r '.[] | "\(.name)\t\(.supportedChainIds|join(","))"'

# Token metadata for a given provider
curl -s -H "x-api-key: $KEY" "https://api.swapkit.dev/tokens?provider=MAYACHAIN_STREAMING" | jq '.tokens[]'
curl -s -H "x-api-key: $KEY" "https://api.swapkit.dev/tokens?provider=NEAR" | jq '.tokens[]'
curl -s -H "x-api-key: $KEY" "https://api.swapkit.dev/tokens?provider=CHAINFLIP_STREAMING" | jq '.tokens[]'
```

> Note: SwapKit currently returns `MAYACHAIN_STREAMING` (not bare `MAYACHAIN`) when you `/tokens?provider=MAYACHAIN_STREAMING`. The two share token lists per the protocol doc.

The token list gives you the human display name (`name`) for the network string and confirms `decimals`. If multiple providers list the same identifier with different names, prefer the one from a chain-native provider (e.g. NEAR token from `NEAR`, not from a bridge).

### 2. Extract existing assets from MayaCurrencyList

Read `MayaCryptoCurrency.kt`. Find all `asset` string values inside `MayaCurrencyList.init {}`. These are lines like:

```kotlin
"ETH.USDT-0XDAC17F958D2EE523A2206206994597C13D831EC7",
```

Build a set of existing asset strings (uppercase the contract suffix when comparing).

### 3. Identify new assets

Compare the SwapKit `/swapTo` target set against the existing set. Identifiers present in SwapKit but absent from the code are new — **all of them must be added**, regardless of which provider services them. The wallet's picker shows everything `/swapTo` returns, so missing entries become picker bugs.

**Skip ONLY**:
- `DASH.DASH` — handled separately by the swap source side.
- `THOR.RUNE` — special-cased; already mapped via `MayaRuneCryptoCurrency`.

Do NOT skip NEAR, SOL, BASE, OP, POL, ZEC, BCH, LTC, DOGE, AVAX, BSC, XRP, TRON, ATOM, etc. — if `/swapTo` returns it, it goes in.

### 4. Categorize each new asset

#### EVM Tokens (ETH.\* and ARB.\*)

These share the Ethereum address format (`0x...`). Use `MayaEthereumTokenCryptoCurrency`.

For an asset like `ETH.MOCA-0X53312F85BBA24C8CB99CFFC13BF82420157230D3`:
- `chain` = `ETH`, `symbol` = `MOCA`, `contractAddr` = `0X53312F85BBA24C8CB99CFFC13BF82420157230D3`
- `shortAlias` = last 5 hex chars of contractAddr = `230D3`
- `memoAsset` = `ETH.MOCA-230D3`
- `uriPrefix` = `symbol.lowercase()` = `"moca"`
- Display `name` = the SwapKit `name` field (e.g. `"Mocaverse"`); fall back to the symbol if SwapKit returned no name.

Generate:
```kotlin
MayaEthereumTokenCryptoCurrency(
    "MOCA",
    "Mocaverse",          // SwapKit token.name
    "ETH.MOCA-0X53312F85BBA24C8CB99CFFC13BF82420157230D3",
    EthereumPaymentIntentParser("moca", "ETH.MOCA-230D3"),
    R.string.cryptocurrency_moca_code,
    R.string.cryptocurrency_moca_ethereum_network
),
```

String resources to add:
```xml
<string name="cryptocurrency_moca_code" translatable="false">MOCA</string>
<string name="cryptocurrency_moca_ethereum_network">Mocaverse (Ethereum)</string>
```

Network display name format: `"Name (Chain)"` where Chain is `Ethereum` for ETH and `Arbitrum` for ARB.

**Naming convention for string resource IDs:**
- code: `cryptocurrency_{symbol.lowercase()}_code`
- network: `cryptocurrency_{symbol.lowercase()}_{chain.lowercase()}_network`

If the same symbol already has a `_code` resource (e.g., `cryptocurrency_usdt_code`), reuse it but still add a new chain-specific network string.

#### L1 Native Coins (new chains: ZEC, XRD, MAYA, KUJI, etc.)

These need:
1. A new `Maya{Name}CryptoCurrency` class in `MayaCryptoCurrency.kt`
2. Possibly a new `{Chain}PaymentIntentParser` class in the parsers directory
3. String resources

**Address format guide by chain:**

| Chain | Address format | Parser class to use |
|-------|---------------|---------------------|
| ETH / ARB / BSC / AVAX / BASE / OP / POL | `0x[a-fA-F0-9]{40}` | `EthereumPaymentIntentParser` |
| BTC / DASH / LTC / BCH / DOGE | Base58Check / Bech32 | `BitcoinPaymentIntentParser` |
| ZEC | Base58Check (t-prefix) | `ZcashPaymentIntentParser` |
| THOR / MAYA chain | Bech32, prefix `thor` / `maya`, length 38 | `Bech32PaymentIntentParser` |
| KUJI | Bech32, prefix `kujira`, length 38 | `Bech32PaymentIntentParser` |
| ATOM (cosmoshub) | Bech32, HRP `cosmos`, length 39 | `Bech32PaymentIntentParser` |
| XRD (Radix) | Bech32, HRP `account_rdx`, length ~61 | `XrdPaymentIntentParser` |
| SOL (Solana) | Base58, 32–44 chars (no checksum) | new `SolanaPaymentIntentParser` |
| NEAR | implicit hex 64-char OR `*.near` | new `NearPaymentIntentParser` |
| TRON | Base58 starting with `T`, 34 chars | new `TronPaymentIntentParser` |
| XRP | Base58 starting with `r`, 25–35 chars | new `XrpPaymentIntentParser` |

For a new L1, create a class extending `MayaBitcoinCryptoCurrency` (which uses 1e8 units — Maya's internal representation for all assets):

```kotlin
open class Maya{Name}CryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "SYMBOL"
    override val name: String = "Full Name"   // from SwapKit token.name
    override val asset: String = "CHAIN.SYMBOL"
    override val exampleAddress: String = "example_address_here"
    override val paymentIntentParser: PaymentIntentParser = ...
    override val addressParser: AddressParser = ...
    override val codeId: Int = R.string.cryptocurrency_{symbol_lower}_code
    override val nameId: Int = R.string.cryptocurrency_{symbol_lower}_network
}
```

If the chain uses bech32 addresses, use:
- `Bech32AddressParser("prefix", length, null)` for the address parser
- `Bech32PaymentIntentParser("SYMBOL", "prefix", "prefix", length, "CHAIN.SYMBOL")` for the payment intent parser

If a new `PaymentIntentParser` class file is needed (for non-Bech32 non-ETH chains), create it in the parsers directory following the `ZcashPaymentIntentParser.kt` pattern.

> **Decimal note**: SwapKit reports per-chain `decimals` (18 for EVM, 8 for BTC/DASH, etc.). The wallet's internal representation always uses 1e8 (`MayaBitcoinCryptoCurrency`). Don't introduce per-asset decimal overrides — Maya's quote/swap pipeline already normalises everything to 1e8.

### 5. Insert new entries into MayaCurrencyList

- Add EVM token entries grouped by chain (ETH tokens first, then ARB tokens) before the KUJI block.
- Add new L1 coins at the end of the list, after `MayaRuneCryptoCurrency()`.

Insertion point for EVM tokens — add after the last existing ARB entry:
```kotlin
// ... existing ARB.WSTETH entry ...
),
// NEW EVM TOKENS GO HERE

MayaKujiraCryptoCurrency(),
```

Insertion point for new L1 coins — after `MayaRuneCryptoCurrency()`:
```kotlin
MayaRuneCryptoCurrency(),
// NEW L1 COINS GO HERE
```

### 6. Add string resources to strings-maya.xml

Add new entries before `<string name="maya_error">`:
```xml
<!-- group by chain, alphabetical within group -->
<string name="cryptocurrency_xxx_code" translatable="false">XXX</string>
<string name="cryptocurrency_xxx_network">Full Name (Chain)</string>
```

### 7. Verify

After making changes:
- Check that all `R.string.*` references have corresponding entries in `strings-maya.xml`.
- Check that all new `PaymentIntentParser` classes are imported in `MayaCryptoCurrency.kt`.
- Check that the `currencyMap` key (asset string) matches exactly between the `MayaCryptoCurrency` subclass and the list entry.
- Run a price spot-check via SwapKit to confirm the new identifier resolves: `curl -s -H "x-api-key: $KEY" -X POST -H "Content-Type: application/json" -d '{"tokens":[{"identifier":"<NEW.IDENTIFIER>"}]}' https://api.swapkit.dev/price` — `price_usd: 0` means SwapKit doesn't recognise the identifier (likely a transcription error in the contract address).
- Quick build check: `./gradlew :integrations:maya:compile_testNet3DebugKotlin`.

## Important Conventions

- **Memo alias (shortened asset)**: Use the last 5 hex characters of the contract address for EVM tokens. Example: contract `...3606EB48` → memo alias `ETH.USDC-6EB48`. Do NOT include the `0X` prefix in the memo alias.
- **Identifier casing**: SwapKit returns contract addresses in mixed case in `address`/`identifier`. Uppercase the hex suffix when storing in `MayaCurrencyList` so it matches the existing entries (Maya stores `0X` uppercase, e.g. `ETH.USDT-0XDAC17F958D2EE523A2206206994597C13D831EC7`).
- **Unit scaling**: All Maya L1 classes extend `MayaBitcoinCryptoCurrency` (1e8 units per coin). ETH/ARB native ETH tokens use `MayaEthereumCryptoCurrency` (1e9 / GWEI). SwapKit's `decimals` field is informational only — do not propagate it into the class.
- **String IDs**: If a symbol already exists with a code resource (e.g., USDT already has `cryptocurrency_tether_code`), reuse the code string but add a new chain-specific network string.
- **`translatable="false"`** must be set on all coin code strings.
- **API key handling**: The key in `SwapKitConstants.API_KEY` is committed for development convenience. Do not echo it into commit messages or PR descriptions. Treat it as a secret in any external output.
- **Provider drift**: If `/swapTo?sellAsset=DASH.DASH` ever returns identifiers that aren't in `/tokens?provider=MAYACHAIN`, SwapKit has expanded DASH routing to a new provider — flag this rather than silently adding the asset, since the wallet's `DASH_SUPPORTED_PROVIDERS` whitelist would still exclude it at quote time.