# QA Test Protocol - Dual-Fallback Security System
## Simplified Manual Testing Guide

**Version:** 1.0
**Target Release:** v11.6 (feat/upgrade-security-system)
**Date:** 2025-01-24

---

## Overview

This testing protocol covers the new dual-fallback security system through **manual UI testing only**. No technical tools or command-line access required.

### What You Need

- **Test Devices:**
  - At least one Android device (Android 8.0 or higher)
  - Recommended: 2-3 devices with different Android versions

- **APK Files:**
  - **v6.x** - Old version (if testing legacy upgrade)
  - **v11.5** - Current production version
  - **v11.6+** - New version with dual-fallback security

- **Test Wallet:**
  - Create a test wallet with small amount
  - Write down: Recovery phrase (12 words) and PIN
  - Keep this information safe for all tests

---

## Test Preparation

### Before Starting Each Test

1. **Record Device Info:**
   - Device model: ________________
   - Android version: ________________
   - Current app version: ________________

2. **Create Test Wallet Record:**
   ```
   Recovery Phrase: ____ ____ ____ ____ ____ ____ ____ ____ ____ ____ ____ ____
   PIN: ________
   Test Date: ________
   ```

---

## Test Scenarios

## Scenario 1: Fresh Install - New Wallet

**Time Estimate:** 10 minutes
**Objective:** Verify new wallet creation works correctly

### Steps

1. **Uninstall** any existing Dash Wallet app
2. **Install** v11.6+ APK
3. **Launch** the app
4. Tap **"Create New Wallet"**
5. **Write down** the 12-word recovery phrase shown
6. Tap **"Continue"** or **"Next"**
7. **Confirm** recovery phrase by selecting words in correct order
8. **Set PIN** (example: 1234)
9. **Confirm PIN** by entering again
10. Wait for wallet to finish initial sync

### Expected Results ✅

- [ ] Recovery phrase displayed (12 words)
- [ ] Recovery phrase confirmation required
- [ ] PIN set successfully
- [ ] No error messages shown
- [ ] App opens to main wallet screen
- [ ] Balance shows 0.00 DASH initially

### If Test Fails ❌

**Note:** Error type, screenshot, and exact step where failure occurred

---

## Scenario 2: Fresh Install - Restore Wallet

**Time Estimate:** 10 minutes
**Objective:** Verify wallet restore from recovery phrase works

### Steps

1. **Uninstall** any existing Dash Wallet app
2. **Install** v11.6+ APK
3. **Launch** the app
4. Tap **"Restore Wallet"** or **"Recover Wallet"**
5. **Enter** your 12-word recovery phrase
6. Tap **"Restore"** or **"Continue"**
7. **Set PIN** (example: 5678)
8. **Confirm PIN**
9. Wait for wallet to sync

### Expected Results ✅

- [ ] Restore option available
- [ ] All 12 words accepted
- [ ] PIN set successfully
- [ ] Wallet restored with correct balance
- [ ] Transaction history appears (if any)
- [ ] No error messages

### If Test Fails ❌

**Note:** Which step failed and what error message appeared

---

## Scenario 3: Upgrade from v11.5

**Time Estimate:** 15 minutes
**Objective:** Verify smooth upgrade from current production version

### Part A: Setup v11.5

1. **Uninstall** any existing app
2. **Install** v11.5 APK
3. **Create** new wallet OR **restore** existing test wallet
4. **Set PIN:** 1234
5. **Send** small test transaction (optional but recommended)
6. **Lock** the app (press home button or lock device)
7. **Unlock** with PIN - verify it works
8. **Write down:** Current balance: ________ DASH

### Part B: Upgrade to v11.6

9. **Install** v11.6+ APK over existing v11.5
   - Note: Choose "Update" or "Install anyway" if prompted
10. **Launch** the app
11. **Wait** for any migration/upgrade process (may take a few seconds)
12. **Enter PIN:** 1234 (same as before)
13. **Verify** wallet opens successfully

### Part C: Verify After Upgrade

14. **Check balance:** ________ DASH (should match Part A)
15. **Check transaction history:** Should show previous transactions
16. **Lock** and **unlock** with PIN again
17. **Go to:** Settings → Security & Recovery
18. **View Recovery Phrase** (enter PIN when prompted)
19. **Verify:** Recovery phrase matches original

### Expected Results ✅

- [ ] Upgrade completes without errors
- [ ] Same PIN works after upgrade
- [ ] Balance preserved exactly
- [ ] Transaction history intact
- [ ] Recovery phrase accessible and correct
- [ ] Can lock/unlock normally
- [ ] No data loss
- [ ] No crashes

