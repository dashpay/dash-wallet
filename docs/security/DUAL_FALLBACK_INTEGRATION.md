# Dual-Fallback Encryption System - Integration Complete

## What Was Implemented

The dual-fallback encryption system provides three layers of protection for PIN and wallet password:

1. **Primary (Layer 1)**: Android KeyStore (hardware-backed)
2. **Fallback #1 (Layer 2)**: PIN-derived encryption (PBKDF2 with 100,000 iterations)
3. **Fallback #2 (Layer 3)**: Mnemonic-derived encryption (last resort recovery)

### Key Features

- **Automatic Failover**: If KeyStore fails, automatically tries PIN-based recovery
- **Self-Healing**: When fallback recovery succeeds, automatically restores KeyStore encryption
- **Backward Compatible**: Works with existing encrypted data
- **Progressive Migration**: Adds fallbacks lazily as data becomes available

## Files Created

### Core Components

1. **PinBasedKeyProvider.kt** (`wallet/src/.../security/`)
   - Derives encryption keys from user's PIN using PBKDF2
   - 100,000 iterations to prevent brute force attacks

2. **DualFallbackEncryptionProvider.kt** (`wallet/src/.../security/`)
   - Main encryption provider with three-layer system
   - Handles automatic failover and self-healing
   - Storage prefixes:
     - `primary_` - KeyStore encrypted
     - `fallback_pin_` - PIN-derived encrypted (wallet password only)
     - `fallback_mnemonic_` - Mnemonic-derived encrypted (PIN + wallet password)

3. **DualFallbackMigration.kt** (`wallet/src/.../security/`)
   - Handles migration to dual-fallback system
   - Two-phase approach:
     - Phase 1: Add PIN fallback when user enters PIN
     - Phase 2: Add mnemonic fallback when wallet loads

### Updated Components

1. **EncryptionProviderFactory.java**
   - Creates DualFallbackEncryptionProvider instead of old provider

2. **SecurityGuard.java**
   - Added recovery methods:
     - `recoverPasswordWithPin(String pin)`
     - `recoverPinWithMnemonic(List<String> mnemonicWords)`
     - `recoverPasswordWithMnemonic(List<String> mnemonicWords)`
   - Added fallback setup methods:
     - `ensurePinFallback(String pin)`
     - `ensureMnemonicFallbacks(List<String> mnemonicWords)`
   - Updated `isConfigured()` and `removeKeys()` for dual-fallback prefixes

3. **CheckPinLiveData.kt** ✅
   - **ALREADY INTEGRATED**: Automatic PIN-based fallback recovery
   - When KeyStore PIN check fails, automatically tries PIN-based recovery
   - Self-healing restores KeyStore after successful recovery
   - Adds PIN fallback when new PIN is set

## Integration Status

### ✅ Completed

1. **CheckPinDialog** - Uses CheckPinLiveData (automatic fallback ✓)
2. **LockScreenActivity** - Uses CheckPinLiveData (automatic fallback ✓)
3. **PIN-based recovery** - Fully automatic, no UI changes needed
4. **Self-healing** - Automatic restoration of KeyStore encryption

### 🔄 Remaining Tasks

#### 1. Add Mnemonic Fallback After Wallet Loads

**Location**: `WalletApplication.java` or similar wallet initialization code

**What to do**: After wallet is assigned/loaded, call `ensureMnemonicFallbacks()` to add mnemonic-based fallback encryption.

**Example**:
```java
// In WalletApplication.java, after wallet is assigned:
this.wallet = wallet;

// Add mnemonic-based fallbacks
try {
    SecurityGuard securityGuard = SecurityGuard.getInstance();
    if (wallet.getKeyChainSeed() != null && wallet.getKeyChainSeed().getMnemonicCode() != null) {
        List<String> mnemonicWords = wallet.getKeyChainSeed().getMnemonicCode();
        securityGuard.ensureMnemonicFallbacks(mnemonicWords);
        log.info("Mnemonic-based fallbacks ensured");
    }
} catch (Exception e) {
    log.error("Failed to ensure mnemonic-based fallbacks", e);
    // Don't crash - app can continue with primary+PIN fallback only
}
```

**Why this is needed**: Mnemonic fallback is the nuclear option - if both KeyStore and PIN fail, user can recover everything with their recovery phrase.

#### 2. (Optional) Add Mnemonic Recovery UI

If you want users to recover with their mnemonic phrase when both KeyStore and PIN fail:

**Create Recovery Flow**:
1. User enters PIN → fails
2. Show "Forgot PIN?" option
3. User enters recovery phrase
4. Call `recoverWithMnemonic()` to recover both PIN and wallet password

**Example**:
```java
try {
    SecurityGuard securityGuard = SecurityGuard.getInstance();

    // Recover both PIN and wallet password
    String recoveredPin = securityGuard.recoverPinWithMnemonic(mnemonicWords);
    String recoveredPassword = securityGuard.recoverPasswordWithMnemonic(mnemonicWords);

    // Use recovered credentials to unlock wallet
    // Self-healing has already occurred

} catch (SecurityGuardException e) {
    // Recovery failed - show error to user
}
```

## How It Works

### Normal Operation (KeyStore Healthy)

