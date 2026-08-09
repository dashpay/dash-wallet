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
import org.dashfoundation.dashsdk.wallet.CoreTransactionBuilder;
import org.dashfoundation.dashsdk.wallet.FinalizedCoreTransaction;
import org.dashfoundation.dashsdk.wallet.ManagedCoreWallet;
import org.dashfoundation.dashsdk.wallet.ManagedPlatformWallet;

/**
 * The SDK send-all (drain) bridge — Step B of the dashj kill-list, closing
 * the {@code SendRequest.emptyWallet} GAP.
 *
 * <p>The Kotlin SDK's public send surface
 * ({@code ManagedPlatformWallet.sendToAddresses}) never exposes the
 * builder's drain strategy: the builder API is {@code internal} to the SDK
 * module. The JNI knob IS already bound —
 * {@code WalletManagerNative.coreTxBuilderSetSelectionStrategy} with
 * {@code CoreTransactionBuilder.SelectionStrategy.ALL} (FFI value 5 →
 * {@code CoreSelectionStrategyFFI::All} → key-wallet
 * {@code SelectionStrategy::All}) — so this shim drives the same builder
 * sequence {@code sendToAddresses} uses ({@code new → addOutput →
 * finalizeAtomic → broadcast}), plus that one extra setter.
 *
 * <p>Since v41int19 (dashpay/platform#4329) the builder is consumed through
 * {@code finalizeAtomic}: Rust performs funding selection and ReservationSet
 * insertion in ONE indivisible operation under the wallet-manager lock,
 * drops the lock, and only then invokes the mnemonic resolver — the
 * concurrency-safe replacement for the removed {@code setFunding} +
 * {@code buildSigned} split (#4323; the deprecated shims still linked, but
 * the split path is not concurrency-safe and is not used here anymore).
 *
 * <p>This is a JAVA class on purpose: Kotlin resolves the SDK's
 * {@code internal} declarations through Kotlin metadata and refuses the
 * call, but at the JVM level the members are public with the stable
 * {@code $sdk_release} mangling of the PINNED AAR (pin-don't-track per the
 * upstream-adoption guardrails), and javac links against the bytecode
 * directly. If a future AAR changes the module name or visibility, this
 * class fails to COMPILE — a loud, pre-runtime canary.
 *
 * <p>Drain semantics (key-wallet {@code coin_selection.rs} /
 * {@code transaction_builder.rs}, {@code SelectionStrategy::All}):
 * <ul>
 *   <li>every spendable UTXO of the funding set is selected as an input —
 *       for the user-facing send-all that set is the POOLED
 *       {@code ALL_SPENDABLE} accounts (BIP44 + BIP32 + every DashPay
 *       contact-receiving account; CoinJoin and watch-only stay out), so
 *       the whole max send is ONE transaction (#4329);</li>
 *   <li>exactly ONE output is allowed; its value is OVERWRITTEN by the
 *       engine to {@code total_input - fee} — the caller's amount is a
 *       floor, not the deliverable;</li>
 *   <li>no change output is emitted (the change address is dropped before
 *       fee sizing);</li>
 *   <li>the caller's output amount still participates in the
 *       {@code total_input < amount + fee} guard, so it acts as a
 *       DELIVER-AT-LEAST floor: a floor above {@code total - fee} fails the
 *       build pre-broadcast with the typed insufficient-funds shortfall —
 *       the adjust-down retry hook the Kotlin side uses
 *       ({@code isSendAllShortfall}).</li>
 * </ul>
 *
 * <p>CONCURRENCY CONTRACT: callers MUST serialize this whole call under the
 * app-owned per-source send-all lock (the Kotlin side does, via
 * {@code SdkL1SendSource.withCoreSendLock} — an app-side
 * {@code kotlinx.coroutines.sync.Mutex}, ONE acquisition spanning the drain
 * attempt AND its adjust-down retry, so a concurrent plain send cannot
 * change the drained balance between attempts). This is an APP-side lock,
 * NOT a mutex owned by the SDK binary. The cross-path double-select
 * backstop is the atomic select+reserve inside {@code finalizeAtomic}
 * itself (the wallet-manager lock), which the app lock complements but does
 * not replace.
 *
 * <p>SELECTION CAVEAT (why {@link SdkL1SendService} guards the drain):
 * {@code SelectionStrategy::All} selects EVERY spendable UTXO and the FFI
 * exposes NO lock/exclusion API — app-side dashj locks
 * ({@code Wallet.lockOutput}, the CrowdNode account outputs) are invisible
 * here, so the Kotlin caller must refuse the drain while any exist.
 * Upstream fix: an SDK UTXO lock/exclusion API (iOS's
 * {@code add_inputs_from_outpoints} binding is the porting candidate).
 *
 * <p>Failure classification: every native failure surfaces as the same
 * {@code DashSDKException} the normal send path produces; the Kotlin caller
 * wraps this call in {@code mapNativeErrors}, so
 * {@code classifyCoreSendFailure}'s decision table applies unchanged
 * (build/funding failures → provably pre-broadcast; broadcast outcomes keep
 * their existing semantics). A definitive broadcast rejection releases the
 * reservation {@code finalizeAtomic} took; a failure BETWEEN the successful
 * finalize and the broadcast (a {@code coreWallet()} handle failure,
 * process death) leaves the reservation to its engine-side TTL — no funds
 * move, but those inputs are unavailable to new SDK builds for the TTL
 * window (the accepted KNOWN LOW in the classifyCoreSendFailure KDoc).
 */