### If Test Fails ❌

**Critical:** Note if balance changed or PIN stopped working

---

## Scenario 4: Upgrade from v6 → v11.5 → v11.6

**Time Estimate:** 30 minutes
**Objective:** Test complete legacy upgrade path

### Phase 1: Setup v6

1. **Uninstall** all apps
2. **Install** v6.x APK
3. **Create** new wallet
4. **Write down:**
   - Recovery phrase: ____________________
   - PIN: 9999
   - Balance: ________ DASH
5. **Lock** and **unlock** - verify PIN works

### Phase 2: Upgrade to v11.5

6. **Install** v11.5 APK over v6
7. **Launch** app
8. **Enter PIN:** 9999
9. **Verify balance:** ________ DASH (should match)
10. **Lock** and **unlock** again

### Phase 3: Upgrade to v11.6

11. **Install** v11.6+ APK over v11.5
12. **Launch** app
13. **Enter PIN:** 9999
14. **Verify balance:** ________ DASH (should match)
15. **Check transaction history**
16. **View recovery phrase** - verify it matches

### Expected Results ✅

- [ ] Each upgrade completes successfully
- [ ] Same PIN (9999) works throughout all versions
- [ ] Balance stays consistent: v6 → v11.5 → v11.6
- [ ] Transaction history preserved
- [ ] Recovery phrase unchanged
- [ ] No errors or crashes

### If Test Fails ❌

**Critical:** Note which upgrade step failed (v6→v11.5 or v11.5→v11.6)

---

## Scenario 5: PIN Change

**Time Estimate:** 5 minutes
**Objective:** Verify PIN can be changed successfully

### Steps

1. **Open** Dash Wallet (any version v11.6+)
2. **Go to:** Settings → Security → Change PIN
3. **Enter current PIN:** 1234
4. **Enter new PIN:** 5678
5. **Confirm new PIN:** 5678
6. Tap **"Save"** or **"Confirm"**
7. **Lock** the app
8. **Try old PIN:** 1234 (should fail)
9. **Enter new PIN:** 5678 (should work)

### Expected Results ✅

- [ ] Change PIN option available
- [ ] Old PIN required to change
- [ ] New PIN saved successfully
- [ ] Confirmation message shown
- [ ] Old PIN no longer works
- [ ] New PIN works for unlocking

### If Test Fails ❌

**Note:** What happened when trying new PIN

---

## Scenario 6: View Recovery Phrase

**Time Estimate:** 3 minutes
**Objective:** Verify recovery phrase can be viewed

### Steps

1. **Open** Dash Wallet
2. **Go to:** Settings → Security & Recovery → View Recovery Phrase
3. **Enter PIN** when prompted
4. **Write down** the 12 words shown
5. **Compare** with original recovery phrase

### Expected Results ✅

- [ ] PIN required to view
- [ ] 12 words displayed
- [ ] Words match original recovery phrase
- [ ] Option to copy or share (with warning)

### If Test Fails ❌

**Critical:** Note if recovery phrase is different from original

---

## Scenario 7: Wrong PIN Entry

**Time Estimate:** 3 minutes
**Objective:** Verify error handling for wrong PIN

### Steps

1. **Lock** the app
2. **Enter wrong PIN:** 0000 (if real PIN is 1234)
3. **Note:** Error message
4. **Enter wrong PIN again:** 1111
5. **Note:** Error message
6. **Enter correct PIN:** 1234

### Expected Results ✅

- [ ] Error message after each wrong PIN
- [ ] Error message is clear (e.g., "Incorrect PIN")
- [ ] App doesn't crash
- [ ] Correct PIN still works after wrong attempts
- [ ] No permanent lockout (or lockout is reasonable)

### If Test Fails ❌

**Note:** Does app crash or behave unexpectedly?

---

## Scenario 8: Send Transaction

**Time Estimate:** 5 minutes
**Objective:** Verify sending works after upgrade/setup

### Steps

1. **Ensure** wallet has small balance
2. **Tap** "Send" or "Pay"
3. **Enter** recipient address (or scan QR code)
4. **Enter** amount (small test amount)
5. **Review** transaction details
6. **Enter PIN** when prompted
7. **Confirm** send
8. **Wait** for transaction to appear in history

### Expected Results ✅

- [ ] Can enter recipient address
- [ ] Can enter amount
- [ ] PIN required to send
- [ ] Transaction sent successfully
- [ ] Transaction appears in history
- [ ] Balance updates accordingly

### If Test Fails ❌

**Note:** At which step did it fail?

---

## Scenario 9: Receive Transaction

