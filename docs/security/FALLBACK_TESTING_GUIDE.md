# Fallback Recovery Testing Guide

## Overview

This guide shows how to test the dual-fallback encryption system by simulating KeyStore failures.

## Testing Utility: FallbackTestingUtils

Location: `wallet/src/de/schildbach/wallet/security/FallbackTestingUtils.kt`

**⚠️ WARNING: FOR TESTING/DEBUGGING ONLY! DO NOT USE IN PRODUCTION BUILDS!**

## Quick Start

### Option 1: Force KeyStore to Fail (Recommended for Testing)

This is the cleanest way to test - it forces the system to skip KeyStore without corrupting any data.

```kotlin
// In your debug code or test activity
import de.schildbach.wallet.security.FallbackTestingUtils

// Enable test mode
FallbackTestingUtils.enableTestMode()

// Force KeyStore to fail
FallbackTestingUtils.forceKeystoreFailure()

// Now try to login - it will automatically use PIN fallback!

// When done testing, restore normal operation
FallbackTestingUtils.restoreKeystoreOperation()
FallbackTestingUtils.disableTestMode()
```

### Option 2: Simulate Actual KeyStore Corruption

These methods actually delete/corrupt data to simulate real-world failures.

```kotlin
import de.schildbach.wallet.security.FallbackTestingUtils

// Delete KeyStore keys but keep fallback data intact
FallbackTestingUtils.simulateKeystoreCorruption_KeepFallbacks()

// Now try to login - fallback recovery will kick in!
```

## Testing Scenarios

### Scenario 1: Test PIN-Based Fallback

**Goal**: Verify PIN-based recovery works when KeyStore fails

**Steps**:
```kotlin
// 1. Enable test mode and force failure
FallbackTestingUtils.enableTestMode()
FallbackTestingUtils.forceKeystoreFailure()

// 2. Try to login with your PIN
// Expected: PIN check fails with KeyStore, automatically recovers via PIN fallback
// Log message: "PIN-based fallback recovery succeeded"

// 3. Verify self-healing occurred
// Next login should work normally (KeyStore restored)

// 4. Clean up
FallbackTestingUtils.restoreKeystoreOperation()
FallbackTestingUtils.disableTestMode()
```

**What to Look For**:
```
[WARN] ⚠️ TESTING MODE: Forcing KeyStore failure for wallet_password_key
[WARN] Primary PIN check failed: Forced KeyStore failure for testing
[INFO] Attempting PIN-based fallback recovery for wallet password
[INFO] PIN-based fallback recovery succeeded
[INFO] Primary encryption healed for wallet_password_key
```

### Scenario 2: Test Mnemonic-Based Fallback

**Goal**: Verify mnemonic recovery works when both KeyStore and PIN fallback fail

**Steps**:
```kotlin
// 1. Corrupt all encryption except mnemonic fallback
FallbackTestingUtils.simulateKeystoreCorruption_OnlyMnemonicFallback()

// 2. Go to "Restore Wallet from Seed" screen
// Enter your 12-word recovery phrase

// Expected: System recovers PIN and password using mnemonic
// Log message: "Mnemonic-based fallback recovery succeeded"

// 3. Verify self-healing occurred
```

**What to Look For**:
```
[WARN] Primary encryption failed during recovery
[INFO] Attempting mnemonic-based fallback recovery
[INFO] Mnemonic-based fallback recovery succeeded
[INFO] Recovered PIN matches provided mnemonic, system healed
[INFO] Primary encryption healed for ui_pin_key
[INFO] Primary encryption healed for wallet_password_key
```

### Scenario 3: Test Cryptographic Verification (Nuclear Option)

**Goal**: Verify cryptographic verification works when ALL encryption is broken

**Steps**:
```kotlin
// 1. Corrupt EVERYTHING
FallbackTestingUtils.simulateCompleteEncryptionFailure()

// 2. Go to "Restore Wallet from Seed" screen
// Enter your 12-word recovery phrase

// Expected: System verifies mnemonic cryptographically, requires new PIN
// Log message: "Mnemonic cryptographically verified against wallet!"

// 3. Set new PIN when prompted
```

**What to Look For**:
```
[ERROR] Mnemonic-based fallback recovery also failed
[INFO] Attempting cryptographic verification of mnemonic against wallet
[INFO] Mnemonic verification: public keys match = true
[INFO] Mnemonic cryptographically verified against wallet!
```