final class CoreSendAllNative {

    private CoreSendAllNative() {}

    /**
     * Build, sign and broadcast a send-all (drain) of the POOLED
     * {@code ALL_SPENDABLE} funding set to {@code addressBase58}: all
     * spendable inputs of BIP44 + BIP32 + every DashPay receival account in
     * ONE transaction, single output worth everything minus the
     * engine-computed fee, no change. CoinJoin is NOT drained — that stays
     * the separate, explicit {@link #buildSignBroadcastDrainCoinJoin} flow.
     *
     * @param wallet the bound SDK wallet (same seed/coins as dashj).
     * @param network the app network; output address is re-validated
     *     against it Rust-side.
     * @param addressBase58 the destination address.
     * @param floorDuffs deliver-at-least floor in duffs (must be positive —
     *     the JNI boundary rejects {@code <= 0}). The engine overwrites the
     *     output value with {@code total - fee}; if that is below this
     *     floor the build fails pre-broadcast with the typed
     *     insufficient-funds shortfall.
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
        return buildSignBroadcastDrain(
                wallet,
                network,
                addressBase58,
                floorDuffs,
                coreSignerHandle,
                CoreTransactionBuilder.AccountType.ALL_SPENDABLE
        );
    }

    /**
     * Build, sign and broadcast a drain of the DIP-9 CoinJoin account
     * ({@code m/9'/coin'/4'/0'}) to {@code addressBase58} — the "combine my
     * previously mixed funds back into the spendable (unmixed) balance"
     * half of the post-upgrade mixed-funds migration.
     *
     * <p>PRIVACY INVARIANT: the builder is pointed at ONE account
     * ({@code AccountType.COIN_JOIN}, index 0); the atomic finalizer seeds
     * the input set from that account's UTXO map alone, so BIP44 coins
     * cannot be co-spent — this is emphatically NOT the {@code ALL_SPENDABLE}
     * pool (which itself deliberately excludes CoinJoin: crossing that
     * privacy domain stays an explicit user choice). It DOES de-mix: the
     * resulting transaction publicly links the CoinJoin outputs to the
     * destination BIP44 address, which is why the UI must say so before the
     * user picks it.
     *
     * <p>{@code SelectionStrategy::All} means no change output is emitted at
     * all, so the "non-Standard accounts cannot derive change" limitation
     * never applies. The account is therefore left EMPTY, which is what makes
     * the migration prompt one-shot.
     *
     * <p>{@code addressBase58} MUST be an address the SAME wallet owns on
     * its unmixed BIP44 account — callers derive it from the wallet, never
     * from user input.
     *
     * <p>Same concurrency contract and failure classification as
     * {@link #buildSignBroadcastSendAll}.
     */
    static String buildSignBroadcastDrainCoinJoin(
            ManagedPlatformWallet wallet,
            Network network,
            String addressBase58,
            long floorDuffs,
            long coreSignerHandle
    ) {
        return buildSignBroadcastDrain(
                wallet,
                network,
                addressBase58,
                floorDuffs,
                coreSignerHandle,
                CoreTransactionBuilder.AccountType.COIN_JOIN
        );
    }

    private static String buildSignBroadcastDrain(
            ManagedPlatformWallet wallet,
            Network network,
            String addressBase58,
            long floorDuffs,
            long coreSignerHandle,
            CoreTransactionBuilder.AccountType accountType
    ) {
        FinalizedCoreTransaction finalized = null;
        // Same shape as ManagedPlatformWallet.sendToAddresses: finalizeAtomic
        // consumes the builder; close() in finally safely destroys it on the
        // pre-finalize failure paths and is a no-op once consumed.
        CoreTransactionBuilder builder = new CoreTransactionBuilder(network);
        try {
            builder.addOutput$sdk_release(addressBase58, floorDuffs);
            builder.setSelectionStrategy$sdk_release(CoreTransactionBuilder.SelectionStrategy.ALL);
            // ONE indivisible native operation: select + reserve the inputs
            // under the wallet-manager lock, then sign via the resolver.
            finalized = builder.finalizeAtomic$sdk_release(
                    wallet,
                    accountType,
                    0,
                    coreSignerHandle
            );
            try (ManagedCoreWallet core = wallet.coreWallet()) {
                // broadcastTransaction(FinalizedCoreTransaction) consumes the
                // finalized handle; a definitive rejection releases the UTXO
                // reservation finalizeAtomic took.
                return core.broadcastTransaction(finalized);
            }
        } finally {
            // No-ops once consumed (broadcast took the handle); real releases
            // on the pre-broadcast failure paths.
            if (finalized != null) {
                finalized.close();
            }
            builder.close();
        }
    }
}
