# 6. Security, secrets and logging

**6.1 Never log bearer or private material.** This includes:
- invitation links, `af_dp`, `osk` (a shielded invite's spending key);
- refund addresses, entered amounts, card numbers/PINs, payment URLs;
- a whole `toString()` of a response or data class that carries any of the above.

Log the host, the length or a boolean (#1619, #1504, #1429). Redact in `toString()`.

**6.2 Never keep a parser exception, or any exception that carries its input, as the cause on a logged path.**
Android's `JSONException` message can include the malformed input. In #1616 that input was a stored pending payment
with signed tx bytes. Throw a sanitized exception with no cause, and log only the exception type for per-entry decode
failures.

**6.3 Log the decision, not the data.** For example: `owed stop ({reason}) suppresses command {action}` (#1623). Log
through SLF4J with the cause; never `printStackTrace`, and never swallow the cause in a retry loop (#1431, #1429).

**6.4 Support reports respect the user's "Append application log" opt-out** for any wallet-attributed transaction data.
Public txids do not make their link to this wallet public (#1601). The pattern: return `NOT_SHARED` without computing
anything.

**6.5 Wallet wipe/reset.**
- SDK secret erasure is a checked postcondition.
- Fence every secret writer before erasing (`WalletSecretWriteFence`).
- Do not raw-delete the SDK DataStore: the device-scoped defect record must survive (#1612).

**6.6 Wallet encryption is all-or-nothing.**
- Roll back `wallet.encrypt` if saving the password fails.
- Do not map `SecurityGuard.checkPin` exceptions to "wrong PIN".
- Snapshot the PIN immutably for delayed submissions, and cancel them on backspace and dismiss (#1551).

**6.7 Authentication before every fresh spend** (see 4.35).

**6.8 Treat QR and deep-link payloads as attacker-controlled.**
- Parse strictly: no trailing bytes accepted.
- Cap sizes before native deserialize.
- Do not present an unverified label from the payload as the app's identity.
- Bind follow-up actions to the scanned request, not "the newest APPROVED" (#1569 DashConnect, before mainnet).
- Clamp coordinates parsed from URLs before `Bitmap.createBitmap` (#1486).

**6.9 Production builds use immutable dependency versions.**
- A `-SNAPSHOT` coordinate resolves through mutable metadata, and there is no verification metadata or lockfile, so a
  compromised publisher could change the signed APK.
- Pin the exact timestamped artifact you tested, e.g. `'0.1.0-v42int28-20261002.062748-1'`, and add a comment naming the
  release to move to (#1597, #1584, #1564).
- Where no immutable artifact exists yet, the bot accepts it as an explicitly deferred production-release gate.
