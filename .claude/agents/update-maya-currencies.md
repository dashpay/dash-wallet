---
name: "update-maya-currencies"
description: "Fetches pools from the Maya Midgard API and updates MayaCurrencyList with any new coins or tokens. Use this agent whenever you need to sync the app's supported currency list with what is live on Maya."
tools: ["*"]
---

# Update Maya Currency List

## Purpose
Sync `MayaCurrencyList` in `MayaCryptoCurrency.kt` with the live pools from the Maya Midgard API.

## Key Files
- **Currency list**: `integrations/maya/src/main/java/org/dash/wallet/integrations/maya/payments/MayaCryptoCurrency.kt`
- **String resources**: `integrations/maya/src/main/res/values/strings-maya.xml`
- **Parsers directory**: `integrations/maya/src/main/java/org/dash/wallet/integrations/maya/payments/parsers/`

## Steps

### 1. Fetch current pools
Use `WebFetch` on `https://midgard.mayachain.info/v2/pools` to get all pool assets.
Extract the `asset` field from each pool object. Example values:
- `BTC.BTC`, `ETH.ETH`, `DASH.DASH` — L1 native coins
- `ETH.USDT-0XDAC17F958D2EE523A2206206994597C13D831EC7` — EVM tokens (chain.symbol-contractAddress)
- `ARB.USDC-0XAF88D065E77C8CC2239327C5EDB3A432268E5831` — Arbitrum EVM tokens

### 2. Extract existing assets from MayaCurrencyList
Read `MayaCryptoCurrency.kt`. Find all `asset` string values inside `MayaCurrencyList.init {}`.
These will be lines like:
```kotlin
"ETH.USDT-0XDAC17F958D2EE523A2206206994597C13D831EC7",
```
Build a set of existing asset strings.

### 3. Identify new assets
Compare the API list against the existing set. Assets present in the API but absent from the code are new.
**Skip** `DASH.DASH` and `THOR.RUNE` — these are handled specially or are the native chain coin.

### 4. Categorize each new asset

#### EVM Tokens (ETH.* and ARB.*)
These share the Ethereum address format (`0x...`). Use `MayaEthereumTokenCryptoCurrency`.

For an asset like `ETH.MOCA-0X53312F85BBA24C8CB99CFFC13BF82420157230D3`:
- `chain` = `ETH`, `symbol` = `MOCA`, `contractAddr` = `0X53312F85BBA24C8CB99CFFC13BF82420157230D3`
- `shortAlias` = last 5 hex chars of contractAddr = `230D3`
- `memoAsset` = `ETH.MOCA-230D3`
- `uriPrefix` = `symbol.lowercase()` = `"moca"`

Generate:
```kotlin
MayaEthereumTokenCryptoCurrency(
    "MOCA",
    "Mocaverse",          // use symbol as name if name unknown
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

Network display name format: `"SYMBOL (Chain)"` where Chain is `Ethereum` for ETH and `Arbitrum` for ARB.

**Naming convention for string resource IDs:**
- code: `cryptocurrency_{symbol.lowercase()}_code`
- network: `cryptocurrency_{symbol.lowercase()}_{chain.lowercase()}_network`

If the same symbol already has a `_code` resource (e.g., `cryptocurrency_usdt_code`), reuse it for the code but still add a new network string.

#### L1 Native Coins (new chains like ZEC, XRD, MAYA, KUJI, etc.)
These need:
1. A new `Maya{Name}CryptoCurrency` class in `MayaCryptoCurrency.kt`
2. Possibly a new `{Chain}PaymentIntentParser` class in the parsers directory
3. String resources

**Address format guide by chain:**
| Chain | Address format | Parser class to use |
|-------|---------------|---------------------|
| ETH / ARB / BSC | `0x[a-fA-F0-9]{40}` | `EthereumPaymentIntentParser` |
| BTC / DASH / ZEC | Base58Check (t-prefix for ZEC) | `BitcoinPaymentIntentParser` or `ZcashPaymentIntentParser` |
| THOR / MAYA chain | Bech32, prefix `thor` / `maya`, length 38 | `Bech32PaymentIntentParser` |
| KUJI | Bech32, prefix `kujira`, length 38 | `Bech32PaymentIntentParser` |
| XRD (Radix) | Bech32, HRP `account_rdx`, length ~61 | `XrdPaymentIntentParser` |

For a new L1, create a class extending `MayaBitcoinCryptoCurrency` (which uses 1e8 units — Maya's internal representation for all assets):
```kotlin
open class Maya{Name}CryptoCurrency : MayaBitcoinCryptoCurrency() {
    override val code: String = "SYMBOL"
    override val name: String = "Full Name"
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

### 5. Insert new entries into MayaCurrencyList

Add EVM token entries grouped by chain (ETH tokens first, then ARB tokens) before the KUJI block.
Add new L1 coins at the end of the list, after `MayaRuneCryptoCurrency()`.

Insertion point for EVM tokens — add after the last existing ARB.WSTETH entry:
```kotlin
// ... existing ARB.WSTETH entry ...
),
// NEW EVM TOKENS GO HERE

MayaKujiraCryptoCurrency(),
```

Insertion point for new L1 coins — after `MayaRuneCryptoCurrency()`:
```kotlin
MayaRuneCryptoCurrency(),
// NEW L1 COINS GO HERE (ZecCryptoCurrency, RadixCryptoCurrency, etc.)
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
- Check that all `R.string.*` references have corresponding entries in `strings-maya.xml`
- Check that all new `PaymentIntentParser` classes are imported in `MayaCryptoCurrency.kt`
- Check that the `currencyMap` key (asset string) matches exactly between the `MayaCryptoCurrency` subclass and the list entry

## Important Conventions

- **Memo alias (shortened asset)**: Use the last 5 hex characters of the contract address for EVM tokens. Example: contract `...3606EB48` → memo alias `ETH.USDC-6EB48`. Do NOT include the `0X` prefix in the memo alias.
- **Unit scaling**: All Maya L1 classes extend `MayaBitcoinCryptoCurrency` (1e8 units per coin). ETH/ARB native ETH tokens use `MayaEthereumCryptoCurrency` (1e9 / GWEI).
- **String IDs**: If a symbol already exists with a code resource (e.g., USDT already has `cryptocurrency_tether_code`), reuse the code string but add a new chain-specific network string.
- **`translatable="false"`** must be set on all coin code strings.