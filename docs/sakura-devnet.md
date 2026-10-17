# Sakura Devnet

The `devnet` flavor targets `devnet-sakura`. Its application ID is
`org.dash.wallet.devnet`, reusing the existing Firebase-registered devnet app.
Only one devnet is supported at a time; this ID shares app storage with any
previously installed wallet using the same ID, but is separate from testnet and mainnet.
Wallet, chain, and bootstrap filenames use the existing `-sakura` suffix mechanism.
No DashJ release or key-derivation changes are required.

## Prerequisites

- An updated `5.0.0-SNAPSHOT` Platform SDK containing `NetworkConfiguration`
  and the explicit-network native constructor, including rebuilt Android libraries.
  The previously published snapshot without those APIs is not sufficient.
- Set `sakuraSporkAddress` in local.properties or with `-PsakuraSporkAddress=...`.
  This is the operator's public signing address, never a private key. No other
  devnet's address is substituted. Missing values fail the devnet build; malformed
  addresses are also rejected when constructing the network parameters.
- Core port defaults to `20001` by assumption; override with `-PsakuraCorePort=...`.

Build with `./gradlew :wallet:assembleDevnetDebug` after installing the updated SDK.
The local, Git-ignored `wallet/google-services.json` must contain
`org.dash.wallet.devnet` so Firebase-dependent startup workers can initialize.

## Validation Still Required

Core peers come from the Sakura status page's 13 validator IP addresses.
The SDK supplies HTTPS DAPI endpoints on port 1443. Core protocol is 70240.
DashJ's default devnet quorum and activation settings are retained as an explicit
testing assumption, not verified Sakura consensus configuration.

Before funding the wallet, confirm Core synchronization, refresh the Platform
protocol version, check fresh block timestamps, and fetch a data contract with
proof verification enabled. The existing masternode-list quorum callback remains
in use; no proof verification or TLS validation is bypassed.

Source: https://status.testnet.networks.dash.org/n/devnet-sakura/connect