**Time Estimate:** 5 minutes
**Objective:** Verify receiving works

### Steps

1. **Tap** "Receive" or "Request"
2. **View** your receive address
3. **Copy** address OR **show QR code**
4. **Send small amount** from another wallet to this address
5. **Wait** for transaction to appear (may take a few minutes)
6. **Check** transaction history
7. **Verify** balance increased

### Expected Results ✅

- [ ] Receive address displayed
- [ ] QR code shown
- [ ] Can copy address
- [ ] Incoming transaction appears
- [ ] Balance updates correctly

### If Test Fails ❌

**Note:** Did transaction appear? Was balance correct?

---

## Scenario 10: App Lock/Unlock Cycle

**Time Estimate:** 5 minutes
**Objective:** Verify lock/unlock works reliably

### Steps

1. **Open** wallet with PIN
2. **Wait** on main screen (30 seconds)
3. **Press home button** (send app to background)
4. **Wait** 1 minute
5. **Return** to app - should be locked
6. **Enter PIN** to unlock
7. **Go to** Settings → Security → Auto-lock timeout
8. **Check** current timeout setting
9. **Change** timeout (if possible)
10. **Test** new timeout by backgrounding app

### Expected Results ✅

- [ ] App locks when backgrounded
- [ ] PIN required to unlock
- [ ] Auto-lock timeout works as set
- [ ] Can change timeout setting
- [ ] No crashes during lock/unlock

### If Test Fails ❌

**Note:** Does auto-lock work? Does PIN unlock work?

---

## Scenario 11: Reinstall Without Uninstall

**Time Estimate:** 5 minutes
**Objective:** Verify data persists when reinstalling same version

### Steps

1. **Note:** Current PIN and balance
2. **Install** same v11.6+ APK again (without uninstalling)
3. **Choose:** "Update" or "Install"
4. **Launch** app
5. **Enter PIN** (should be same)
6. **Check balance** (should be same)

### Expected Results ✅

- [ ] Reinstall completes successfully
- [ ] No data loss
- [ ] Same PIN works
- [ ] Balance unchanged
- [ ] Transaction history intact

---

## Scenario 12: Uninstall and Restore

**Time Estimate:** 10 minutes
**Objective:** Verify recovery phrase actually restores wallet

### Steps

1. **Write down:**
   - Recovery phrase: ____________________
   - Current balance: ________ DASH
2. **Uninstall** Dash Wallet completely
3. **Reinstall** v11.6+ APK
4. **Choose:** "Restore Wallet"
5. **Enter** recovery phrase (all 12 words)
6. **Set new PIN:** (can be different)
7. **Wait** for sync
8. **Verify balance:** ________ DASH (should match step 1)

### Expected Results ✅

