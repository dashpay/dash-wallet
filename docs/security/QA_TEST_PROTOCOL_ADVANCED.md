# QA Test Protocol - Dual-Fallback Security System

**Version:** 1.0
**Target Release:** v11.6 (feat/upgrade-security-system)
**Date:** 2025-01-24

## Overview

This document provides comprehensive testing procedures for the dual-fallback encryption security system. The system must be tested across multiple upgrade paths to ensure backward compatibility and data preservation.

## Test Environment Requirements

### Required APK Versions
- **v6.x** - Legacy encryption system (pre-v7)
- **v11.5** - Current production release
- **v11.6+** - New dual-fallback system (feat/upgrade-security-system branch)

### Required Test Devices
- **Minimum:** Android API 26 (Android 8.0)
- **Recommended:**
  - Android 8.0 (API 26) - Minimum supported
  - Android 10 (API 29) - Common device
  - Android 13+ (API 33+) - Latest with hardware security
- **Emulators:** Pixel 8 API 34 (for testing)

### Test Data Preparation
- Test wallet with known:
  - 12-word recovery phrase
  - PIN code (e.g., "1234")
  - Small balance for transaction testing
  - At least 2 receiving addresses used

---

## Test Scenarios

## Scenario 1: Clean Install (Fresh Installation)

**Objective:** Verify the dual-fallback system works correctly on fresh installations.

### Test Case 1.1: New Wallet Creation

**Steps:**
1. Uninstall any existing Dash Wallet app
2. Install v11.6+ APK
3. Launch app
4. Select "Create New Wallet"
5. Write down the 12-word recovery phrase shown
6. Confirm recovery phrase
7. Set PIN (e.g., "1234")
8. Wait for wallet to sync

**Expected Results:**
- ✅ Wallet created successfully
- ✅ PIN saved and works for unlocking
- ✅ Security preferences should contain:
  - `primary_ui_pin_key`
  - `primary_wallet_password_key`
  - `fallback_pin_wallet_password_key`
  - `fallback_mnemonic_ui_pin_key`
  - `fallback_mnemonic_wallet_password_key`

**Verification Commands:**
```bash
# Check security preferences
adb shell "run-as hashengineering.darkcoin.wallet cat /data/data/hashengineering.darkcoin.wallet/shared_prefs/security.xml"

# Check for all expected keys
adb shell "run-as hashengineering.darkcoin.wallet cat /data/data/hashengineering.darkcoin.wallet/shared_prefs/security.xml" | grep -E "primary_|fallback_"
```

**Check Logs For:**
```
INFO: Mnemonic-based fallbacks ensured for PIN and wallet password
INFO: Health monitoring system initialized
```

### Test Case 1.2: Wallet Restore from Recovery Phrase

**Steps:**
1. Uninstall any existing Dash Wallet app
2. Install v11.6+ APK
3. Launch app
4. Select "Restore Wallet"
5. Enter known 12-word recovery phrase
6. Set PIN (e.g., "5678")
7. Wait for wallet to sync

**Expected Results:**
- ✅ Wallet restored with correct balance and addresses
- ✅ PIN works for unlocking
- ✅ All fallback encryptions created
- ✅ Health status: `HEALTHY_WITH_FALLBACKS`

**Verification:**
```bash
# Check security status
adb logcat -d | grep -i "security\|fallback\|encryption"
```

---

## Scenario 2: Upgrade from v11.5 (Current Production)

**Objective:** Verify seamless upgrade from current production version.

### Test Case 2.1: Basic Upgrade with Existing PIN

**Pre-conditions:**
- v11.5 installed with wallet
- PIN set and working

**Steps:**
1. Install v11.5 from Google Play or APK
2. Create new wallet or restore existing
3. Set PIN (e.g., "1234")
4. Send/receive a small transaction
5. Verify PIN unlock works
6. **Upgrade:** Install v11.6+ APK over v11.5
7. Launch app
8. Enter PIN to unlock
9. Check "Settings" > "Security & Recovery"
10. View recovery phrase (requires PIN)
11. Send a test transaction

