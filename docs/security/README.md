# Security System Documentation

This directory contains documentation for the Dash Wallet's dual-fallback encryption security system.

## Architecture Overview

The security system provides three layers of encryption protection:

1. **Primary:** Android KeyStore (hardware-backed when available)
2. **Fallback #1:** PIN-based encryption (for wallet password recovery)
3. **Fallback #2:** Mnemonic-based encryption (for complete recovery)

## Documentation Files

### Integration & Setup
- **[INTEGRATION_COMPLETE.md](./INTEGRATION_COMPLETE.md)** - Complete integration guide and status
- **[DUAL_FALLBACK_INTEGRATION.md](./DUAL_FALLBACK_INTEGRATION.md)** - Detailed integration instructions
- **[LAZY_FALLBACK_ENCRYPTION.md](./LAZY_FALLBACK_ENCRYPTION.md)** - Lazy fallback initialization guide

### Implementation Details
- **[COMPLETE_FALLBACK_COVERAGE.md](./COMPLETE_FALLBACK_COVERAGE.md)** - Coverage analysis of fallback recovery
- **[CRYPTOGRAPHIC_VERIFICATION_FALLBACK.md](./CRYPTOGRAPHIC_VERIFICATION_FALLBACK.md)** - Ultimate recovery layer documentation
- **[ALL_PIN_CHECKS_UPDATED.md](./ALL_PIN_CHECKS_UPDATED.md)** - PIN check integration status

### Testing
- **[QA_TEST_PROTOCOL.md](./QA_TEST_PROTOCOL.md)** - **Simple manual UI testing** (no technical tools required)
- **[QA_TEST_PROTOCOL_ADVANCED.md](./QA_TEST_PROTOCOL_ADVANCED.md)** - Advanced testing with logcat, adb, and FallbackTestingUtils
- **[FALLBACK_TESTING_GUIDE.md](./FALLBACK_TESTING_GUIDE.md)** - Developer testing guide with FallbackTestingUtils

## Quick Links

### For Developers
- Start here: [INTEGRATION_COMPLETE.md](./INTEGRATION_COMPLETE.md)
- Testing: [FALLBACK_TESTING_GUIDE.md](./FALLBACK_TESTING_GUIDE.md)

### For QA Testers
- **Start here:** [QA_TEST_PROTOCOL.md](./QA_TEST_PROTOCOL.md) - Simple manual testing (recommended)
- **Advanced:** [QA_TEST_PROTOCOL_ADVANCED.md](./QA_TEST_PROTOCOL_ADVANCED.md) - Technical testing with tools
- All upgrade paths covered: v6 → v11.5 → v11.6
- UI-based testing only, no command-line required

### For Security Auditors
- Architecture: [DUAL_FALLBACK_INTEGRATION.md](./DUAL_FALLBACK_INTEGRATION.md)
- Recovery: [CRYPTOGRAPHIC_VERIFICATION_FALLBACK.md](./CRYPTOGRAPHIC_VERIFICATION_FALLBACK.md)
- Coverage: [COMPLETE_FALLBACK_COVERAGE.md](./COMPLETE_FALLBACK_COVERAGE.md)

## Key Components

### Core Classes
- `DualFallbackEncryptionProvider` - Orchestrates all three encryption layers
- `ModernEncryptionProvider` - Android KeyStore implementation
- `PinBasedKeyProvider` - PIN-derived key generation
- `MnemonicBasedKeyProvider` - Mnemonic-derived key generation
- `SecurityGuard` - Central security management

### Testing Utilities
- `FallbackTestingUtils` - Simulate KeyStore failures and test recovery paths

## Recovery Hierarchy

```
User needs to access wallet
        ↓
┌───────────────────┐
│ Primary (KeyStore)│
└───────────────────┘
        ↓
    ┌───┴───┐
    ▼       ▼
   OK      FAIL
    │       │
    │       ▼
    │  ┌──────────────────┐
    │  │ PIN Fallback     │
    │  │ (Remember PIN)   │
    │  └──────────────────┘
    │       │
    │   ┌───┴───┐
    │   ▼       ▼
    │  OK      FAIL
    │   │       │
    │   │       ▼
    │   │  ┌─────────────────────┐
    │   │  │ Mnemonic Fallback   │
    │   │  │ (12-word phrase)    │
    │   │  └─────────────────────┘
    │   │       │
    │   │   ┌───┴───┐
    │   │   ▼       ▼
    │   │  OK      FAIL
    │   │   │       │
    │   │   │       ▼
    │   │   │  ┌──────────────────────┐
    │   │   │  │ Cryptographic Verify │
    │   │   │  │ (Last resort)        │
    │   │   │  └──────────────────────┘
    │   │   │       │
    │   │   │   ┌───┴───┐
    │   │   │   ▼       ▼
    └───┴───┴──►│     FAIL
                │
                ▼
        ✅ Access Granted
```

## Security Properties

- **No single point of failure:** Three independent recovery paths
- **Hardware security:** Uses Android KeyStore when available
- **Self-healing:** Automatically repairs KeyStore after recovery
- **Zero data loss:** Even catastrophic KeyStore failure is recoverable
- **Cryptographic verification:** Can verify recovery phrase without decryption
- **Forward compatible:** Graceful degradation on older devices

## Support

For questions or issues with the security system:
1. Check the relevant documentation file above
2. Review test cases in `FallbackTestingUtils.kt`
3. Consult integration status in `INTEGRATION_COMPLETE.md`