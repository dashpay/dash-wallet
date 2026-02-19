# Payment Methodology

This document describes the payment architecture and flow in the Dash wallet, focusing on three key classes: `SendCoinsTaskRunner`, `SendCoinsViewModel`, and `PaymentProtocolViewModel`.

## Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        UI Layer                                 │
├──────────────────────────┬──────────────────────────────────────┤
│   SendCoinsViewModel     │     PaymentProtocolViewModel         │
│   (Standard Payments)    │     (BIP70/BIP270 Payments)          │
└──────────────────────────┴──────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    SendCoinsTaskRunner                          │
│                 (Core Payment Execution)                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Wallet / Network                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## SendCoinsTaskRunner

**Location:** `wallet/src/de/schildbach/wallet/payments/SendCoinsTaskRunner.kt`

### Purpose
The core payment execution engine. Implements `SendPaymentService` interface and handles all low-level transaction creation, signing, and broadcasting.

### Key Responsibilities

1. **Transaction Creation** - Creates `SendRequest` objects from various inputs
2. **CoinJoin Integration** - Manages privacy-enhanced coin selection
3. **Payment Protocol** - Handles BIP70/BIP270 direct payments
4. **Transaction Signing** - Signs transactions using wallet encryption key
5. **Broadcasting** - Commits and broadcasts transactions to the network

### CoinJoin Mode

The class tracks CoinJoin state via two flows:
- `coinJoinMode` - Current CoinJoin configuration (NONE, INTERMEDIATE, etc.)
- `coinJoinMixingState` - Current mixing status (NOT_STARTED, MIXING, FINISHING)

CoinJoin sending is active when:
```kotlin
coinJoinSend = coinJoinMode != CoinJoinMode.NONE && coinJoinMixingState != MixingStatus.FINISHING
```

When CoinJoin is active:
- Uses `CoinJoinCoinSelector` to select only mixed coins
- Can use greedy algorithm (`useCoinJoinGreedy`) to avoid change outputs
- Sets `returnChange = false` to create changeless transactions

### SendRequest Creation Methods

#### 1. `createSendRequest(address, amount, ...)`
Creates a simple send request to an address.

```kotlin
fun createSendRequest(
    address: Address,
    amount: Coin,
    coinSelector: CoinSelector? = null,
    emptyWallet: Boolean = false,
    forceMinFee: Boolean = true,
    canSendLockedOutput: Predicate<TransactionOutput>? = null,
    useCoinJoinGreedy: Boolean = true
): SendRequest
```

#### 2. `createSendRequest(mayEditAmount, paymentIntent, ...)`
Creates a send request from a PaymentIntent (parsed from URI, QR code, etc.).

```kotlin
fun createSendRequest(
    mayEditAmount: Boolean,
    paymentIntent: PaymentIntent,
    signInputs: Boolean,
    forceEnsureMinRequiredFee: Boolean,
    useCoinJoinGreedy: Boolean = true
): SendRequest
```

#### 3. `createAssetLockSendRequest(...)`
Creates a send request for asset lock transactions (Platform identity top-ups).

```kotlin
fun createAssetLockSendRequest(
    mayEditAmount: Boolean,
    paymentIntent: PaymentIntent,
    signInputs: Boolean,
    forceEnsureMinRequiredFee: Boolean,
    topUpKey: ECKey,
    useCoinJoinGreedy: Boolean = true
): SendRequest
```

### Payment Flows

#### Standard Payment Flow
```
sendCoins(address, amount, ...)
  → createSendRequest()
  → sendCoins(sendRequest)
  → signSendRequest()
  → wallet.sendCoinsOffline()
  → broadcastTransaction()
```

#### BIP70/BIP270 Payment Flow
```
payWithDashUrl(dashUri)
  → createPaymentRequest()
  → requestPaymentRequest (HTTP)
  → createRequestFromPaymentIntent()
  → sendPayment()
  → directPay()
  → send Payment via HTTP
  → receive PaymentACK
```

### Fee Handling

- Uses `Constants.ECONOMIC_FEE` as base fee per KB
- Checks for dust outputs and retries with `ensureMinRequiredFee = true`
- For CoinJoin: checks if fee exceeds `MAX_NO_CHANGE_FEE` (0.001 DASH)

---

## CoinJoin Greedy Algorithm Flow

### The Problem

When CoinJoin is active, the wallet uses `CoinJoinCoinSelector` to select only mixed coins. This selector has a **greedy algorithm** mode (`useCoinJoinGreedy = true` by default) that:

1. **Avoids creating change outputs** - Change outputs break privacy because they link the transaction to the sender
2. **Consumes more inputs than necessary** - To hit the exact amount (or close to it), it may add extra inputs
3. **Results in higher fees** - More inputs = larger transaction = higher fee

The excess value that would normally be change instead goes to the miner as fee. This is acceptable for small amounts but becomes problematic when the fee exceeds a threshold.

### The Solution

The wallet implements a **retry mechanism** that detects high fees and falls back to non-greedy selection:

```
┌─────────────────────────────────────────┐
│  Create SendRequest                     │
│  (useCoinJoinGreedy = true)             │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│  wallet.completeTx(sendRequest)         │
│  (Coin selection + fee calculation)     │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│  Check: isFeeTooHigh(tx)?               │
│  (fee > MAX_NO_CHANGE_FEE = 0.001 DASH) │
└────────┬───────────────────┬────────────┘
         │                   │
    No   │                   │ Yes
         ▼                   ▼
┌─────────────────┐  ┌─────────────────────────────┐
│  Use this       │  │  Retry with                 │
│  transaction    │  │  useCoinJoinGreedy = false  │
└─────────────────┘  └──────────────┬──────────────┘
                                    │
                                    ▼
                     ┌─────────────────────────────┐
                     │  wallet.completeTx()        │
                     │  (Creates change output)    │
                     └─────────────────────────────┘
```

### Fee Check Implementation

In `SendCoinsTaskRunner`:

```kotlin
companion object {
    private val MAX_NO_CHANGE_FEE = Coin.valueOf(10_0000) // 0.001 DASH
}

override fun isFeeTooHigh(tx: Transaction): Boolean {
    return if (coinJoinSend) {
        tx.fee > MAX_NO_CHANGE_FEE
    } else {
        false  // Non-CoinJoin transactions always return false
    }
}
```

### Implementation in SendCoinsViewModel

In `executeDryrun()` (lines 437-467):

```kotlin
private fun executeDryrun(amount: Coin) {
    // Step 1: Try with greedy algorithm (default)
    var sendRequest = createSendRequest(
        basePaymentIntent.mayEditAmount(),
        finalPaymentIntent,
        signInputs = false,
        forceEnsureMinRequiredFee = false
    )
    wallet.completeTx(sendRequest)

    // Step 2: Check for dust, retry if needed
    if (checkDust(sendRequest)) {
        sendRequest = createSendRequest(
            basePaymentIntent.mayEditAmount(),
            finalPaymentIntent,
            signInputs = false,
            forceEnsureMinRequiredFee = true
        )
        wallet.completeTx(sendRequest)
    }

    // Step 3: Check for high fee, disable greedy if needed
    if (sendCoinsTaskRunner.isFeeTooHigh(sendRequest.tx)) {
        sendRequest = createSendRequest(
            basePaymentIntent.mayEditAmount(),
            finalPaymentIntent,
            signInputs = false,
            forceEnsureMinRequiredFee = true,
            useGreedyAlgorithm = false  // <-- Disable greedy
        )
        wallet.completeTx(sendRequest)
    }

    dryrunSendRequest = sendRequest
}
```

### Implementation in PaymentProtocolViewModel

In `createBaseSendRequest()` (lines 181-225):

```kotlin
fun createBaseSendRequest(paymentIntent: PaymentIntent) {
    backgroundHandler.post {
        // Step 1: Try with greedy algorithm (default)
        var sendRequest = sendCoinsTaskRunner.createSendRequest(
            false,
            paymentIntent,
            signInputs = false,
            forceEnsureMinRequiredFee = false
        )
        wallet.completeTx(sendRequest)

        // Step 2: Check for dust, retry if needed
        if (checkDust(sendRequest)) {
            sendRequest = sendCoinsTaskRunner.createSendRequest(
                false,
                paymentIntent,
                signInputs = false,
                forceEnsureMinRequiredFee = true
            )
            wallet.completeTx(sendRequest)
        }

        // Step 3: Check for high fee, disable greedy if needed
        if (sendCoinsTaskRunner.isFeeTooHigh(sendRequest.tx)) {
            sendRequest = sendCoinsTaskRunner.createSendRequest(
                false,
                finalPaymentIntent!!,
                signInputs = false,
                forceEnsureMinRequiredFee = true,
                useCoinJoinGreedy = false  // <-- Disable greedy
            )
            wallet.completeTx(sendRequest)
        }

        baseSendRequest = sendRequest
    }
}
```

### Trade-offs

| Mode | Privacy | Fee | Change Output |
|------|---------|-----|---------------|
| Greedy (default) | Higher | Higher (excess becomes fee) | No |
| Non-Greedy | Lower | Normal | Yes (may link to sender) |

The 0.001 DASH threshold balances privacy benefits against excessive fee costs. Below this threshold, the privacy benefit of no change output is worth the extra fee. Above it, the fee is too high and a change output is acceptable.

---

## SendCoinsViewModel

**Location:** `wallet/src/de/schildbach/wallet/ui/send/SendCoinsViewModel.kt`

### Purpose
ViewModel for the standard send coins UI. Handles user input, validation, dry runs, and coordinates with `SendCoinsTaskRunner` for execution.

### State Machine

```
┌─────────┐     ┌─────────┐     ┌──────┐
│  INPUT  │────▶│ SENDING │────▶│ SENT │
└─────────┘     └─────────┘     └──────┘
                     │
                     ▼
                ┌────────┐
                │ FAILED │
                └────────┘
```

### Key Features

#### 1. Dry Run Validation
Before sending, executes a dry run to validate the transaction:

