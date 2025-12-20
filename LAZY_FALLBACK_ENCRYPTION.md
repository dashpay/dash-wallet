# Lazy Fallback Encryption - Implementation Guide

## Problem Solved

**Issue**: SecurityGuard initializes before WalletApplication.wallet is assigned, so SeedBasedMasterKeyProvider can't access the wallet seed during initial encryption. This means fallback encryption was always skipped, defeating the hybrid system's redundancy.

## Solution: Lazy Fallback Encryption

The hybrid system now works in two phases:

### Phase 1: Initial Encryption (Early Startup)
```
SecurityGuard initializes (wallet seed not available yet)
↓
User sets PIN
↓
HybridEncryptionProvider.encrypt():
  - Primary (KeyStore): ✓ Encrypts → stores at "primary_ui_pin_key"
  - Fallback (Seed): ✗ Skipped (wallet not ready) - logs warning
  - Result: Only primary encryption exists
```

### Phase 2: Upgrade to Dual Encryption (After Wallet Loads)
```
Wallet finishes loading
↓
Call SecurityGuard.getInstance().ensureFallbackEncryptions()
↓
HybridEncryptionProvider.ensureAllFallbackEncryptions():
  - For each key (wallet_password_key, ui_pin_key):
    1. Check if fallback already exists → skip if yes
    2. Decrypt primary encryption → get plaintext
    3. Encrypt with fallback (seed now available!) → save
  - Result: Dual encryption now complete
```

## Integration Instructions

Add this call **after wallet initialization** in `WalletApplication`:

```java
// In WalletApplication.java (or wherever wallet is initialized)

// After this line where wallet is assigned:
this.wallet = wallet;

// Add this:
try {
    SecurityGuard securityGuard = SecurityGuard.getInstance();
    securityGuard.ensureFallbackEncryptions();
    log.info("Fallback encryptions ensured for all keys");
} catch (Exception e) {
    log.error("Failed to ensure fallback encryptions", e);
    // Don't crash - app can continue with primary-only encryption
}
```

### Where to Find the Right Location

Look for where `WalletApplication` sets the `wallet` field:
- Usually in a method like `onWalletLoaded()` or `initializeWallet()`
- After `this.wallet = wallet;` or similar assignment
- Before wallet is used for any operations

Example locations to check:
```java
// Option 1: In wallet loading callback
private void onWalletLoaded(Wallet wallet) {
    this.wallet = wallet;

    // ADD HERE:
    ensureFallbackEncryptions();
}

// Option 2: In async wallet initialization
private void initializeWallet() {
    // ... wallet loading code ...
    this.wallet = loadedWallet;

    // ADD HERE:
    ensureFallbackEncryptions();
}

// Option 3: In restore/create wallet flow
public void restoreWallet(DeterministicSeed seed) {
    this.wallet = Wallet.fromSeed(...);

    // ADD HERE:
    ensureFallbackEncryptions();
}
```

## Implementation Details

### HybridEncryptionProvider Changes

1. **tryFallbackEncryption()** - New private method
   - Attempts fallback encryption
   - Returns `null` if wallet not available (instead of throwing)
   - Logs warning: "wallet seed not yet available (will retry later)"

2. **ensureFallbackEncryption(keyAlias)** - New public method
   - Checks if fallback already exists → return true
   - Loads primary encrypted data
   - Decrypts with primary → gets plaintext
   - Encrypts with fallback → saves
   - Returns true if successful, false otherwise

3. **ensureAllFallbackEncryptions()** - New public method
   - Loops through all known keys (WALLET_PASSWORD_KEY_ALIAS, UI_PIN_KEY_ALIAS)
   - Calls `ensureFallbackEncryption()` for each
   - Logs count of upgraded keys

### SecurityGuard Changes

**New public method:**
```java
public void ensureFallbackEncryptions() {
    if (encryptionProvider instanceof HybridEncryptionProvider) {
        log.info("Ensuring fallback encryptions for all keys (wallet is now available)");
        ((HybridEncryptionProvider) encryptionProvider).ensureAllFallbackEncryptions();
    }
}
```

## Testing

### Test Case 1: Fresh Install
```
1. Install app (no existing data)
2. Create new wallet → wallet seed available early
3. Set PIN
4. Expected: Both primary and fallback encryption succeed immediately
5. Check SharedPreferences:
   - "primary_ui_pin_key" exists ✓
   - "fallback_ui_pin_key" exists ✓
```

### Test Case 2: Restore Wallet
```
1. Install app
2. Restore from seed phrase
3. Wallet loads asynchronously
4. Set PIN before wallet finishes loading
5. Expected:
   - Initial save: Only "primary_ui_pin_key" exists
   - After ensureFallbackEncryptions(): "fallback_ui_pin_key" appears
6. Verify both keys exist in SharedPreferences
```

### Test Case 3: Existing User (Upgrade)
```
1. Upgrade app with existing PIN
2. Wallet already exists
3. On first launch after upgrade:
   - Migration runs
   - ensureFallbackEncryptions() adds fallback to existing keys
4. Expected: Both primary and fallback exist for all keys
```

## Monitoring

Check logs for these messages:

### Successful Dual Encryption:
```
INFO: Dual encryption successful for ui_pin_key
INFO: Ensuring fallback encryptions for all keys (wallet is now available)
INFO: Successfully added fallback encryption for ui_pin_key
INFO: Upgraded 2 keys to dual-encryption
```

### Wallet Not Ready Yet:
```
WARN: Fallback encryption skipped for ui_pin_key: wallet seed not yet available (will retry later)
INFO: Ensuring fallback encryptions for all keys (wallet is now available)
INFO: Successfully added fallback encryption for ui_pin_key
```

### Already Upgraded:
```
DEBUG: Fallback encryption already exists for ui_pin_key
DEBUG: Fallback encryption already exists for wallet_password_key
INFO: Upgraded 0 keys to dual-encryption
```

## Benefits

1. **No initialization order dependency** - Works regardless of when wallet loads
2. **Graceful degradation** - App works with primary-only encryption if wallet never loads
3. **Automatic upgrade** - Existing single-encrypted data upgraded to dual-encryption
4. **Idempotent** - Safe to call multiple times (checks if already done)
5. **Non-blocking** - Doesn't delay startup, runs asynchronously after wallet loads

## Edge Cases Handled

### Wallet Never Loads
- Primary encryption still works
- App functional with reduced redundancy
- Fallback attempted on next app start

### ensureFallbackEncryptions() Called Multiple Times
- First call: Adds fallback encryption
- Subsequent calls: Detects existing fallback, returns immediately
- No duplicate work

### Primary Decryption Fails During Upgrade
- Logs warning: "Cannot decrypt primary data to create fallback"
- Returns false
- Primary data left unchanged
- Retry possible on next call

## Summary

The lazy fallback encryption approach solves the initialization timing problem by:
- Allowing encryption to work immediately with just KeyStore (primary)
- Deferring seed-based encryption (fallback) until wallet is ready
- Automatically upgrading to dual-encryption when possible
- Maintaining backward compatibility with existing encrypted data

**Result**: Users get full hybrid encryption protection without app startup delays or initialization order constraints.