# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Dash Wallet is an Android application for the Dash cryptocurrency. This is a multi-module Gradle project with the main wallet app and several supporting modules for integrations and features.

## Project Structure

- **wallet/**: Main Android wallet application (Kotlin/Java)
- **common/**: Shared components used across modules
- **features/exploredash/**: Explore Dash feature module
- **integrations/**: Third-party service integrations (Uphold, Coinbase, Crowdnode)
- **integration-android/**: Library for integrating Dash payments into other apps
- **sample-integration-android/**: Example integration app
- **market/**: App store materials

## Build Commands

### Development Builds (Testnet)
```bash
# Clean and build testnet debug version
./gradlew clean assemble_testNet3Debug -x test

# Install testnet debug build to device
adb install wallet/build/outputs/apk/dash-wallet-_testNet3-debug.apk
```

### Production Builds
```bash
# Build production release (requires proper signing keys and configuration)
./gradlew assembleProdRelease

# Build all flavors
./gradlew clean build assembleProdRelease
./gradlew clean build assemble_testNet3Release
./gradlew clean build assembleProdDebug
./gradlew clean build assemble_testNet3Debug
```

### Testing & Quick Builds
```bash
# Quick compilation check (fastest)
./gradlew :wallet:compile_testNet3DebugKotlin

# Compile specific build variant  
./gradlew :wallet:assemble_testNet3Debug

# Run unit tests
./gradlew :wallet:test_testNet3DebugUnitTest

# Run all tests and build
./gradlew build
```

### Code Quality
```bash
# Format Kotlin code with ktlint
./gradlew ktlintFormat
```

## Architecture

### Design Pattern
The app follows **MVVM (Model-View-ViewModel)** architecture with the following conventions:

1. **ViewModels** should use a single `UIState` data class rather than multiple separate flows
2. Use `StateFlow` (not `LiveData`) for asynchronously updated fields
3. Use private mutable `_uiState` with public immutable `uiState` via `asStateFlow()`
4. ViewModels are annotated with `@HiltViewModel` and use Dagger Hilt for dependency injection

### Key Technologies
- **Language**: Kotlin (primary), Java (legacy code)
- **UI**: Android Views with Data Binding, Jetpack Compose for newer components
- **Dependency Injection**: Dagger Hilt
- **Database**: Room with SQLite
- **Networking**: Retrofit, OkHttp
- **Architecture Components**: ViewModel, LiveData/StateFlow, Navigation Component
- **Cryptocurrency**: dashj library (fork of bitcoinj)

### Module Dependencies
- Main wallet module depends on common, features, and integration modules
- Uses external dependencies like dashj for Dash blockchain functionality
- Firebase for analytics and crash reporting
- Material Design components for UI

## Key Configuration Files

### Required for Full Build
- `wallet/google-services.json` - Firebase services configuration
- `service.properties` - API keys for Uphold and Coinbase integrations
- `local.properties` - Support email and Google Maps API keys

### Development Setup
- Set build variant to required flavor (e.g., `_testNet3Debug` for testnet development)
- Android SDK Build Tools version 35+ and NDK required
- Uses Gradle 8.9 with Kotlin 2.1.0

### Build Flavors
- **prod**: Production mainnet build (non-debuggable, ProGuard optimized)
- **_testNet3**: Development testnet build (debuggable, world-readable wallet file)

## Test Structure
- Unit tests: `wallet/test/` directory
- Uses JUnit for testing framework
- Key test files include ViewModelTest classes and utility tests

## Development Notes
- Testnet builds use world-readable wallet files for debugging
- Production builds have protected wallet files and are space-optimized
- The app supports both mainnet and testnet Dash networks
- Exchange rate data comes from multiple sources (CTX, BitPay, Dash Central, etc.)
- Supports NFC for Dash payment requests
- Uses dashj library for Dash-specific blockchain operations