**Expected Results:**
- ✅ Migration runs automatically on first launch
- ✅ Existing PIN continues to work
- ✅ Wallet balance and history preserved
- ✅ All fallback encryptions created
- ✅ No error dialogs or crashes

**Check Logs For:**
```
INFO: Starting dual-fallback migration
INFO: Creating dual-fallback encryption provider
INFO: PIN-based fallback ensured for wallet password
INFO: Mnemonic-based fallbacks ensured for PIN and wallet password
INFO: Dual-fallback migration completed successfully
```

**Verify Security Preferences:**
```bash
# Should contain both old and new format
adb shell "run-as hashengineering.darkcoin.wallet cat /data/data/hashengineering.darkcoin.wallet/shared_prefs/security.xml" | grep -E "ui_pin_key|wallet_password_key"
```

### Test Case 2.2: Upgrade Without Migration (Edge Case)

**Pre-conditions:**
- v11.5 installed with wallet
- Wallet has funds

**Steps:**
1. Install v11.5
2. Create wallet and set PIN
3. **Before upgrading:** Clear migration flag manually
   ```bash
   adb shell "run-as hashengineering.darkcoin.wallet rm -f /data/data/hashengineering.darkcoin.wallet/files/dual_fallback_migration_completed"
   ```
4. Install v11.6+ APK
5. Launch app
6. Enter PIN

**Expected Results:**
- ✅ Migration runs even if flag was cleared
- ✅ All fallbacks created successfully
- ✅ PIN works normally

---

## Scenario 3: Upgrade from v6 → v11.5 → v11.6

**Objective:** Verify complete upgrade path from legacy version.

### Test Case 3.1: Multi-Step Upgrade

**Steps:**

#### Phase 1: Install v6
1. Uninstall any existing app
2. Install v6.x APK
3. Create new wallet
4. Set PIN (e.g., "9999")
5. Write down recovery phrase
6. Send small amount to wallet
7. Verify PIN unlock works in v6

**Verify v6 Security:**
```bash
# v6 uses different encryption format
adb shell "run-as hashengineering.darkcoin.wallet cat /data/data/hashengineering.darkcoin.wallet/shared_prefs/security.xml"
```

#### Phase 2: Upgrade to v11.5
1. Install v11.5 APK over v6
2. Launch app
3. Enter PIN (should be same: "9999")
4. Check balance (should match v6)
5. Send a test transaction
6. Lock and unlock with PIN

**Expected Results:**
- ✅ v6 → v11.5 migration successful
- ✅ PIN works after migration
- ✅ Wallet data preserved

**Check Logs:**
```
INFO: Migrating from legacy encryption
INFO: Legacy data migrated successfully
```

#### Phase 3: Upgrade to v11.6
1. Install v11.6+ APK over v11.5
2. Launch app
3. Enter PIN (still "9999")
4. Wait for migration to complete
5. Verify balance matches
6. Check security status

**Expected Results:**
- ✅ v11.5 → v11.6 migration successful
- ✅ All dual-fallback encryptions created
- ✅ PIN continues working
- ✅ Wallet balance preserved
- ✅ Transaction history intact

**Verify Full Security Stack:**
```bash
# Should have all fallback keys
adb shell "run-as hashengineering.darkcoin.wallet cat /data/data/hashengineering.darkcoin.wallet/shared_prefs/security.xml" | grep -E "primary_|fallback_" | wc -l
# Should return 5 (primary PIN, primary password, PIN fallback, mnemonic PIN fallback, mnemonic password fallback)
```

---

## Scenario 4: Fallback Recovery Testing

**Objective:** Verify all fallback recovery paths work correctly.

### Test Case 4.1: PIN-Based Fallback Recovery

**Pre-conditions:**
- v11.6+ installed with wallet
- PIN set and all fallbacks created

