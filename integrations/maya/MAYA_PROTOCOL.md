# Maya Protocol Integration Documentation

This document describes the Maya Protocol API integration used in the Dash Wallet.

## Overview

Maya Protocol is a cross-chain liquidity protocol (based on THORChain) that enables atomic swaps between different blockchain assets. This integration allows Dash wallet users to swap DASH for other cryptocurrencies like BTC, ETH, and various ERC-20/ARB tokens.

## API Endpoints

### Primary API Base URLs

- **Main API**: `https://mayanode.mayachain.info/mayachain/`

> **Note**: The Midgard v2 API (`https://midgard.mayachain.info/v2/`) was previously used as the source for pool information but has been removed. All data now comes from the mayanode endpoint.

### Available Endpoints

#### 1. Pools Information
**Endpoint**: `GET https://mayanode.mayachain.info/mayachain/pools`

Returns the raw mayanode pool list. Each pool exposes balances in CACAO and the paired asset, plus liquidity-unit accounting and status flags.

**Response fields** (per pool):
- `asset`: Asset identifier (e.g., `BTC.BTC`, `ETH.USDT-0x...`)
- `balance_cacao`: Pool depth in CACAO base units
- `balance_asset`: Pool depth in the paired asset's base units
- `LP_units`: Liquidity provider units
- `pool_units`: Total pool units
- `status`: Pool status — must be `Available` to be usable
- `synth_units`, `synth_supply`: Synthetic asset accounting
- `pending_inbound_cacao`, `pending_inbound_asset`: Pending liquidity
- `synth_mint_paused`: Whether minting synths is paused
- `bondable`: Whether the pool is bondable

**Asset price derivation**: Unlike the old Midgard response, mayanode pools do **not** include an `assetPriceUSD` field. Prices are derived client-side:
- Each pool's CACAO ratio: `balance_cacao / balance_asset` (see `PoolInfo.assetPriceInCacao`)
- USD price of any asset: computed via the USD-stable pools (USDT, USDC). The liquidity-weighted formula is
  `(balance_cacao × Σ stable_balance_asset) / (balance_asset × Σ stable_balance_cacao)`,
  which collapses CACAO out of both sides and yields USD per whole asset. Implementation: `MayaViewModel.applyPoolPrices()` / `priceInUsd()`.
- Fiat conversion: multiply the derived USD price by the `usdToFiat` rate from the wallet's selected currency.

**Used by**: `MayaWebApi.getPoolInfo()` via `endpoint.getPoolInfo()` (mayanode)

#### 2. Inbound Addresses
**Endpoint**: `GET https://mayanode.mayachain.info/mayachain/inbound_addresses`

Returns vault addresses for all supported chains where users send funds to execute swaps.

**Response fields**:
- `address`: Vault address for the chain
- `chain`: Blockchain identifier (DASH, BTC, ETH, ARB, etc.)
- `halted`: Critical status flag - if true, chain is halted
- `router`: EVM router contract address (ETH/ARB chains only)
- `gas_rate`: Current gas rate for the chain
- `gas_rate_units`: Units for gas rate (duffsperbyte, satsperbyte, centigwei)
- `outbound_fee`: Fee for outbound transactions
- `outbound_tx_size`: Estimated transaction size
- `dust_threshold`: Minimum amount threshold

**Used by**: `MayaWebApi.getInboundAddresses()`

#### 3. Network Information
**Endpoint**: `GET https://mayanode.mayachain.info/mayachain/network`

Returns network-wide parameters and settings.

**Used by**: `MayaWebApi.getNetwork()`

#### 4. Swap Quote
**Endpoint**: `GET https://mayanode.mayachain.info/mayachain/quote/swap`

**Parameters**:
- `from_asset`: Source asset (e.g., "DASH.DASH")
- `to_asset`: Target asset (e.g., "BTC.BTC", "ETH.ETH-0x...")
- `amount`: Amount in base units (1e8 for DASH/BTC)
- `destination`: Target blockchain address

