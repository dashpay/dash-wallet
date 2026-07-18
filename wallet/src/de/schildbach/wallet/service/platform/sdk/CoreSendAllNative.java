/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.schildbach.wallet.service.platform.sdk;

import org.dashfoundation.dashsdk.Network;
import org.dashfoundation.dashsdk.wallet.CoreTransaction;
import org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder;
import org.dashfoundation.dashsdk.wallet.ManagedCoreWallet;
import org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet;

/**
 * The SDK send-all (drain) bridge — Step B of the dashj kill-list, closing
 * the {@code SendRequest.emptyWallet} GAP.
 *
 * <p>The Kotlin SDK's public send surface
 * ({@code ManagedPlatformWallet.sendToAddresses}) never exposes the
 * builder's drain strategy: the split builder API is {@code internal} to
 * the SDK module solely to force every build through the per-wallet
 * {@code coreSendMutex} (see the {@code CoreTransactionBuilder} KDoc). The
 * JNI knob IS already bound —
 * {@code WalletManagerNative.coreTxBuilderSetSelectionStrategy} with
 * {@code CoreTransactionBuilder.SelectionStrategy.ALL} (FFI value 5 →
 * {@code CoreSelectionStrategyFFI::All} → key-wallet
 * {@code SelectionStrategy::All}) — so this shim drives the same builder
 * sequence {@code sendToAddresses} uses, plus that one extra setter.
 *
 * <p>This is a JAVA class on purpose: Kotlin resolves the SDK's
 * {@code internal} declarations through Kotlin metadata and refuses the
 * call, but at the JVM level the members are public with the stable
 * {@code $sdk_release} mangling of the PINNED AAR
 * ({@code org.dashj:dash-sdk-android:0.1.0-SNAPSHOT} — pin-don't-track per
 * the upstream-adoption guardrails), and javac links against the bytecode
 * directly. If a future AAR changes the module name or visibility, this
 * class fails to COMPILE — a loud, pre-runtime canary.
 *
 * <p>NOT routed through the legacy key-wallet-ffi transaction-build path:
 * that path hardcodes BranchAndBound selection
 * ({@code key-wallet-ffi/src/transaction.rs}) and the strategy knob does
 * not apply there. The {@code core_wallet_tx_builder_*} path used here is
 * the one where the knob is honored.
 *
 * <p>Drain semantics (key-wallet {@code coin_selection.rs} /
 * {@code transaction_builder.rs}, {@code SelectionStrategy::All}):
 * <ul>
 *   <li>every spendable UTXO of the account is selected as an input;</li>
 *   <li>exactly ONE output is allowed; its value is OVERWRITTEN by the
 *       engine to {@code total_input - fee} — the caller's amount is a
 *       floor, not the deliverable;</li>
 *   <li>no change output is emitted (the change address is dropped before
 *       fee sizing);</li>
 *   <li>the caller's output amount still participates in the
 *       {@code total_input < amount + fee} guard, so it acts as a
 *       DELIVER-AT-LEAST floor: a floor above {@code total - fee} fails the
 *       build with "Insufficient funds" (pre-broadcast) — the adjust-down
 *       retry hook the Kotlin side uses.</li>
 * </ul>
 *
 * <p>CONCURRENCY CONTRACT: callers MUST hold the wallet's
 * {@code coreSendMutex} across this whole call (the Kotlin wrapper does,
 * via {@link SdkL1SendService}'s source) — the builder's setFunding and
 * buildSigned are two separate FFI calls, and two concurrent same-account
 * builds could otherwise select and sign the same UTXOs.
 *
 * <p>Failure classification: every native failure surfaces as the same
 * {@code DashSDKException} the normal send path produces; the Kotlin caller
 * wraps this call in {@code mapNativeErrors}, so
 * {@code classifyCoreSendFailure}'s decision table applies unchanged
 * (build/funding failures → provably pre-broadcast; broadcast outcomes keep
 * their existing semantics, including released UTXO reservations on a
 * definitive rejection).
 */
final class CoreSendAllNative {

    private CoreSendAllNative() {}

    /**
     * Build, sign and broadcast a send-all (drain) of BIP44 account 0 to
     * {@code addressBase58}: all spendable inputs, single output worth
     * everything minus the engine-computed fee, no change.
     *
     * @param wallet the bound SDK wallet (same seed/coins as dashj).
     * @param network the app network; output address is re-validated
     *     against it Rust-side.
     * @param addressBase58 the destination address.
     * @param floorDuffs deliver-at-least floor in duffs (must be positive —
     *     the JNI boundary rejects {@code <= 0}). The engine overwrites the
     *     output value with {@code total - fee}; if that is below this
     *     floor the build fails pre-broadcast with "Insufficient funds".
     * @param coreSignerHandle the manager's {@code MnemonicResolverHandle};
     *     no private key crosses the boundary.
     * @return the broadcast txid as lowercase hex.
     */
    static String buildSignBroadcastSendAll(
            ManagedPlatformWallet wallet,
            Network network,
            String addressBase58,
            long floorDuffs,
            long coreSignerHandle
    ) {
        CoreTransaction signed = null;
        // Same shape as ManagedPlatformWallet.sendToAddresses: buildSigned
        // consumes the builder; close() in finally safely destroys it on the
        // pre-build failure paths and is a no-op once consumed.
        CoreTransactionBuilder builder = new CoreTransactionBuilder(network);
        try {
            builder.addOutput$sdk_release(addressBase58, floorDuffs);
            builder.setSelectionStrategy$sdk_release(CoreTransactionBuilder.SelectionStrategy.ALL);
            builder.setFunding$sdk_release(wallet, CoreTransactionBuilder.AccountType.BIP44, 0);
            signed = builder.buildSigned$sdk_release(
                    wallet,
                    CoreTransactionBuilder.AccountType.BIP44,
                    0,
                    coreSignerHandle
            );
            try (ManagedCoreWallet core = wallet.coreWallet()) {
                // The signed tx carries its funding account, so a failed
                // broadcast releases the UTXO reservation buildSigned took.
                return core.broadcastTransaction(signed);
            }
        } finally {
            if (signed != null) {
                signed.close();
            }
            builder.close();
        }
    }
}