### Scenario 4: Test PIN Change with KeyStore Corrupted

**Goal**: Verify PIN change works even when KeyStore fails

**Steps**:
```kotlin
// 1. Force KeyStore failure
FallbackTestingUtils.enableTestMode()
FallbackTestingUtils.forceKeystoreFailure()

// 2. Go to "Change PIN" screen
// Enter old PIN and new PIN

// Expected: Old PIN verified via fallback, new PIN saved
// Log message: "PIN-based fallback recovery succeeded during password change"

// 3. Clean up
FallbackTestingUtils.restoreKeystoreOperation()
FallbackTestingUtils.disableTestMode()
```

### Scenario 5: Test View Recovery Phrase with KeyStore Corrupted

**Goal**: Verify viewing seed phrase works with fallback

**Steps**:
```kotlin
// 1. Force KeyStore failure
FallbackTestingUtils.enableTestMode()
FallbackTestingUtils.forceKeystoreFailure()

// 2. Go to "View Recovery Phrase" screen
// Enter your PIN

// Expected: PIN verified via fallback, seed displayed
// Log message: "PIN-based fallback recovery succeeded during seed decryption"

// 3. Clean up
FallbackTestingUtils.restoreKeystoreOperation()
FallbackTestingUtils.disableTestMode()
```

## Testing Methods Reference

### Enable/Disable Test Mode

```kotlin
// Enable test mode (required for forced failures)
FallbackTestingUtils.enableTestMode()

// Disable test mode
FallbackTestingUtils.disableTestMode()

// Check if enabled
val isEnabled = FallbackTestingUtils.isTestModeEnabled()
```

### Force KeyStore Failure (Clean Method)

```kotlin
// Force KeyStore to fail (no data corruption)
FallbackTestingUtils.forceKeystoreFailure()

// Restore normal operation
FallbackTestingUtils.restoreKeystoreOperation()

// Check if forced to fail
val isForced = FallbackTestingUtils.isKeystoreForcedToFail()
```

### Simulate Real Corruption

```kotlin
// Method 1: Delete KeyStore keys, keep fallbacks
FallbackTestingUtils.simulateKeystoreCorruption_KeepFallbacks()

// Method 2: Corrupt primary encrypted data
FallbackTestingUtils.simulateKeystoreCorruption_CorruptData()

// Method 3: Only PIN fallback available
FallbackTestingUtils.simulateKeystoreCorruption_OnlyPinFallback()

// Method 4: Only mnemonic fallback available
FallbackTestingUtils.simulateKeystoreCorruption_OnlyMnemonicFallback()

// Method 5: Complete encryption failure (for cryptographic verification)
FallbackTestingUtils.simulateCompleteEncryptionFailure()

// Restore everything
FallbackTestingUtils.restoreAllEncryption()
```

### Check Encryption Status

```kotlin
// Print detailed encryption status to logs
FallbackTestingUtils.printEncryptionStatus()
```

**Example Output**:
```
=== ENCRYPTION STATUS ===
Test Mode: true
KeyStore Forced Fail: true

Primary PIN: ✓
Primary Password: ✓

PIN Fallback (Password): ✓

Mnemonic Fallback (PIN): ✓
Mnemonic Fallback (Password): ✓
========================
```

## Integration with UI

### Debug Menu Example

Add a debug menu to your app for easy testing:

```kotlin
// In your DebugActivity or Settings screen
class DebugEncryptionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.btnEnableTestMode.setOnClickListener {
            FallbackTestingUtils.enableTestMode()
            Toast.makeText(this, "Test mode enabled", Toast.LENGTH_SHORT).show()
        }

        binding.btnForceKeystoreFail.setOnClickListener {
            FallbackTestingUtils.forceKeystoreFailure()
            Toast.makeText(this, "KeyStore forced to fail", Toast.LENGTH_SHORT).show()
        }

        binding.btnRestoreNormal.setOnClickListener {
            FallbackTestingUtils.restoreKeystoreOperation()
            FallbackTestingUtils.disableTestMode()
            Toast.makeText(this, "Restored to normal", Toast.LENGTH_SHORT).show()
        }

        binding.btnCheckStatus.setOnClickListener {
            FallbackTestingUtils.printEncryptionStatus()
            Toast.makeText(this, "Check logs for status", Toast.LENGTH_SHORT).show()
        }

        binding.btnSimulateCorruption.setOnClickListener {
            FallbackTestingUtils.simulateKeystoreCorruption_KeepFallbacks()
            Toast.makeText(this, "KeyStore corrupted", Toast.LENGTH_SHORT).show()
        }

        binding.btnCompleteFailure.setOnClickListener {
            FallbackTestingUtils.simulateCompleteEncryptionFailure()
            Toast.makeText(this, "All encryption corrupted!", Toast.LENGTH_SHORT).show()
        }
    }
}
```