**Steps:**
1. Verify wallet is working normally
2. Enable fallback testing mode:
   ```kotlin
   // In debug menu or debug build
   FallbackTestingUtils.enableTestMode()
   FallbackTestingUtils.forceKeystoreFailure()
   ```
3. Lock the app
4. Enter PIN to unlock

**Expected Results:**
- ✅ KeyStore fails (as forced)
- ✅ PIN-based fallback recovery succeeds automatically
- ✅ Wallet unlocks successfully
- ✅ User sees no error (transparent recovery)

**Check Logs:**
```
WARN: ⚠️ TESTING MODE: Forcing KeyStore failure
WARN: Primary PIN check failed: Forced KeyStore failure for testing
INFO: Attempting PIN-based fallback recovery for wallet password
INFO: PIN-based fallback recovery succeeded
INFO: Primary encryption healed for wallet_password_key
```

**Cleanup:**
```kotlin
FallbackTestingUtils.restoreKeystoreOperation()
FallbackTestingUtils.disableTestMode()
```

### Test Case 4.2: Mnemonic-Based Fallback Recovery

**Pre-conditions:**
- v11.6+ with wallet
- Know the 12-word recovery phrase

**Steps:**
1. Enable test mode and corrupt all encryption except mnemonic:
   ```kotlin
   FallbackTestingUtils.enableTestMode()
   FallbackTestingUtils.simulateKeystoreCorruption_OnlyMnemonicFallback()
   ```
2. Try to unlock with PIN (will fail)
3. Select "Restore Wallet from Seed"
4. Enter 12-word recovery phrase
5. Should recover PIN automatically

**Expected Results:**
- ✅ KeyStore and PIN fallback both fail
- ✅ Mnemonic-based recovery succeeds
- ✅ Original PIN is recovered
- ✅ Wallet accessible with recovered PIN

**Check Logs:**
```
WARN: Primary encryption failed during recovery
INFO: Attempting mnemonic-based fallback recovery
INFO: Mnemonic-based fallback recovery succeeded
INFO: Recovered PIN matches provided mnemonic, system healed
```

### Test Case 4.3: Cryptographic Verification (Nuclear Option)

**Pre-conditions:**
- v11.6+ with wallet
- Know the 12-word recovery phrase

**Steps:**
1. Simulate complete encryption failure:
   ```kotlin
   FallbackTestingUtils.simulateCompleteEncryptionFailure()
   ```
2. Try to unlock - will fail
3. Go to "Restore Wallet from Seed"
4. Enter 12-word recovery phrase
5. System should verify cryptographically
6. Prompt to set new PIN

**Expected Results:**
- ✅ All encryption fails
- ✅ Mnemonic is cryptographically verified
- ✅ User prompted to set new PIN
- ✅ After setting new PIN, wallet accessible
- ✅ All fallbacks recreated

**Check Logs:**
```
ERROR: Mnemonic-based fallback recovery also failed
INFO: Attempting cryptographic verification of mnemonic against wallet
INFO: Mnemonic verification: public keys match = true
INFO: Mnemonic cryptographically verified against wallet!
```

### Test Case 4.4: Real-World KeyStore Failure

**Pre-conditions:**
- Physical Android device (not emulator)
- v11.6+ with wallet and PIN

**Steps:**
1. Set up wallet with PIN
2. Go to Android Settings → Security → Screen Lock
3. Change device lock screen pattern/PIN/password
4. Return to Dash Wallet
5. Try to unlock with PIN

**Expected Results:**
- ✅ KeyStore keys invalidated by device settings change
- ✅ PIN-based fallback recovery works automatically
- ✅ No user-visible error
- ✅ KeyStore self-heals after recovery

**Note:** This is the most realistic test of fallback recovery.

---

## Scenario 5: Security Health Monitoring

**Objective:** Verify health monitoring system works correctly.

### Test Case 5.1: Health Status Transitions