```
User enters PIN
    ↓
CheckPinLiveData.checkPin()
    ↓
SecurityGuard.checkPin() → SUCCESS
    ↓
User authenticated ✓
```

### KeyStore Corrupted (Automatic Fallback)

```
User enters PIN
    ↓
CheckPinLiveData.checkPin()
    ↓
SecurityGuard.checkPin() → FAILS (KeyStore corrupted)
    ↓
Automatic PIN-based fallback:
SecurityGuard.recoverPasswordWithPin(pin)
    ↓
DualFallbackEncryptionProvider.decryptWithPin()
    ↓
Self-healing: tryHealPrimary() → Restore KeyStore encryption
    ↓
User authenticated ✓
KeyStore restored ✓
```

### Complete Failure (Mnemonic Recovery)

```
User enters PIN
    ↓
Both KeyStore AND PIN fallback fail
    ↓
User clicks "Forgot PIN?"
    ↓
User enters recovery phrase
    ↓
SecurityGuard.recoverPinWithMnemonic(mnemonicWords)
SecurityGuard.recoverPasswordWithMnemonic(mnemonicWords)
    ↓
Self-healing: Restore both KeyStore and PIN fallback
    ↓
User authenticated ✓
Everything restored ✓
```

## Storage Architecture

### SharedPreferences Keys

For each encrypted value (UI_PIN_KEY_ALIAS, WALLET_PASSWORD_KEY_ALIAS):

- `primary_<alias>` - KeyStore encrypted (Base64)
- `fallback_pin_<alias>` - PIN-derived encrypted (Base64) - wallet password only
- `fallback_mnemonic_<alias>` - Mnemonic-derived encrypted (Base64) - both PIN and wallet password

### Encrypted Data Format

Each encrypted value is stored as Base64 string containing:
```
[IV_LENGTH (4 bytes)][IV (12 bytes)][ENCRYPTED_DATA (variable)]
```

## Migration Strategy

### Phase 1: Existing Users on Upgrade

When app upgrades to this version:

1. **Primary encryption already exists** (old format)
2. **PIN fallback**: Added next time user enters PIN
3. **Mnemonic fallback**: Added when wallet loads (after you implement step 1 above)

### Phase 2: New Users

1. User sets up wallet with PIN
2. `CheckPinLiveData.setupSecurityGuard()` saves PIN and password
3. Automatically calls `ensurePinFallback(pin)` to add PIN fallback
4. When wallet loads, `ensureMnemonicFallbacks()` adds mnemonic fallback

## Testing

### Test Scenarios

1. **Normal Operation**
   - Enter correct PIN → Should work normally

2. **KeyStore Corruption Simulation**
   - Clear app data → Trigger KeyStore corruption
   - Enter PIN → Should automatically recover via PIN fallback
   - Check logs for "PIN-based fallback recovery succeeded"

3. **Complete Recovery**
   - Clear all encrypted data except fallbacks
   - Enter recovery phrase → Should recover everything

4. **Migration**
   - Install old version, set up wallet
   - Upgrade to new version
   - Enter PIN → Should add PIN fallback
   - Wallet loads → Should add mnemonic fallback

### Log Messages to Watch

- `PIN-based fallback recovery succeeded` - PIN fallback worked
- `Primary encryption healed` - Self-healing successful
- `PIN-based fallback ensured for wallet password` - Fallback added
- `Mnemonic-based fallbacks ensured` - Mnemonic fallback added

## Security Considerations

### Key Derivation Strength

- **PIN-based**: PBKDF2-HMAC-SHA256, 100,000 iterations
- **Mnemonic-based**: PBKDF2-HMAC-SHA256, 100,000 iterations
- **Salt**: Unique per key alias (`dash_wallet_pin_<alias>`, `dash_wallet_<alias>`)

### Attack Vectors

1. **Brute Force PIN**: Mitigated by 100,000 PBKDF2 iterations + PinRetryController lockout
2. **Brute Force Mnemonic**: Computationally infeasible (2048-word dictionary, 12-24 words)
3. **Physical Access**: All sensitive data encrypted, requires PIN or mnemonic

### Best Practices

- PIN fallback only stores wallet password (not the PIN itself)
- Mnemonic fallback stores both (for complete recovery)
- Self-healing ensures KeyStore is restored when possible
- All encryption uses AES-256-GCM (authenticated encryption)

## Troubleshooting

### Issue: "PIN-based fallback not available"

**Cause**: User hasn't entered PIN since upgrade
**Solution**: User needs to enter PIN once to add PIN fallback

### Issue: "Mnemonic-based fallback not available"

**Cause**: `ensureMnemonicFallbacks()` not called after wallet loads
**Solution**: Implement step 1 from "Remaining Tasks" above

### Issue: Recovery fails even with correct PIN/mnemonic

**Cause**: Fallback data corrupted or never created
**Solution**: User must restore from backup or recovery phrase (fresh start)

## Summary

✅ **PIN-based automatic recovery**: Fully implemented and integrated
✅ **Self-healing**: Automatic KeyStore restoration
✅ **CheckPinDialog & LockScreenActivity**: Both automatically use fallback recovery

🔄 **TODO**: Add `ensureMnemonicFallbacks()` call after wallet loads (5 minutes of work)

The system is production-ready for PIN-based fallback. Mnemonic fallback just needs the one-line integration in wallet initialization.