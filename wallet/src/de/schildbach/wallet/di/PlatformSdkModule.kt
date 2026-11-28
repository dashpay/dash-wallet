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

package de.schildbach.wallet.di

import dagger.Binds
import dagger.Provides
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.service.platform.sdk.DashPayBackfillGate
import de.schildbach.wallet.service.platform.sdk.DashPayBackfillGateImpl
import de.schildbach.wallet.service.platform.sdk.DashSdkMessageSigner
import de.schildbach.wallet.service.platform.sdk.DashSdkService
import de.schildbach.wallet.service.platform.sdk.DashSdkServiceImpl
import de.schildbach.wallet.service.platform.sdk.PlatformMnemonicProvider
import de.schildbach.wallet.service.platform.sdk.SdkMessageSigner
import de.schildbach.wallet.service.platform.sdk.SecurityGuardMnemonicProvider
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceServiceImpl
import javax.inject.Singleton

/**
 * Hilt bindings for the Dash Platform Kotlin SDK scaffold — Phase 3 of the
 * dashj → Kotlin SDK migration (`docs/kotlin-sdk-migration-plan.md`).
 *
 * Both bindings are lazy `@Binds` (no `@Provides` factory work, no eager
 * initialization): nothing here runs at app startup, and the SDK's native
 * library is only loaded when some caller explicitly invokes
 * [DashSdkService.ensureStarted]. No production code path does so in
 * Phase 3.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformSdkModule {

    @Singleton
    @Binds
    abstract fun bindDashSdkService(dashSdkService: DashSdkServiceImpl): DashSdkService

    /**
     * The Phase 3b dashj seed bridge. Never prompts: callers pass an
     * explicit [de.schildbach.wallet.service.platform.sdk.WalletUnlock]
     * proving the user already authenticated.
     */
    @Singleton
    @Binds
    abstract fun bindPlatformMnemonicProvider(
        provider: SecurityGuardMnemonicProvider
    ): PlatformMnemonicProvider

    /**
     * Phase 4 shielded-balances plumbing. Also lazy: inert until a caller
     * invokes a method, and provably inert while
     * [de.schildbach.wallet.ui.dashpay.utils.DashPayConfig.USE_KOTLIN_SDK_SHIELDED]
     * (default OFF) stays off. No production call sites yet — this is the
     * API layer for the upcoming shielded UI.
     */
    @Singleton
    @Binds
    abstract fun bindShieldedBalanceService(
        service: ShieldedBalanceServiceImpl
    ): ShieldedBalanceService

    /**
     * The SDK message-signing seam behind
     * [de.schildbach.wallet.security.SecurityFunctions.signMessage] (the
     * CrowdNode `RegisterEmail` / `Withdrawal` request signatures). Lazy
     * like the rest: the SDK only boots if a signature is actually
     * requested.
     */
    @Singleton
    @Binds
    abstract fun bindSdkMessageSigner(
        signer: DashSdkMessageSigner
    ): SdkMessageSigner

    companion object {
        /**
         * The DIP-15 coreHeight-backfill gate is DISABLED (bound to the
         * no-op [DashPayBackfillGate.ALWAYS_RUN]) now that the ordered
         * wallet bring-up (`startWalletSubsystems`, called before `startSpv`
         * in [de.schildbach.wallet.service.platform.sdk.L1ShadowSyncService])
         * registers contact receival accounts BEFORE the compact-filter
         * scan — so there is no post-scan gap for the gate to detect or
         * rewind for. This matches iOS, which has no such host gate. The
         * gate's watch channel could not complete anyway (the SDK's
         * persisted sync cursor is monotonic-max guarded, so the durable
         * watermark drop it waited for never lands).
         *
         * [DashPayBackfillGateImpl] is left in the tree, unused, pending a
         * later deliberate removal; flip this back to a
         * `@Binds DashPayBackfillGateImpl` to re-enable it. See
         * FIXES-restored-wallets.md #1/#5.
         */
        @Singleton
        @Provides
        fun provideDashPayBackfillGate(): DashPayBackfillGate =
            DashPayBackfillGate.ALWAYS_RUN
    }
}