**Steps:**
1. Install v11.6+ and create wallet
2. Check initial health status (should be `HEALTHY_WITH_FALLBACKS`)
3. Force KeyStore failure:
   ```kotlin
   FallbackTestingUtils.enableTestMode()
   FallbackTestingUtils.forceKeystoreFailure()
   ```
4. Check health status (should change to `FALLBACKS`)
5. Unlock with PIN (triggers recovery)
6. Check health status (should return to `HEALTHY_WITH_FALLBACKS`)

**Expected Results:**
- ✅ Health status transitions correctly
- ✅ Blockchain sync stops when status = `FALLBACKS`
- ✅ Blockchain sync resumes after healing

**Verify via Logs:**
```
INFO: Notifying X health listeners of status change: FALLBACKS
INFO: Primary encryption healed for wallet_password_key
INFO: Notifying X health listeners of status change: HEALTHY_WITH_FALLBACKS
```

### Test Case 5.2: Blockchain Service Impediment

**Steps:**
1. Start with healthy system
2. Force KeyStore failure
3. Observe blockchain sync status
4. Unlock with PIN (triggers healing)
5. Observe blockchain sync resumes

**Expected Results:**
- ✅ Blockchain sync has impediment `SECURITY` when KeyStore fails
- ✅ Impediment removed after healing
- ✅ Sync resumes automatically

---

## Scenario 6: PIN Change Operations

**Objective:** Verify PIN changes work with fallback system.

### Test Case 6.1: Normal PIN Change

**Steps:**
1. Install v11.6+ with wallet
2. Current PIN: "1234"
3. Go to Settings → Change PIN
4. Enter old PIN: "1234"
5. Enter new PIN: "5678"
6. Confirm new PIN: "5678"
7. Lock app
8. Unlock with new PIN: "5678"

**Expected Results:**
- ✅ PIN changed successfully
- ✅ New PIN works
- ✅ Old PIN no longer works
- ✅ All fallbacks updated with new PIN

### Test Case 6.2: PIN Change with KeyStore Failed

**Steps:**
1. Set initial PIN: "1234"
2. Force KeyStore failure:
   ```kotlin
   FallbackTestingUtils.enableTestMode()
   FallbackTestingUtils.forceKeystoreFailure()
   ```
3. Go to Settings → Change PIN
4. Enter old PIN: "1234" (verified via PIN fallback)
5. Enter new PIN: "9999"
6. Restore KeyStore:
   ```kotlin
   FallbackTestingUtils.restoreKeystoreOperation()
   ```
7. Lock and unlock with new PIN: "9999"

**Expected Results:**
- ✅ Old PIN verified via fallback
- ✅ New PIN saved successfully
- ✅ System self-heals
- ✅ New PIN works after healing

---

## Scenario 7: View Recovery Phrase

**Objective:** Verify recovery phrase viewing works with fallback.

### Test Case 7.1: Normal Recovery Phrase Viewing

**Steps:**
1. Install v11.6+ with wallet
2. PIN: "1234"
3. Go to Settings → Security & Recovery → View Recovery Phrase
4. Enter PIN: "1234"
5. View 12-word phrase
6. Write down and verify it matches original

**Expected Results:**
- ✅ PIN required to view
- ✅ Correct recovery phrase displayed
- ✅ All 12 words shown

### Test Case 7.2: View Recovery Phrase with KeyStore Failed

**Steps:**
1. Force KeyStore failure
2. Go to Settings → View Recovery Phrase
3. Enter PIN

**Expected Results:**
- ✅ PIN verified via fallback
- ✅ Recovery phrase displayed
- ✅ No error visible to user

---

## Scenario 8: Edge Cases and Error Handling

### Test Case 8.1: Wrong PIN Entry

**Steps:**
1. Lock app
2. Enter wrong PIN 3 times
3. Enter correct PIN

**Expected Results:**
- ✅ Error message after each wrong attempt
- ✅ No lockout (or appropriate lockout behavior)
- ✅ Correct PIN works

