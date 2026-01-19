# Cryptographic Verification Fallback - Ultimate Recovery Layer

## Overview

RestoreWalletFromSeedViewModel now has **three layers** of recovery when users provide their recovery phrase:

1. **Primary**: KeyStore-based encryption (decrypt and compare seeds)
2. **Fallback #1**: Mnemonic-based recovery (recover PIN and password from encrypted fallbacks)
3. **Fallback #2**: Cryptographic verification (compare derived keys) ⭐ NEW

## Why This Is Needed

Even if:
- ✗ KeyStore is completely corrupted
- ✗ Mnemonic-based fallback encrypted data is missing or corrupted
- ✗ All encryption systems have failed

We can **still verify** the user's recovery phrase is correct by comparing cryptographically derived keys.

## How Cryptographic Verification Works

### The Algorithm

```kotlin
fun verifyMnemonicMatchesWallet(words: List<String>): Boolean {
    // 1. Create seed from provided mnemonic
    val providedSeed = DeterministicSeed(words, null, "", 0L)

    // 2. Derive master key from provided mnemonic
    val providedMasterKey = HDKeyDerivation.createMasterPrivateKey(providedSeed.seedBytes)

    // 3. Derive key at BIP44 path: m/44'/5'/0'/0/0
    //    (first receiving address in Dash wallet)
    val derivationPath = [44', 5', 0', 0, 0]
    val providedKey = deriveAtPath(providedMasterKey, derivationPath)

    // 4. Get the same key from the existing wallet
    val walletKey = wallet.getKeyByPath(derivationPath)

    // 5. Compare public keys
    return providedKey.pubKey == walletKey.pubKey
}
```

### Derivation Path Explained

`m/44'/5'/0'/0/0` means:
- `44'` - BIP44 standard (hardened)
- `5'` - Dash coin type (hardened)
- `0'` - Account 0 (hardened)
- `0` - External chain (receiving addresses)
- `0` - First address

This path always exists in a Dash wallet and provides a reliable comparison point.

## Complete Recovery Flow

```
User provides recovery phrase
            ↓
┌───────────────────────────┐
│ Layer 1: Primary          │
│ (KeyStore decryption)     │
└───────────────────────────┘
            ↓
        ┌───┴───┐
        ▼       ▼
      OK       FAIL
        │       │
        │       ▼
        │  ┌───────────────────────────┐
        │  │ Layer 2: Mnemonic Fallback│
        │  │ (Recover from encrypted)  │
        │  └───────────────────────────┘
        │       │
        │   ┌───┴───┐
        │   ▼       ▼
        │  OK      FAIL
        │   │       │
        │   │       ▼
        │   │  ┌──────────────────────────────┐
        │   │  │ Layer 3: Cryptographic       │
        │   │  │ (Compare derived keys)       │
        │   │  └──────────────────────────────┘
        │   │       │
        │   │   ┌───┴───┐
        │   │   ▼       ▼
        │   │  MATCH   NO MATCH
        │   │   │       │
        │   │   ▼       ▼
        └───┴──►│      FAIL
                │
                ▼
        ┌───────────────┐
        │ Mnemonic OK!  │
        │               │
        │ • PIN?        │
        │   - Recovered │
        │   - OR Empty  │
        │               │
        └───────────────┘
```

## Return Values from `recover()`

| Layer | Success | Return Value | Meaning |
|-------|---------|--------------|---------|
| Primary | ✅ | `"1234"` | PIN recovered from KeyStore |
| Mnemonic Fallback | ✅ | `"1234"` | PIN recovered from encrypted fallback |
| Cryptographic Verification | ✅ | `""` (empty string) | Mnemonic verified but PIN **not** recovered |
| All Failed | ❌ | `null` | Mnemonic does not match wallet |

### Handling Empty String Return

When `recover()` returns `""`:
- ✅ The mnemonic is **cryptographically verified** to match the wallet
- ❌ The PIN could **not** be recovered
- 🔧 User must **set a new PIN**

**Example handling**:
```kotlin
val recoveredPin = recoverPin(mnemonicWords)
when {
    recoveredPin == null -> {
        // Mnemonic doesn't match wallet - show error
        showError("Invalid recovery phrase")
    }
    recoveredPin.isEmpty() -> {
        // Mnemonic verified but PIN not recovered
        // User needs to set new PIN
        showMessage("Recovery phrase verified! Please set a new PIN")
        navigateToSetPin()
    }
    else -> {
        // PIN recovered successfully
        usePin(recoveredPin)
    }
}
```

## Example Scenario: Complete Encryption Failure

### The Situation
1. User's KeyStore is corrupted (BadPaddingException)
2. Mnemonic fallback encrypted data got corrupted or deleted
3. All encryption systems completely broken
4. User provides their 12-word recovery phrase

### The Recovery Process

