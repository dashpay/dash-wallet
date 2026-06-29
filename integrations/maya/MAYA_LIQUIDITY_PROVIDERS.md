# Maya Protocol — Liquidity Providers

This document investigates the **liquidity-provider (LP)** surface of Maya Protocol and how it
maps onto the existing swap integration in the Dash Wallet. It is a feasibility / design
reference, not (yet) an implemented feature.

See also: [`MAYA_PROTOCOL.md`](./MAYA_PROTOCOL.md) for the swap integration this builds on.

## Overview

Providing liquidity reuses the **exact same on-chain mechanism as a swap**: the user sends a
deposit to the chain's inbound vault with an `OP_RETURN` memo telling Maya what to do. The only
thing that changes between a swap and an LP action is the **memo string**.

Everything already built for swaps therefore applies unchanged:

- Inbound vault-address fetching (`GET /mayachain/inbound_addresses`)
- `halted` / trading-paused safety checks
- The `VOUT0 vault / VOUT1 OP_RETURN / VOUT2 change` transaction layout in `MayaBlockchainApi`
- The **no-BIP69 output sorting** rule (Maya requires a specific output order)
- The 80-byte `OP_RETURN` memo limit

## The LP role (how it works conceptually)

Source: [Maya docs — Liquidity Providers](https://docs.mayaprotocol.com/introduction/readme/roles/liquidity-providers).

LPs deposit assets into pools and earn yield from swap fees + system rewards, in exchange for
taking on price exposure (and impermanent loss). Key points:

### Pools always pair against CACAO

Every pool is `ASSET/CACAO`. An LP in the `DASH/CACAO` pool earns rewards in **both DASH and
CACAO**, and CACAO is held as "a redeemable insurance policy whilst they are in the pool." This
is why a true dual-sided position needs CACAO — the wallet's single-sided constraint stands.

### Deposit types

- **Symmetrical** — equal value of both sides (e.g. $1000 DASH + $1000 CACAO).
- **Asymmetrical** — unequal, including **single-sided** (e.g. $2000 DASH + $0 CACAO). The LP
  receives pool ownership accounting for the price slip they create. Maya notes there is "no
  difference between swapping into symmetrical shares then depositing, or depositing
  asymmetrically and being arb'd to symmetrical." Recommended when the pool is already
  imbalanced.
- **Multiple deposits** — rules vary by the initial deposit type. Asymmetric deposits with the
  *non-deposited* asset generally create a **new** LP position rather than topping up; likewise
  a symmetric deposit after a 100%-CACAO deposit creates a new position. (Relevant to our
  address-keying problem below — positions are not always additive.)

### Pool ownership = Liquidity Units

Depositing mints **Liquidity Units** representing the LP's share of the pool. Value per unit is
tracked by **LUVI** (Liquidity Unit Value Index):

```
LUVI = sqrt(AssetDepth × CacaoDepth) / PoolUnits
```

APR is derived by extrapolating the change in LUVI over a window (default 30 days). LUVI rises
from swap fees, block rewards, and donations; falls as synth liability grows. (These are the
`LP_units` / `pool_units` already present in `PoolInfo`.)

### How rewards accrue

Yield is computed **each block** and paid **on withdrawal**, in both CACAO and the paired asset:

- **Blocks with swaps** — rewards split proportionally to **fees collected** per pool.
- **Blocks without swaps** — rewards split proportionally to **pool depth**.
- **Incentive Pendulum** — shifts emissions between node operators (bonded capital) and LPs
  (pooled capital) to keep the system balanced; affects LP yield over time.

Reward sources: a portion of each swap **slip** retained in the pool, MAYAChain **block
rewards**, and long-term payouts from the **token reserve**.

### Deposit / withdrawal rules

- **No minimum deposit**, but it must cover the transaction + withdrawal fees.
- **Non-custodial** — only the original depositor can withdraw.
- **Withdraw anytime** — the only wait is on-chain confirmation time; no lockup/cooldown.
- A **withdrawal fee** is applied and placed into the network reserve.
- Each `ADD` **resets the Impermanent-Loss-Protection timer** (per the docs).

### Risks & costs

- **Impermanent loss** — LPs are not entitled to a fixed quantity back; they get their fair
  share of the pool's earnings and *final* balances. IL can exceed earned rewards.
- **Costs** — direct: network withdrawal fee; indirect: IL from price divergence.
- **No misconduct penalties** for LPs.

### Strategy framing

- **Passive** — deep-liquidity pools to minimise risk.
- **Active** — shallow but high-demand pools for bigger slips/fees (higher risk).

## Terminology note (THORChain vs. Maya)

The official memo docs
([transaction-memos](https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/transaction-memos))
are written in THORChain terms. For Maya, substitute:

| THORChain term      | Maya equivalent     |
|---------------------|---------------------|
| `RUNE`              | `CACAO`             |
| THORChain address   | `MAYA` chain address|
| `THORName`          | `MAYAName`          |

Maya's native chain is `MAYA` and its native asset is `CACAO`.

## LP Transaction Memos

### ADD LIQUIDITY

**Format:** `ADD:POOL:PAIREDADDR:AFFILIATE:FEE`
**Abbreviations:** `a`, `+`

| Field         | Required | Notes |
|---------------|----------|-------|
| `POOL`        | yes      | Target pool, e.g. `DASH.DASH` (may be shortened) |
| `PAIREDADDR`  | no       | Counterparty address for **two-sided** deposits. On an external chain it links to a `MAYA` address; on `MAYA` it links to the external address. **Omit for single-sided.** |
| `AFFILIATE`   | no       | `MAYAName` or `MAYA` address |
| `FEE`         | no       | Affiliate fee, 0–1000 basis points |

**Examples:**
- `ADD:POOL` — single-sided deposit
- `+:POOL:PAIREDADDR` — both-sided deposit
- `+:POOL:PAIREDADDR:AFFILIATE:FEE` — both-sided with affiliate

### WITHDRAW LIQUIDITY

**Format:** `WD:POOL:BASISPOINTS:ASSET`
**Abbreviations:** `-`, `wd`, `WITHDRAW`

| Field         | Required | Notes |
|---------------|----------|-------|
| `POOL`        | yes      | Source pool (may be shortened) |
| `BASISPOINTS` | yes      | 0–10,000, where 10,000 = 100% withdrawal |
| `ASSET`       | no       | Single-sided withdrawal to the named asset (CACAO or the pool asset). Omit for dual-sided. |

**Examples:**
- `WITHDRAW:POOL:10000` — dual-sided 100% exit
- `-:POOL:1000` — dual-sided 10% exit
- `wd:DASH.DASH:5000:DASH.DASH` — 50% exit, received as DASH

A withdraw deposit carries **no value** — it is a dust transaction to the vault that only
delivers the instruction. Maya pays the redeemed amount out from the pool.

### Related node memos (out of scope)

These require CACAO and a `MAYA` account, so they are **not feasible for a DASH-only wallet**:

- **BOND:** `BOND:ASSET:UNITS:NODEADDR:PROVIDER:FEE`
- **UNBOND:** `UNBOND:NODEADDR:AMOUNT`
- **LEAVE:** `LEAVE:NODEADDR`

## Single-sided vs. dual-sided for this wallet

- **Single-sided DASH LP** (`ADD:DASH.DASH`) — deposit only DASH. No counterparty address
  needed. **This is the only realistic path for the current wallet.**
- **Dual-sided** (`+:DASH.DASH:PAIREDADDR`) — deposit DASH *and* CACAO, where `PAIREDADDR`
  links the DASH-side deposit to a `MAYA`-chain address supplying the CACAO half. The wallet
  has **no CACAO/MAYA account**, so dual-sided is not feasible without standing up CACAO
  custody.
- **Exit** is correspondingly **single-sided to DASH** (`wd:DASH.DASH:<bps>:DASH.DASH`).

> Open question: confirm Maya currently permits single-sided LP on the `DASH.DASH` pool. Some
> pools restrict single-sided adds.

## What already exists (reusable)

| Capability                          | Where |
|-------------------------------------|-------|
| Pool data incl. `lpUnits`, `poolUnits`, `balanceCacao`, `balanceAsset`, `bondable` | `model/PoolInfo.kt` |
| Pool fetch + 30s auto-refresh + pricing | `api/MayaApiAggregator.kt`, `api/MayaWebApi.kt` |
| Inbound vault addresses + `halted` checks | `api/MayaWebApi.getInboundAddresses()`, `model/InboundAddress.kt` |
| Build + broadcast vault+memo+change tx | `api/MayaBlockchainApi.kt`, `api/MayaBlockchainApiImpl.kt` |

An LP deposit is the same `MayaBlockchainApi` call as a swap, with a different memo.

## LP yield / APY

**Yes, Maya publishes an LP APY — but only via Midgard, not mayanode.**

LPs earn from two sources, rolled into a single APY figure:
1. **Swap fees** charged on the pool, and
2. **System income** (CACAO block rewards) allocated to pools by depth.

### Where the numbers live

- **mayanode `/pools`** (the source this branch currently uses) exposes **no yield fields** —
  only balances, `LP_units`, `pool_units`, `status`, `bondable`, synth accounting. There is no
  APY/APR/earnings/volume on mayanode.
- **Midgard v2** exposes the yield data:
  - All pools: `GET https://midgard.mayachain.info/v2/pools`
  - One pool, windowed: `GET https://midgard.mayachain.info/v2/pool/DASH.DASH?period=30d`
    (periods: `1h`, `24h`, `7d`, `14d`, `30d`, `90d`, `180d`, `365d`, `all`)

### Relevant Midgard fields

| Field                            | Meaning |
|----------------------------------|---------|
| `annualPercentageRate` / `poolAPY` | LP APY (fees + rewards). `poolAPY` duplicates `annualPercentageRate`. Windowed by `?period=`. |
| `earningsAnnualAsPercentOfDepth` | Annualized earnings ÷ pool depth |
| `earnings`                       | Raw earnings (CACAO base units) over the window |
| `volume24h`                      | 24h swap volume |
| `saversAPR`                      | Savers yield (separate product; `0` for DASH today) |
| `assetPriceUSD`, `assetDepth`, `runeDepth`, `liquidityUnits` | depth/price inputs |

### Live sample (DASH.DASH, captured 2026-06-09)

```
annualPercentageRate / poolAPY : 0.1581   (~15.8%, default/all-time window)
  same with ?period=30d        : 0.0797   (~8.0%, trailing 30 days)
earningsAnnualAsPercentOfDepth : 0.147    (~14.7%)
saversAPR                      : 0
```

APY moves with the chosen `period` window — quote the window alongside the number.

### Implication for this wallet

This branch **removed Midgard** when `PoolInfo` migrated to mayanode (`poolAPY`, `volume24h`,
`assetPriceUSD`, `assetDepth` were dropped — see `MAYA_PROTOCOL.md`). mayanode cannot supply
APY. So surfacing LP yield requires **re-introducing a single Midgard call** (e.g. a
`MidgardEndpoint.getPools()` or per-pool fetch) purely for the yield figures, separate from the
mayanode pool/price path the swap UI uses.

## What is missing / the real work

1. **Position tracking** — no query exists for "what LP units do I own." Maya exposes:
   - `GET /mayachain/liquidity_provider/{pool}/{address}` — a single provider's position
   - `GET /mayachain/liquidity_providers/{pool}` — all providers in a pool

   Neither is wired up. A new endpoint + model is needed to show position, pool share, and
   redeemable value.
2. **Withdraw is address-keyed, not UTXO-keyed** — Maya tracks the LP by the **DASH address**
   that deposited. With an HD wallet rotating addresses, we must deposit from (and withdraw
   to) a known/consistent address, or record which address holds each position.
3. **No CACAO side** — confines us to single-sided DASH add and single-sided DASH exit.
4. **No LP quote endpoint** — swaps use `/quote/swap`; there is no equivalent LP slippage quote
   in the code. Units/share would be estimated client-side from pool depths.
5. **UI** — entirely new. The current Maya UI is swap-only.

## Backend applicability

LP is a **Maya-native-only** feature. SwapKit is a swap *aggregator* and does not expose LP, so
any LP action must go direct to `mayanode` regardless of the configured swap backend.

## Scope recommendation

Target **single-sided DASH LP**: add, withdraw, and a position view. Bonding/nodes and savers
need CACAO and are out of scope for a DASH-only wallet.

Open questions to resolve before implementation:

- Is the goal to **earn yield by providing DASH liquidity** (single-sided add/withdraw +
  position view), or something broader?
- Confirm Maya allows single-sided LP on the `DASH.DASH` pool today.
- Decide how to pin the deposit/withdraw address so positions remain queryable.

## References

- Liquidity Providers role: https://docs.mayaprotocol.com/introduction/readme/roles/liquidity-providers
- Transaction memos: https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/transaction-memos
- Querying MAYAChain: https://docs.mayaprotocol.com/mayachain-dev-docs/concepts/querying-mayachain
- Swagger API: https://mayanode.mayachain.info/mayachain/doc
- Main docs: https://docs.mayaprotocol.com/
