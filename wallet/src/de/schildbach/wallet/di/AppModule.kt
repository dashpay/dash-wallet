/*
 * Copyright 2021 Dash Core Group.
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

import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.telephony.TelephonyManager
import androidx.preference.PreferenceManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.payments.ConfirmTransactionLauncher
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.service.*
import de.schildbach.wallet.service.AndroidActionsService
import de.schildbach.wallet.service.AppRestartService
import de.schildbach.wallet.service.RestartService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.more.tools.ZenLedgerApi
import de.schildbach.wallet.ui.more.tools.ZenLedgerClient
import de.schildbach.wallet.ui.notifications.NotificationManagerWrapper
import de.schildbach.wallet_test.BuildConfig
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.*
import org.dash.wallet.common.services.ConfirmTransactionService
import org.dash.wallet.common.services.LockScreenBroadcaster
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.dash.wallet.integrations.uphold.api.UpholdClient
import org.dash.wallet.features.exploredash.network.service.stubs.FakeDashSpendService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    companion object {
        @Provides
        fun provideContext(@ApplicationContext context: Context): Context {
            return context
        }

        @Provides
        fun provideApplication(
            @ApplicationContext context: Context
        ): WalletApplication = context as WalletApplication

        @Singleton
        @Provides
        fun provideLockScreenBroadcaster(): LockScreenBroadcaster = LockScreenBroadcaster()

        @Provides
        fun provideClipboardManager(
            @ApplicationContext context: Context
        ) = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        @Provides
        fun provideUphold(): UpholdClient = UpholdClient.getInstance()

        @Provides
        @Singleton
        fun providePackageInfoProvider(@ApplicationContext context: Context) = PackageInfoProvider(context)

        @Provides
        fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        @Provides
        fun provideDeviceInfo(@ApplicationContext context: Context): DeviceInfoProvider =
            DeviceInfoProvider(context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)

        @Singleton
        @Provides
        fun provideConfiguration(@ApplicationContext context: Context): Configuration =
            Configuration(PreferenceManager.getDefaultSharedPreferences(context), context.resources)

        @Provides
        fun provideSendPaymentService(
            walletData: WalletDataProvider,
            walletApplication: WalletApplication,
            securityFunctions: SecurityFunctions,
            packageInfoProvider: PackageInfoProvider,
            analyticsService: AnalyticsService,
            identityConfig: BlockchainIdentityConfig,
            coinJoinConfig: CoinJoinConfig,
            platformRepo: PlatformRepo
        ): SendPaymentService {
            val realService = SendCoinsTaskRunner(walletData, walletApplication, securityFunctions, packageInfoProvider, analyticsService, identityConfig, coinJoinConfig, platformRepo)

            return if (BuildConfig.FLAVOR.lowercase() == "prod") {
                realService
            } else {
                FakeDashSpendService(realService, walletData)
            }
        }
    }

    @Binds
    abstract fun bindAnalyticsService(
        analyticsService: FirebaseAnalyticsServiceImpl
    ): AnalyticsService

    @Binds
    abstract fun bindConfirmTransactionService(
        confirmTransactionLauncher: ConfirmTransactionLauncher
    ): ConfirmTransactionService

    @Binds
    abstract fun bindNotificationService(
        notificationService: NotificationManagerWrapper
    ): NotificationService

    @Binds
    abstract fun bindRestartService(
        restartService: AppRestartService
    ): RestartService

    @Binds
    abstract fun bindClipboardService(
        clipboardService: AndroidActionsService
    ): SystemActionsService

    @Binds
    @Singleton
    abstract fun bindNetworkState(networkState: NetworkState) : NetworkStateInt

    @Binds
    abstract fun bindWalletFactory(walletFactory: DashWalletFactory) : WalletFactory

    @Binds
    @Singleton
    abstract fun bindZenLedgerClient(zenLedgerClient: ZenLedgerClient): ZenLedgerApi

    @Singleton
    @Binds
    abstract fun provideDashSystemService(dashSystemService: DashSystemServiceImpl): DashSystemService
}