**Response fields**:
- `expected_amount_out`: Estimated output amount
- `fees`: Detailed fee breakdown (liquidity, outbound, affiliate, slippage)
- `memo`: Transaction memo instruction for the swap
- `inbound_address`: Vault address to send funds to
- `expiry`: Quote expiration timestamp
- `recommended_min_amount_in`: Minimum recommended amount
- `dust_threshold`: Dust threshold
- `warning`: Important warnings (do not cache, expiry)
- `notes`: Implementation notes
- `error`: Error message if quote failed

**Important**: Quotes expire and should not be cached for more than 10 minutes.

**Used by**: `MayaWebApi.getSwapQuote()`

#### 5. Transaction Status
**Endpoint**: `GET https://mayanode.mayachain.info/mayachain/tx/{txid}`

Query the status and details of a swap transaction.

**Response fields**:
- `observedTx`: Transaction observation details
- `status`: Transaction status
- `outHashes`: Outbound transaction hashes
- `error`: Error message if failed

## Data Models

### Core Request Models

#### SwapQuoteRequest
Location: `model/SwapQuoteRequest.kt`

```kotlin
- amount: Amount (multi-currency holder)
- amount_from: String = "input"
- source_maya_asset: String (e.g., "DASH.DASH")
- target_maya_asset: String (e.g., "BTC.BTC", "ETH.ETH-0x...")
- fiatCurrency: String
- targetAddress: String (destination blockchain address)
- maximum: Boolean (whether to send max balance)
```

#### Amount
Location: `model/Amount.kt`

Holds amounts in Dash, fiat, and crypto currencies simultaneously with automatic conversion:
- `dash`: Amount in DASH
- `fiat`: Amount in selected fiat currency
- `crypto`: Amount in target cryptocurrency
- `anchoredType`: Which currency is the anchor for conversions

### Core Response Models

#### PoolInfo
Location: `model/PoolInfo.kt`

Mirrors the mayanode `/pools` response (snake_case fields are mapped via `@SerializedName`):

```kotlin
- asset: String (e.g., "BTC.BTC", "ETH.USDT-0x...")
- balanceCacao: String (pool depth in CACAO base units)
- balanceAsset: String (pool depth in asset base units)
- lpUnits: String
- poolUnits: String
- status: String (must be "Available" to use)
- synthUnits: String
- synthSupply: String
- pendingInboundCacao: String
- pendingInboundAsset: String
- synthMintPaused: Boolean
- bondable: Boolean

// Computed (not part of the API response, @IgnoredOnParcel):
- assetPriceInCacao: BigDecimal  // balanceCacao / balanceAsset
- assetPriceFiat: Fiat            // populated by MayaViewModel.applyPoolPrices()
```

**Note**: The previous Midgard-shaped fields (`annualPercentageRate`, `assetDepth`, `assetPrice`, `assetPriceUSD`, `liquidityUnits`, `poolAPY`, `runeDepth`, `units`, `volume24h`) were removed when Midgard was dropped as a data source. APY/volume metrics are no longer surfaced.

#### InboundAddress
Location: `model/InboundAddress.kt`

```kotlin
- address: String (vault address)
- chain: String (blockchain identifier)
- chainLpActionsPaused: Boolean
- chainTradingPaused: Boolean
- dustThreshold: String
- gasRate: String
- gasRateUnits: String
- globalTradingPaused: Boolean
- halted: Boolean (critical - check before sending)
- outboundFee: String
- outboundTxSize: String
- pubKey: String
```

**Note**: The API also returns a `router` field for EVM chains (ETH/ARB) which is not currently in the model but doesn't cause issues.

#### SwapQuote
Location: `model/SwapQuote.kt`

```kotlin
- dustThreshold: String
- expectedAmountOut: String
- expiry: Long
- fees: SwapFees (affiliate, asset, liquidity, outbound, slippageBps, total, totalBps)
- inboundAddress: String
- inboundConfirmationBlocks: Int
- inboundConfirmationSeconds: Int
- memo: String (critical - transaction instruction)
- notes: String
- outboundDelayBlocks: Int
- outboundDelaySeconds: Int
- recommendedMinAmountIn: String
- slippageBps: Int
- warning: String
- error: String?
```