```kotlin
private fun executeDryrun(amount: Coin) {
    // Creates unsigned transaction
    // Checks for dust
    // Checks for high fees (CoinJoin)
    // Stores result in dryrunSendRequest
}
```

The dry run:
- Validates sufficient funds
- Checks for dust outputs
- Verifies fee is acceptable (especially for CoinJoin)
- Debounced (150ms) to avoid excessive calls during amount entry

#### 2. Asset Lock Support
Supports both regular payments and asset lock transactions:

```kotlin
var isAssetLock = false  // Toggle for asset lock mode
```

Asset locks are used for Platform identity top-ups.

#### 3. CoinJoin Awareness
- Tracks `coinJoinActive` state
- Uses `MaxOutputAmountCoinJoinCoinSelector` when CoinJoin is active
- Throws `InsufficientCoinJoinMoneyException` when mixed funds are insufficient

#### 4. DashPay Contact Integration
For identity-based payments:
- Resolves usernames to addresses
- Gets next contact address from Platform
- Loads contact data for UI display

### Payment Methods

#### `signAndSendPayment(editedAmount, exchangeRate, checkBalance)`
Standard payment execution:
1. Creates final SendRequest from payment intent
2. Calls `sendCoinsTaskRunner.sendCoins()`
3. Updates state to SENT or FAILED

#### `signAndSendAssetLock(editedAmount, exchangeRate, checkBalance, key, emptyWallet)`
Asset lock payment execution:
1. Creates asset lock SendRequest
2. Handles empty wallet case (adjusts payload to match output)
3. Calls `sendCoinsTaskRunner.sendCoins()`

---

## PaymentProtocolViewModel

**Location:** `wallet/src/de/schildbach/wallet/ui/send/PaymentProtocolViewModel.kt`

### Purpose
ViewModel for BIP70/BIP270 payment protocol handling. Manages HTTP-based payment requests and direct payments to merchants.

### Payment Protocol Flow

```
┌──────────────────┐
│ Payment Request  │  (BIP72 URL with r= parameter)
│      URL         │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  HTTP Request    │  Request PaymentRequest from merchant
│  (GET)           │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ PaymentRequest   │  Merchant returns payment details
│   Response       │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Create & Sign    │  Build transaction
│   Transaction    │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  HTTP Request    │  Send Payment to merchant
│  (POST)          │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  PaymentACK      │  Merchant acknowledges payment
└──────────────────┘
```

### Key Components

#### 1. Background Handler
Uses a dedicated `HandlerThread` for network operations:
```kotlin
val backgroundThread = HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND)
```

#### 2. Request Payment Request
`requestPaymentRequest(basePaymentIntent)`:
- Fetches PaymentRequest from merchant URL
- Validates it extends the base payment intent (BIP72 trust check)
- Creates base SendRequest on success

#### 3. Create Base SendRequest
`createBaseSendRequest(paymentIntent)`:
- Creates unsigned SendRequest
- Completes transaction (coin selection)
- Checks for dust and high fees
- Retries with adjusted parameters if needed

#### 4. Send Payment
`sendPayment()`:
- Creates final signed SendRequest
- Calls `directPay()` to submit to merchant

#### 5. Direct Pay
`directPay(sendRequest)`:
- Completes transaction
- Creates BIP70 Payment message with refund address
- Sends via HTTP POST
- Handles PaymentACK response

### LiveData Outputs

- `sendRequestLiveData` - Loading/Success/Error state of payment request
- `directPaymentAckLiveData` - Result of direct payment submission
- `exchangeRateData` - Current exchange rate for display

---

## Comparison Summary

| Feature | SendCoinsTaskRunner | SendCoinsViewModel | PaymentProtocolViewModel |
|---------|--------------------|--------------------|--------------------------|
| Layer | Core/Service | UI/ViewModel | UI/ViewModel |
| Payment Type | All | Standard & Asset Lock | BIP70/BIP270 |
| Dry Run | No | Yes | No |
| CoinJoin | Handles selection | Tracks state | Uses via TaskRunner |
| Direct Pay | Implements | No | Uses via HTTP |
| State Machine | No | Yes (INPUT→SENT) | No |
| Contact Support | No | Yes (DashPay) | No |

---

## Usage Examples

### Standard Payment
```kotlin
// In SendCoinsViewModel
val tx = signAndSendPayment(
    editedAmount = Coin.COIN,
    exchangeRate = currentRate,
    checkBalance = true
)
```

### BIP70 Payment
```kotlin
// In PaymentProtocolViewModel
initPaymentIntent(paymentIntent)  // Triggers requestPaymentRequest
// ... wait for sendRequestLiveData success ...
sendPayment()  // Creates and sends payment
// ... wait for directPaymentAckLiveData ...
commitAndBroadcast(sendRequest)  // Broadcast to network
```

### Direct API Payment
```kotlin
// Using SendCoinsTaskRunner directly
val tx = sendCoinsTaskRunner.sendCoins(
    address = recipientAddress,
    amount = Coin.COIN,
    checkBalanceConditions = true
)
```
