# Hybrid Encryption System - Implementation Summary

## Overview

This implementation combines **Android KeyStore encryption** (primary) with **wallet seed-derived encryption** (fallback) to provide maximum security and reliability. The key feature is **automatic self-healing** when KeyStore failures occur.

## Architecture

### Components Created

1. **MasterKeyProvider** (`common/src/.../MasterKeyProvider.kt`)
   - Interface for providing encryption keys
   - Allows different key derivation strategies

2. **SeedBasedMasterKeyProvider** (`wallet/src/.../SeedBasedMasterKeyProvider.kt`)
   - Derives encryption keys from BIP39 wallet seed
   - Uses custom BIP32 derivation paths:
     - `m/9999'/0'/0'` for wallet password
     - `m/9999'/0'/1'` for UI PIN
   - Keys are always recoverable via seed phrase

3. **PasswordBasedEncryptionProvider** (`wallet/src/.../PasswordBasedEncryptionProvider.kt`)
   - Software-based encryption using seed-derived keys
   - Proper GCM usage: unique IV per encryption
   - IV embedded in encrypted data (no separate storage)
   - No Android KeyStore dependency

4. **ModernEncryptionProvider** (updated)
   - Fixed IV reuse vulnerability
   - Now generates unique IV per encryption
   - IV embedded in ciphertext
   - Proper GCM compliance

5. **HybridEncryptionProvider** (`wallet/src/.../HybridEncryptionProvider.kt`)
   - **Core component** that orchestrates everything
   - Dual encryption: every save encrypts with BOTH systems
   - Smart decryption: tries KeyStore first, falls back if needed
   - **Self-healing**: Automatically re-encrypts with KeyStore after recovery

6. **HybridEncryptionMigration** (`wallet/src/.../HybridEncryptionMigration.kt`)
   - Migrates existing users from old format
   - Handles legacy shared-IV decryption
   - Re-encrypts with new hybrid system
   - Safe: runs only once, fails gracefully

## How It Works

### Storage Architecture

The hybrid system stores encrypted data with prefixes in SharedPreferences:
- **Primary**: `primary_wallet_password_key`, `primary_ui_pin_key` (KeyStore-encrypted)
- **Fallback**: `fallback_wallet_password_key`, `fallback_ui_pin_key` (Seed-encrypted)
- **Legacy**: `wallet_password_key`, `ui_pin_key` (old format, migrated on first run)

HybridEncryptionProvider manages storage internally - SecurityGuard just calls encrypt()/decrypt().

### Encryption Flow (savePassword/savePin)

```
SecurityGuard.savePassword("mypass123"):
1. Validates key integrity
2. Calls encryptionProvider.encrypt("wallet_password_key", "mypass123")
3. HybridEncryptionProvider.encrypt():
   a. Encrypts with ModernEncryptionProvider (KeyStore)
   b. Stores at "primary_wallet_password_key" in SharedPreferences
   c. Encrypts with PasswordBasedEncryptionProvider (seed)
   d. Stores at "fallback_wallet_password_key" in SharedPreferences
   e. Returns encrypted bytes (for interface compatibility)
4. Done - both versions saved automatically
```

### Decryption Flow (retrievePassword/retrievePin)

```
SecurityGuard.retrievePassword():
1. Calls encryptionProvider.decrypt("wallet_password_key", dummy_bytes)
2. HybridEncryptionProvider.decrypt():
   a. Check if KeyStore is healthy (flag in SharedPreferences)
   b. IF healthy:
      - Load from "primary_wallet_password_key"
      - Try decrypt with ModernEncryptionProvider
      - If SUCCESS → return plaintext
      - If FAILS (BadPaddingException/AEADBadTagException):
         * Mark KeyStore as unhealthy
         * Fall through to fallback
   c. Load from "fallback_wallet_password_key"
   d. Decrypt with PasswordBasedEncryptionProvider → plaintext
   e. SELF-HEAL: Try to re-encrypt with ModernEncryptionProvider
   f. IF healing succeeds → mark KeyStore as healthy again
   g. Return plaintext
3. Done - user gets password, KeyStore auto-healed if possible
```

**Note**: The `encryptedData` parameter in `decrypt()` is ignored - HybridEncryptionProvider loads data from SharedPreferences internally using the key alias.

### Self-Healing Example

```
Day 1: User saves PIN
- KeyStore encryption: ✓ (stored as primary_ui_pin_key)
- Seed encryption: ✓ (stored as fallback_ui_pin_key)

Day 2: Android OS update corrupts KeyStore
- KeyStore encryption: ✗ (corrupted, can't decrypt)
- Seed encryption: ✓ (still works)

User unlocks wallet:
1. App tries KeyStore decryption → FAILS (AEADBadTagException)
2. App detects corruption, switches to fallback mode
3. Decrypts with seed-based encryption → SUCCESS
4. Immediately re-encrypts with KeyStore (healing attempt)
5. IF KeyStore now works:
   - Saves new primary_ui_pin_key
   - Marks KeyStore as healthy
6. IF KeyStore still broken:
   - Stays in fallback mode
   - Will retry healing on next unlock

Result: User never knows anything happened
```