- [ ] Recovery phrase accepted
- [ ] Wallet restored successfully
- [ ] Balance matches before uninstall
- [ ] Transaction history restored
- [ ] Can set new PIN (doesn't have to be old PIN)

### If Test Fails ❌

**Critical:** Did recovery phrase restore the correct wallet?

---

## Regression Testing Checklist

After completing main scenarios, verify these still work:

### Basic Functionality
- [ ] App launches without crash
- [ ] Main balance screen displays correctly
- [ ] Send transaction works
- [ ] Receive transaction works
- [ ] Transaction history displays
- [ ] QR code scanning works
- [ ] Copy/paste addresses works

### Settings
- [ ] Can access all settings
- [ ] Can change PIN
- [ ] Can view recovery phrase
- [ ] Can change currency (USD, EUR, etc.)
- [ ] Can toggle biometric unlock (if available)
- [ ] Settings persist after restart

### UI/UX
- [ ] No UI glitches or overlapping text
- [ ] All buttons responsive
- [ ] No frozen screens
- [ ] Back button works correctly
- [ ] Animations smooth
- [ ] No visual artifacts

### Performance
- [ ] App launches in < 3 seconds
- [ ] PIN unlock in < 1 second
- [ ] No lag when typing PIN
- [ ] Smooth scrolling in transaction list
- [ ] No battery drain issues

---

## Test Results Form

### Test Execution Summary

**Tester Name:** ________________
**Date:** ________________
**Device:** ________________
**Android Version:** ________________

| Scenario | Status | Notes |
|----------|--------|-------|
| 1. Fresh Install - New | ⬜ Pass ⬜ Fail | |
| 2. Fresh Install - Restore | ⬜ Pass ⬜ Fail | |
| 3. Upgrade v11.5 → v11.6 | ⬜ Pass ⬜ Fail | |
| 4. Upgrade v6 → v11.5 → v11.6 | ⬜ Pass ⬜ Fail | |
| 5. PIN Change | ⬜ Pass ⬜ Fail | |
| 6. View Recovery Phrase | ⬜ Pass ⬜ Fail | |
| 7. Wrong PIN Entry | ⬜ Pass ⬜ Fail | |
| 8. Send Transaction | ⬜ Pass ⬜ Fail | |
| 9. Receive Transaction | ⬜ Pass ⬜ Fail | |
| 10. App Lock/Unlock | ⬜ Pass ⬜ Fail | |
| 11. Reinstall Same Version | ⬜ Pass ⬜ Fail | |
| 12. Uninstall/Restore | ⬜ Pass ⬜ Fail | |
| Regression Tests | ⬜ Pass ⬜ Fail | |

### Critical Issues Found

**Issue 1:**
- Scenario: ________________
- Description: ________________
- Severity: ⬜ Critical ⬜ High ⬜ Medium ⬜ Low
- Screenshot/Video: ________________

**Issue 2:**
- Scenario: ________________
- Description: ________________
- Severity: ⬜ Critical ⬜ High ⬜ Medium ⬜ Low
- Screenshot/Video: ________________

**Issue 3:**
- Scenario: ________________
- Description: ________________
- Severity: ⬜ Critical ⬜ High ⬜ Medium ⬜ Low
- Screenshot/Video: ________________

### Overall Test Result

- [ ] **PASS** - All critical scenarios passed, no blocking issues
- [ ] **PASS WITH MINOR ISSUES** - All critical scenarios passed, minor issues noted
- [ ] **FAIL** - One or more critical scenarios failed

### Sign-Off

**Tester Signature:** ________________
**Date:** ________________

**Reviewed By:** ________________
**Date:** ________________

---

## Bug Reporting Template

When reporting bugs, include:

### Required Information

1. **Device Information:**
   - Device Model: ________________
   - Android Version: ________________
   - App Version: ________________

2. **Steps to Reproduce:**
   ```
   1.
   2.
   3.
   ```

3. **Expected Behavior:**
   ```

   ```

4. **Actual Behavior:**
   ```

   ```

5. **Frequency:**
   - ⬜ Every time
   - ⬜ Sometimes (__ out of __ attempts)
   - ⬜ Once

6. **Screenshots/Videos:**
   - Attach any visual evidence

7. **Additional Notes:**
   ```

   ```

---

## FAQ - Common Questions

**Q: How long should I wait after entering recovery phrase?**
A: Wait at least 5 minutes for the wallet to sync. Check that the sync indicator shows progress.

**Q: What if I forget my PIN during testing?**
A: Use "Restore Wallet" and enter the recovery phrase to regain access. You can set a new PIN.

**Q: Can I use the same recovery phrase for multiple tests?**
A: Yes, but create separate test wallets for different test scenarios to avoid confusion.

**Q: What if the app crashes?**
A: Note the exact step, take a screenshot if possible, and try to reproduce. If it crashes again at the same step, that's a critical bug.

**Q: How much DASH do I need for testing?**
A: Very small amounts - 0.01 DASH is enough for transaction testing.

**Q: What if upgrade takes a long time?**
A: Wait at least 2 minutes. If no progress after 5 minutes, that may be a bug.

---

## Test Environment Tips

### Before Each Test Session

1. **Charge device** to > 50%
2. **Connect to stable WiFi**
3. **Clear notification tray**
4. **Close other apps**
5. **Have test recovery phrase ready**

### During Testing

- **Take screenshots** of any unexpected behavior
- **Record videos** for complex issues
- **Note exact time** when errors occur
- **Don't rush** - follow each step carefully
- **Test thoroughly** - repeat failed tests

### After Testing

- **Uninstall test app** if using test device
- **Save all screenshots/videos**
- **Complete test results form**
- **Report all issues found**

---

## Success Criteria

### Must Pass (Critical)
- ✅ Fresh install creates wallet successfully
- ✅ Recovery phrase restores wallet with correct balance
- ✅ Upgrade from v11.5 preserves all data
- ✅ PIN works after upgrade
- ✅ Send and receive transactions work
- ✅ No crashes during normal operation
- ✅ No data loss during any scenario

### Should Pass (High Priority)
- ✅ Upgrade from v6 works
- ✅ PIN change works
- ✅ View recovery phrase works
- ✅ Wrong PIN handling is correct
- ✅ App lock/unlock is reliable

### Nice to Have (Medium Priority)
- ✅ No UI glitches
- ✅ Fast performance
- ✅ Smooth animations

---

**End of QA Test Protocol**