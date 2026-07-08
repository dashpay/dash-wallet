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
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.service.platform.sdk.DashSdkService
import de.schildbach.wallet.service.platform.sdk.DashSdkServiceImpl
import de.schildbach.wallet.service.platform.sdk.PlatformMnemonicProvider
import de.schildbach.wallet.service.platform.sdk.SecurityGuardMnemonicProvider
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
}