## Benefits

### For Users
- **No lockouts**: Wallet is always recoverable via seed phrase
- **Transparent**: Works seamlessly in background
- **Fast recovery**: Automatic healing, no manual intervention
- **Seed phrase recovery**: Can restore wallet + settings from seed

### For Developers
- **Proper GCM**: Unique IV per encryption (security best practice)
- **No IV corruption**: IV embedded in ciphertext
- **Backward compatible**: Migrates old data automatically
- **Testable**: Clear separation of concerns

### Security Improvements
- **Eliminated shared IV vulnerability**: Each encryption has unique IV
- **Defense in depth**: Two independent encryption systems
- **Hardware security**: Still uses KeyStore when available
- **Recoverable**: Seed-based fallback always works

## Migration Strategy

### Existing Users
When app upgrades:
1. `HybridEncryptionMigration` runs automatically
2. Detects old-format encrypted data
3. Decrypts using legacy method (shared IV)
4. Re-encrypts with hybrid system
5. Old data removed, new data saved
6. Migration flagged as complete

### New Users
- Start directly with hybrid system
- No migration needed
- Dual encryption from day one

## Recovery Scenarios

### Scenario 1: KeyStore Corrupted
```
Problem: Android KeyStore key corrupted (common issue)
Solution:
1. Hybrid system detects corruption
2. Falls back to seed-based decryption
3. Re-encrypts with KeyStore (self-healing)
4. User unaffected
```

### Scenario 2: Forgot PIN
```
Problem: User forgot PIN
Solution:
1. User enters 12/24 word seed phrase
2. Wallet restored from seed
3. Encryption keys derived from seed
4. Encrypted settings decrypted
5. User sets new PIN
```

### Scenario 3: New Device
```
Problem: User restored wallet on new device
Solution:
1. User enters seed phrase during setup
2. Wallet + encrypted data restored
3. Encryption keys derived from seed
4. All settings recovered
5. No re-configuration needed
```

### Scenario 4: Both Systems Fail
```
Problem: KeyStore corrupted AND seed unavailable (extremely rare)
Fallback:
1. App cannot decrypt sensitive data
2. User must re-enter credentials
3. Wallet still accessible via seed phrase backup
4. Only encrypted settings lost
```

## File Structure

```
common/src/main/java/org/dash/wallet/common/util/security/
├── MasterKeyProvider.kt          (interface)
├── EncryptionProvider.kt          (existing interface)

wallet/src/de/schildbach/wallet/security/
├── SeedBasedMasterKeyProvider.kt  (seed → keys)
├── PasswordBasedEncryptionProvider.kt  (fallback encryption)
├── ModernEncryptionProvider.kt    (fixed, proper IV handling)
├── HybridEncryptionProvider.kt    (orchestrator + self-healing)
├── HybridEncryptionMigration.kt   (migration logic)
├── EncryptionProviderFactory.java (updated to create hybrid)
├── SecurityGuard.java             (updated to run migration)
```

## Testing

The implementation includes:
- Unit tests for each provider
- Integration tests for hybrid failover
- Migration tests for backward compatibility
- Self-healing verification tests

To run tests:
```bash
./gradlew testDebugUnitTest
./gradlew connectedAndroidTest
```

## Configuration

No configuration needed - system works automatically:
- **Primary**: KeyStore encryption (default)
- **Fallback**: Seed-based encryption (always ready)
- **Self-healing**: Enabled by default

## Monitoring

Check logs for encryption health:
```
INFO: Primary encryption succeeded for ui_pin_key
INFO: Fallback encryption succeeded for ui_pin_key
INFO: Dual encryption successful for ui_pin_key

# If KeyStore issues occur:
WARN: Primary encryption failed for ui_pin_key
INFO: Fallback encryption succeeded for ui_pin_key
INFO: Fallback mode active, using password-based decryption
INFO: Primary encryption healed for ui_pin_key
```

## Future Enhancements

Potential improvements:
1. Add biometric wrapping of cached PIN (convenience layer)
2. Add cloud backup of fallback-encrypted data
3. Add recovery code generation (alternative to seed)
4. Add hardware security module support (where available)

## Summary

This hybrid approach provides:
- ✅ Best security when KeyStore works (hardware-backed)
- ✅ Guaranteed recovery when KeyStore fails (seed-based)
- ✅ Automatic self-healing (transparent to users)
- ✅ Proper cryptography (unique IVs, GCM compliance)
- ✅ Backward compatibility (migrates existing users)

**Result**: Users will never experience wallet lockouts due to Android KeyStore corruption issues.