## Implementation Architecture

### Layered Architecture

```
MayaApi (Interface)
    ↓
MayaApiAggregator (Main Implementation)
    ↓
├── MayaWebApi (HTTP API Layer)
│   └── MayaEndpoint (Retrofit - mayanode)
│
├── MayaBlockchainApi (Transaction Layer)
│   └── Builds and sends blockchain transactions
│
└── FiatExchangeRateApi (Exchange Rate Layer)
    ├── CurrencyBeaconAPI (primary)
    ├── FreeCurrencyAPI (fallback)
    └── ExchangeRateAPI (fallback)
```

### Key Implementation Files

- **API Interface**: `api/MayaApi.kt`
- **HTTP Layer**: `api/MayaWebApi.kt`
- **Blockchain Layer**: `api/MayaBlockchainApi.kt`
- **Retrofit Factory**: `api/RemoteDataSource.kt`
- **Dependency Injection**: `di/MayaModule.kt`
- **Models**: `model/` directory
- **Constants**: `utils/MayaConstants.kt`
- **USD-pool pricing**: `ui/MayaViewModel.kt` (`applyPoolPrices()`, `priceInUsd()`)

> **Removed in this branch**: `api/MayaLegacyWebApi.kt` and the `MayaLegacyEndpoint` Retrofit interface. Midgard is no longer wired into Hilt — `MayaModule.provideMayaLegacyEndpoint()` was deleted.

### Swap Flow

1. **Get Pool Information**
   - Fetch available pools and pricing
   - Auto-refresh every 30 seconds (UPDATE_FREQ_MS = 30000)

2. **Get Swap Quote**
   - Request estimated output and fees
   - Check source and destination vault status (not halted)
   - Validate addresses and amounts

3. **Build Transaction**
   - Create DASH transaction with proper UTXO structure:
     - VOUT0: Amount + fees to vault address
     - VOUT1: OP_RETURN with memo
     - VOUT2: Change back to source
   - **Important**: Do NOT use BIP69 sorting for outputs

4. **Send Transaction**
   - Submit to DASH network
   - Maya Protocol observes and executes swap
   - Funds delivered to destination address

## Transaction Memo Format

The memo instructs Maya Protocol on how to execute the swap:

**Format**: `=:{TARGET_ASSET}:{DESTINATION_ADDRESS}`

**Examples**:
- `=:BTC.BTC:bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh`
- `=:ETH.ETH:0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb`
- `=:b:bc1q...` (short form for BTC)

**With affiliate** (optional): `SWAP:ASSET:DESTADDR:AFFILIATE:FEE`

The memo is encoded in an OP_RETURN output (limited to 80 bytes).

## Asset Notation

Assets are identified using the format: `CHAIN.ASSET[-CONTRACT]`

**Examples**:
- `DASH.DASH` - Native DASH
- `BTC.BTC` - Native Bitcoin
- `ETH.ETH` - Native Ethereum
- `ETH.USDT-0xdAC17F958D2ee523a2206206994597C13D831ec7` - USDT on Ethereum
- `ARB.ARB-0x912ce59144191c1204e64559fe8253a0e49e6548` - ARB token on Arbitrum

## Important Considerations

### Security & Validation

1. **Always check `halted` status** before sending funds
   - Source vault halted → reject swap
   - Destination vault halted → reject swap
   - Implementation: `MayaWebApi.getSwapInfo()` lines 215-234

2. **Respect dust thresholds**
   - DASH: 10,000 duffs (0.0001 DASH)
   - BTC: 10,000 sats
   - Amounts below threshold will fail

3. **Quote expiry**
   - Quotes expire (typically 10 minutes)
   - Do not cache or reuse old quotes
   - Check `expiry` timestamp before using

4. **Inbound address changes**
   - Do not cache vault addresses
   - Always fetch fresh addresses before swaps
   - Sending to old addresses results in loss of funds

### Fee Calculation

