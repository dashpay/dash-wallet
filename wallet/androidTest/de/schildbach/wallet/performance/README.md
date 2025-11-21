# Wallet Performance Tests

## Overview
This package contains instrumentation tests to measure wallet save/load performance on Android devices.

## Test Class: WalletSavePerformanceTest

### Purpose
Measures the performance of wallet serialization and persistence operations to identify potential bottlenecks in wallet saving operations.

### Tests Included

1. **testWalletSavePerformance_EmptyWallet()** - Tests saving a newly created empty wallet
2. **testWalletSavePerformance_WithTransactions()** - Tests saving a wallet with 50 transactions
3. **testWalletSavePerformance_LargeWallet()** - Tests saving a wallet with 200 transactions and 100 addresses
4. **testWalletSavePerformance_MultipleConsecutiveSaves()** - Tests performance consistency across multiple saves
5. **testWalletLoadFromResources()** - Tests loading a pre-existing wallet from assets
6. **testInMemoryWalletSerialization()** - Tests in-memory serialization performance

### Running the Tests

#### Command Line
```bash
# Run all performance tests
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.schildbach.wallet.performance.WalletSavePerformanceTest

# Run specific test
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.schildbach.wallet.performance.WalletSavePerformanceTest -Pandroid.testInstrumentationRunnerArguments.method=testWalletSavePerformance_EmptyWallet
```

#### Android Studio
1. Right-click on the test class or method
2. Select "Run 'testMethodName()'"
3. Choose your target device/emulator

### Expected Performance Baselines

- **Empty wallet save**: < 1000ms
- **Wallet with transactions save**: < 2000ms  
- **Large wallet save**: < 3000ms
- **In-memory serialization**: < 1000ms
- **Wallet loading**: < 1000ms

### Test Data

The tests use:
- **Network**: TestNet3
- **Mnemonic**: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
- **Script Type**: P2PKH
- **BIP44 compatible paths**

### Interpreting Results

Monitor these metrics in the test logs:
- Individual save times (ms)
- Average save times across multiple iterations
- Wallet file sizes
- Transaction counts
- Memory usage patterns

### Troubleshooting

**Common Issues:**
1. **Tests failing on slow devices**: Adjust `EXPECTED_SAVE_TIME_MS` constant
2. **Out of memory errors**: Reduce transaction counts in large wallet tests
3. **Asset loading failures**: Ensure test wallet file is in `androidTest/assets/`

### Adding Custom Test Wallets

1. Place your `.wallet` file in `androidTest/assets/`
2. Update the test to reference your wallet file
3. Ensure wallet uses TestNet3 parameters