### Test Case 8.2: Empty/Corrupted Security Preferences

**Steps:**
1. Install v11.6+, create wallet
2. Force-stop app
3. Delete security preferences:
   ```bash
   adb shell "run-as hashengineering.darkcoin.wallet rm /data/data/hashengineering.darkcoin.wallet/shared_prefs/security.xml"
   ```
4. Launch app

**Expected Results:**
- ✅ App detects missing security data
- ✅ User prompted to restore from recovery phrase
- ✅ Recovery phrase restores access

### Test Case 8.3: Wallet File Deleted but Security Intact

**Steps:**
1. Create wallet with known recovery phrase
2. Force-stop app
3. Delete wallet file:
   ```bash
   adb shell "run-as hashengineering.darkcoin.wallet rm /data/data/hashengineering.darkcoin.wallet/files/*.wallet"
   ```
4. Launch app

**Expected Results:**
- ✅ App detects missing wallet
- ✅ User prompted to restore
- ✅ Security data can be used after restore

---

## Test Data Collection

### Required Information to Collect

For each test scenario, collect:

#### 1. Security Preferences Snapshot
```bash
adb shell "run-as hashengineering.darkcoin.wallet cat /data/data/hashengineering.darkcoin.wallet/shared_prefs/security.xml"
```

Expected keys:
- `primary_ui_pin_key`
- `primary_wallet_password_key`
- `fallback_pin_wallet_password_key`
- `fallback_mnemonic_ui_pin_key`
- `fallback_mnemonic_wallet_password_key`
- `keystore_healthy` (boolean)

#### 2. Logcat Output
```bash
adb logcat -d -s "SecurityGuard:*" "DualFallbackEncryptionProvider:*" "DualFallbackMigration:*" "ModernEncryptionProvider:*" "FallbackTestingUtils:*"
```

#### 3. Health Status
Check for log message:
```
INFO: Health monitoring system initialized
INFO: Notifying X health listeners of status change: <status>
```

#### 4. Migration Status
Check for:
```
INFO: Starting dual-fallback migration
INFO: Dual-fallback migration completed successfully
```

---

## Success Criteria

### Critical Requirements (Must Pass All)

- ✅ **Clean install:** All fallbacks created on first setup
- ✅ **v11.5 upgrade:** Seamless migration, no data loss
- ✅ **v6 → v11.5 → v11.6:** Complete upgrade path works
- ✅ **PIN fallback:** Automatic recovery when KeyStore fails
- ✅ **Mnemonic fallback:** Recovery from 12-word phrase works
- ✅ **PIN change:** Works with and without KeyStore
- ✅ **View recovery phrase:** Accessible via fallback
- ✅ **No crashes:** Zero crashes during any test scenario
- ✅ **No data loss:** Wallet balance and history preserved

### Performance Requirements

- Migration completes in < 5 seconds
- No noticeable delay on app startup
- PIN unlock within 500ms (including fallback path)

### Security Requirements

- No plaintext secrets in logs (production builds)
- No secrets in SharedPreferences (all encrypted)
- KeyStore self-heals after fallback recovery
- Health monitoring reflects actual system state

---

## Regression Testing Checklist

After all test scenarios pass, verify:

- [ ] Send transaction works
- [ ] Receive transaction works
- [ ] Transaction history correct
- [ ] Balance matches blockchain
- [ ] QR code scanning works
- [ ] Biometric unlock works (if supported)
- [ ] App backgrounding/foregrounding
- [ ] App lock timeout works
- [ ] Settings changes persist
- [ ] Backup/restore wallet works

---

## Known Issues & Workarounds

### Issue: Migration flag file location

**Symptom:** Migration runs multiple times
**Workaround:** Check for file existence before migration
**Status:** Fixed in v11.6

### Issue: KeyStore keys on emulator

**Symptom:** KeyStore behaves differently on emulator vs device
**Workaround:** Test on real device for KeyStore failure scenarios
**Status:** Expected behavior (emulator limitation)