```
1. User enters: "abandon abandon abandon ... art"
   └─> 12-word recovery phrase

2. Try Primary (KeyStore)
   └─> AEADBadTagException (KeyStore corrupted!)

3. Try Mnemonic Fallback
   └─> GeneralSecurityException (Encrypted fallback data corrupted!)

4. Try Cryptographic Verification
   ├─> Derive key from provided mnemonic at m/44'/5'/0'/0/0
   │   Result: pubkey = 02a1b2c3d4e5...
   │
   ├─> Get key from wallet at m/44'/5'/0'/0/0
   │   Result: pubkey = 02a1b2c3d4e5...
   │
   └─> Compare: MATCH! ✓

5. Result: return "" (empty string)
   └─> Signal: "Mnemonic verified but PIN not recovered"

6. UI Action: Prompt user to set new PIN
```

### Log Output

```
[INFO] Attempting cryptographic verification of mnemonic against wallet
[INFO] Mnemonic verification: public keys match = true
[INFO] Mnemonic cryptographically verified against wallet!
```

## Security Considerations

### Why This Is Safe

1. **No Decryption Required**: Works without any encryption keys
2. **One-Way Derivation**: Cannot reverse-engineer mnemonic from public keys
3. **Cryptographic Proof**: BIP32 derivation is cryptographically secure
4. **Unique to Wallet**: Derived keys are unique to this specific wallet

### What Gets Verified

✅ The mnemonic is mathematically proven to be the correct recovery phrase for this wallet
✅ The derived keys match exactly
✅ User can access their funds with this mnemonic

### What Does NOT Get Verified

❌ Cannot recover the old PIN (encryption keys lost)
❌ Cannot recover the old wallet password (encryption keys lost)

**Solution**: User sets a new PIN → System creates new encryption → Everything works again

## Implementation Details

### File: RestoreWalletFromSeedViewModel.kt

**Method**: `verifyMnemonicMatchesWallet(words: List<String>): Boolean`
- Lines 63-109
- Derives key from mnemonic at BIP44 path
- Gets same key from wallet
- Compares public keys
- Returns true if match

**Method**: `recover(words: List<String>): String?`
- Lines 111-166
- Primary attempt (lines 113-120)
- Mnemonic fallback attempt (lines 126-145)
- Cryptographic verification (lines 152-158) ⭐ NEW
- Returns "", null, or recovered PIN

### Derivation Path Used

```
m/44'/5'/0'/0/0
│  │   │  │  │
│  │   │  │  └─ Address index 0
│  │   │  └──── External chain (receiving)
│  │   └─────── Account 0
│  └─────────── Dash coin type (5)
└────────────── BIP44 purpose
```

## Testing

### Test Case 1: All Systems Working
```
1. Provide correct mnemonic
2. Primary succeeds → Returns actual PIN
3. Cryptographic verification not needed ✓
```

### Test Case 2: KeyStore Corrupted, Fallback Works
```
1. Provide correct mnemonic
2. Primary fails (KeyStore corrupted)
3. Mnemonic fallback succeeds → Returns recovered PIN
4. Cryptographic verification not needed ✓
```

### Test Case 3: All Encryption Broken
```
1. Provide correct mnemonic
2. Primary fails (KeyStore corrupted)
3. Mnemonic fallback fails (encrypted data corrupted)
4. Cryptographic verification succeeds → Returns ""
5. UI prompts for new PIN ✓
```

### Test Case 4: Wrong Mnemonic
```
1. Provide incorrect mnemonic
2. Primary fails (KeyStore corrupted)
3. Mnemonic fallback fails (wrong mnemonic)
4. Cryptographic verification fails → Returns null
5. UI shows error ✓
```

## Production Benefits

### For Users
- ✅ Can always verify their recovery phrase is correct
- ✅ Works even when all encryption is broken
- ✅ Just need to set new PIN if old one can't be recovered
- ✅ No data loss - funds are still accessible

### For Support
- ✅ Can definitively tell if user has correct recovery phrase
- ✅ Clear error messages (verified vs not verified)
- ✅ Simple resolution path (set new PIN)

### For Security
- ✅ No weak points introduced
- ✅ Uses standard BIP32/BIP44 derivation
- ✅ Cannot be exploited for unauthorized access
- ✅ Maintains all existing security properties

## Summary

The cryptographic verification fallback is the **ultimate safety net**. Even in catastrophic encryption failure scenarios, users can:

1. Verify their recovery phrase is correct
2. Set a new PIN
3. Regain full access to their wallet

This completes the recovery hierarchy:
- **Best**: KeyStore works → Instant PIN recovery
- **Good**: Fallback works → PIN recovered from encrypted backup
- **Acceptable**: Crypto verify works → Mnemonic verified, new PIN needed
- **Fail**: Nothing works → Wrong mnemonic or corrupted wallet

With this implementation, **zero valid mnemonics will be rejected** due to encryption failures!