## Manual Testing Without Code

### Method 1: Device Settings (Real World Scenario)

1. **Setup**: Login to app, ensure PIN fallback is created
2. **Corrupt KeyStore**:
   - Go to Android Settings → Security → Lock Screen
   - Change your device lock screen pattern/PIN/password
   - This invalidates biometric KeyStore keys
3. **Test**: Try to login - should automatically use PIN fallback
4. **Result**: PIN-based recovery works ✓

### Method 2: Clear App Data

1. **Setup**: Login to app, note your PIN
2. **Corrupt**:
   - Go to Android Settings → Apps → Dash Wallet → Storage
   - Click "Clear Data" (NOT "Clear Storage")
3. **Test**: Reinstall app, restore from seed
4. **Result**: Mnemonic-based recovery or cryptographic verification ✓

## Automated Testing

### Unit Test Example

```kotlin
@Test
fun testPinBasedFallbackRecovery() {
    // Enable test mode
    FallbackTestingUtils.enableTestMode()
    FallbackTestingUtils.forceKeystoreFailure()

    // Try to check PIN
    val result = checkPinViewModel.checkPin("1234")

    // Verify fallback recovery occurred
    assertEquals(Status.SUCCESS, result.status)
    verify(logger).info(contains("PIN-based fallback recovery succeeded"))

    // Clean up
    FallbackTestingUtils.disableTestMode()
}
```

## Common Issues

### Issue: "Cannot force KeyStore failure - test mode not enabled!"

**Solution**: Call `enableTestMode()` before `forceKeystoreFailure()`

```kotlin
FallbackTestingUtils.enableTestMode()  // Call this first!
FallbackTestingUtils.forceKeystoreFailure()
```

### Issue: Fallback recovery not working

**Possible causes**:
1. Fallback data not created yet (user hasn't entered PIN since upgrade)
2. Fallback data also corrupted
3. Wrong PIN or mnemonic provided

**Solution**: Check encryption status:
```kotlin
FallbackTestingUtils.printEncryptionStatus()
```

### Issue: App crashes instead of falling back

**Check**:
1. Is `DualFallbackEncryptionProvider` being used? (Check logs)
2. Are recovery methods properly integrated in all PIN check locations?
3. Check crash logs for exceptions

## Best Practices

### 1. Always Clean Up

```kotlin
try {
    FallbackTestingUtils.enableTestMode()
    FallbackTestingUtils.forceKeystoreFailure()

    // ... your test code ...

} finally {
    // Always restore normal operation
    FallbackTestingUtils.restoreKeystoreOperation()
    FallbackTestingUtils.disableTestMode()
}
```

### 2. Test All Scenarios

- ✅ Login with PIN (KeyStore fails)
- ✅ Change PIN (KeyStore fails)
- ✅ View recovery phrase (KeyStore fails)
- ✅ Restore wallet (complete encryption failure)
- ✅ Self-healing verification

### 3. Check Logs

Enable verbose logging and watch for:
```
PIN-based fallback recovery succeeded
Mnemonic-based fallback recovery succeeded
Primary encryption healed
Mnemonic cryptographically verified
```

### 4. Don't Ship Test Code

**Remove or disable** `FallbackTestingUtils` in production builds:

```kotlin
// In build.gradle
buildTypes {
    release {
        // Exclude testing utilities
        minifyEnabled true
        proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
    }
}
```

```proguard
# In proguard-rules.pro
-assumenosideeffects class de.schildbach.wallet.security.FallbackTestingUtils {
    *;
}
```

## Summary

The `FallbackTestingUtils` provides comprehensive tools for testing every fallback recovery scenario:

1. **Forced Failure** (clean, no corruption)
2. **KeyStore Corruption** (delete keys)
3. **Data Corruption** (corrupt encrypted bytes)
4. **Partial Failure** (test specific fallback paths)
5. **Complete Failure** (test cryptographic verification)

Use these tools to verify the dual-fallback system works perfectly before deploying to users!