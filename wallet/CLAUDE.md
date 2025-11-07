# Wallet Module Structure

This document describes the non-standard directory structure used in the wallet module, as defined in `wallet/build.gradle`.

## Directory Structure Overview

The wallet module uses a **non-standard Android project structure** that differs from the typical `src/main/java` layout. This is explicitly configured in the `sourceSets` block in `build.gradle`.

### Source Sets Configuration

```gradle
sourceSets {
    main {
        manifest.srcFile 'AndroidManifest.xml'
        java.srcDirs = ['src']
        res.srcDirs = ['res']
        assets.srcDirs = ['assets']
    }
    test {
        java.srcDirs = ['test']
        resources.srcDirs = ['test']
    }
    androidTest {
        assets.srcDirs += "$projectDir/schemas"
        java.srcDirs = ['androidTest']
        resources.srcDirs = ['androidTest/java']
        res.srcDirs = ['androidTest/testNet3/res']
    }
    _testNet3 {
        res.srcDirs = ["testNet3/res"]
    }
    staging {
        res.srcDirs = ["staging/res"]
    }
    devnet {
        res.srcDirs = ["devnet/res"]
    }
}
```

## Actual Directory Layout

### Main Source Code
- **Java/Kotlin source**: `src/` (not `src/main/java/`)
- **Resources**: `res/` (not `src/main/res/`)
- **Assets**: `assets/` (not `src/main/assets/`)
- **Manifest**: `AndroidManifest.xml` (root level, not `src/main/`)

### Test Source Code
- **Unit tests**: `test/` (not `src/test/java/`)
- **Unit test resources**: `test/` (not `src/test/resources/`)
- **Instrumentation tests**: `androidTest/` (not `src/androidTest/java/`)

### Product Flavor Resources
Each product flavor has its own resource directory:
- **TestNet3**: `testNet3/res/`
- **Staging**: `staging/res/`  
- **DevNet**: `devnet/res/`

### Schema Files
- **Room database schemas**: `schemas/` (also included in androidTest assets)

## Product Flavors

The module defines four product flavors:
- **prod**: Production mainnet build (`hashengineering.darkcoin.wallet`)
- **_testNet3**: TestNet development build (`hashengineering.darkcoin.wallet_test`)
- **staging**: Staging testnet build (`org.dash.dashpay.testnet`)
- **devnet**: DevNet build (`org.dash.dashpay.devnet`)

## Important Notes

1. **Non-standard paths**: This structure predates modern Android conventions
2. **Source directory**: Use `src/` instead of `src/main/java/` for source files
3. **Resource resolution**: Flavor-specific resources override main resources
4. **Test structure**: Tests are in `test/` and `androidTest/` at the module root
5. **Configuration files**: Build configuration and secrets are loaded from parent directory files (`../service.properties`, `../local.properties`)

## When Working with This Module

- Source files are located in `src/de/schildbach/wallet/...`
- Resources are in `res/` at the module root
- Unit tests are in `test/` at the module root
- Always check the appropriate flavor directory for flavor-specific resources
- The build system is configured to handle this non-standard structure automatically

This structure is maintained for historical compatibility and works correctly with the configured Gradle build system.