Total fees include:
- **Inbound Fee**: Transaction fee to send to vault (DASH network fee)
- **Liquidity Fee**: Slippage based on pool depth
- **Outbound Fee**: Fee to receive on destination chain
- **Affiliate Fee**: Optional affiliate commission

The `getSwapQuote` endpoint returns all fees in the `fees` object.

### Error Handling

**Error Types** (from `MayaErrorType`):
- Trading halted
- Invalid address
- Amount below minimum
- Quote generation failure
- Network errors

All errors are logged via `AnalyticsService` (except IOException).

## Multi-Currency Support

### Cryptocurrencies
Supports any blockchain integrated with Maya Protocol:
- DASH, BTC, ETH, ARB
- ERC-20 tokens on Ethereum
- Arbitrum tokens
- Other Maya-supported chains

### Fiat Currencies
Dynamic support for any fiat currency via exchange rate APIs with fallback:
1. CurrencyBeaconAPI (primary)
2. FreeCurrencyAPI (secondary)
3. ExchangeRateAPI (tertiary)

Exchange rates are cached with expiration tracking.

## Advanced Features

- **State Management**: Kotlin Flow/StateFlow for reactive updates
- **Auto-refresh**: Pool info updates every 30 seconds
- **Exchange Rate Fallback**: 3-tier API fallback system
- **Maximum Balance Swaps**: Special handling for "send all" scenarios
- **Analytics Integration**: Error tracking and logging
- **Wallet Integration**: Hooks into WalletDataProvider and transaction services

## API Compatibility Status

**Last Verified**: May 2026

All endpoints are functional and compatible with current data models:

✅ **InboundAddress** - Compatible (API has extra `router` field, safely ignored)
✅ **PoolInfo** - Migrated to mayanode `/pools` (Midgard removed); USD pricing now derived client-side from USDT/USDC pools
✅ **SwapQuote** - Compatible (extra streaming fields safely ignored)
✅ **Network** - Compatible
✅ **Transaction Status** - Compatible

**Recent Changes**:
- Removed Midgard v2 (`midgard.mayachain.info`) as a data source — pools are now fetched from mayanode
- `PoolInfo` model rewritten to match mayanode's snake_case schema (`balance_cacao`, `balance_asset`, etc.); legacy fields like `assetPriceUSD`, `assetDepth`, `poolAPY`, `volume24h` are no longer available
- USD prices are computed from USDT/USDC pool depths using a liquidity-weighted cross-product (see `MayaViewModel.applyPoolPrices`)
- `FiatExchangeRateAggregatedProvider.observeFiatRate` now filters by the requested `currencyCode` so flat-mapped consumers don't receive rates for the wrong currency

**THORChain version updates** (still applicable):
- Deprecated: QueryObservedTx BlockHeight and FinaliseHeight fields
- Added: Streaming swap support
- Added: Router field for EVM chains

## Testing Endpoints

You can test the API endpoints directly:

```bash
# Get pools (mayanode — replaces the old Midgard endpoint)
curl "https://mayanode.mayachain.info/mayachain/pools"

# Get inbound addresses
curl "https://mayanode.mayachain.info/mayachain/inbound_addresses"

# Get swap quote
curl "https://mayanode.mayachain.info/mayachain/quote/swap?from_asset=DASH.DASH&to_asset=BTC.BTC&amount=100000000&destination=bc1q..."

# Get network info
curl "https://mayanode.mayachain.info/mayachain/network"
```

## Official Documentation

- **Main Docs**: https://docs.mayaprotocol.com/
- **Querying MAYAChain**: https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/querying-mayachain
- **Swagger API**: https://mayanode.mayachain.info/mayachain/doc
- **Quickstart Guide**: https://docs.mayaprotocol.com/mayachain-dev-docs/introduction/swapping-guide/quickstart-guide
- **Version Updates**: https://docs.mayaprotocol.com/mayachain-dev-docs/protocol-development/thorchain-version-updates-i

## References

- Maya Protocol: https://www.mayaprotocol.com/
- Maya Protocol GitLab: (check official docs for repository links)
- Based on THORChain Protocol: https://thorchain.org/