---

## Reporting Failures

### Information to Include in Bug Reports

1. **Device information:**
   - Device model
   - Android version
   - API level

2. **Test scenario:**
   - Scenario number and name
   - Steps to reproduce

3. **Expected vs. Actual:**
   - What should have happened
   - What actually happened

4. **Logs:**
   - Full logcat (filtered for relevant tags)
   - Security preferences dump

5. **Screenshots/Videos:**
   - Error dialogs
   - Unexpected behavior

### Log Capture Command

```bash
# Full relevant logs
adb logcat -d \
  -s "SecurityGuard:*" \
     "DualFallbackEncryptionProvider:*" \
     "DualFallbackMigration:*" \
     "ModernEncryptionProvider:*" \
     "PinBasedKeyProvider:*" \
     "MnemonicBasedKeyProvider:*" \
     "CheckPinViewModel:*" \
     "RestoreWalletFromSeedViewModel:*" \
     "BlockchainServiceImpl:*" \
  > security_test_log.txt
```

---

## Test Sign-Off

### Test Execution Record

| Scenario | Test Cases | Pass/Fail | Tester | Date | Notes |
|----------|-----------|-----------|--------|------|-------|
| 1. Clean Install | 1.1, 1.2 | | | | |
| 2. Upgrade v11.5 | 2.1, 2.2 | | | | |
| 3. Upgrade v6→v11.5→v11.6 | 3.1 | | | | |
| 4. Fallback Recovery | 4.1, 4.2, 4.3, 4.4 | | | | |
| 5. Health Monitoring | 5.1, 5.2 | | | | |
| 6. PIN Change | 6.1, 6.2 | | | | |
| 7. View Recovery | 7.1, 7.2 | | | | |
| 8. Edge Cases | 8.1, 8.2, 8.3 | | | | |

### Final Approval

- [ ] All critical test scenarios passed
- [ ] No critical or high-priority bugs
- [ ] Performance requirements met
- [ ] Security requirements verified
- [ ] Regression testing completed
- [ ] Documentation reviewed

**QA Lead:** ________________
**Date:** ________________
**Approved for Release:** [ ] Yes [ ] No

---

## Appendix: Quick Reference Commands

### Enable Debug Testing Mode

```kotlin
// In debug menu or via adb shell am start
FallbackTestingUtils.enableTestMode()
FallbackTestingUtils.printEncryptionStatus()
```

### Force KeyStore Failure

```kotlin
FallbackTestingUtils.forceKeystoreFailure()
// Test your scenario
FallbackTestingUtils.restoreKeystoreOperation()
```

### Simulate KeyStore Corruption

```kotlin
// Method 1: Delete keys but keep fallbacks
FallbackTestingUtils.simulateKeystoreCorruption_KeepFallbacks()

// Method 2: Corrupt encrypted data
FallbackTestingUtils.simulateKeystoreCorruption_CorruptData()

// Method 3: Only PIN fallback available
FallbackTestingUtils.simulateKeystoreCorruption_OnlyPinFallback()

// Method 4: Only mnemonic fallback available
FallbackTestingUtils.simulateKeystoreCorruption_OnlyMnemonicFallback()

// Method 5: Complete failure (cryptographic verification only)
FallbackTestingUtils.simulateCompleteEncryptionFailure()
```

### Check Encryption Status

```kotlin
FallbackTestingUtils.printEncryptionStatus()
```

Output example:
```
=== ENCRYPTION STATUS ===
Test Mode: true
KeyStore Forced Fail: false

Primary PIN: ✓
Primary Password: ✓

PIN Fallback (Password): ✓

Mnemonic Fallback (PIN): ✓
Mnemonic Fallback (Password): ✓
========================
```

### Clean Up After Testing

```kotlin
FallbackTestingUtils.restoreAllEncryption()
FallbackTestingUtils.disableTestMode()
```

---

**End of QA Test Protocol**