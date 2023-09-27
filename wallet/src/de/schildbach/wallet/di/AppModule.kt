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
import de.schildbach.wallet.payments.ConfirmTransactionLauncher
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.service.*
import de.schildbach.wallet.service.AndroidActionsService
import de.schildbach.wallet.service.AppRestartService
import de.schildbach.wallet.service.RestartService
import de.schildbach.wallet.ui.notifications.NotificationManagerWrapper
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.*
import org.dash.wallet.common.services.ConfirmTransactionService
import org.dash.wallet.common.services.LockScreenBroadcaster
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.dash.wallet.integrations.uphold.api.UpholdClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    companion object {
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
        fun provideTelephonyService(@ApplicationContext context: Context): TelephonyManager =
            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        @Singleton
        @Provides
        fun provideConfiguration(@ApplicationContext context: Context): Configuration =
            Configuration(PreferenceManager.getDefaultSharedPreferences(context), context.resources)
    }

    @Binds
    abstract fun bindAnalyticsService(
        analyticsService: FirebaseAnalyticsServiceImpl
    ): AnalyticsService

    @Binds
    abstract fun bindSendPaymentService(
        sendCoinsTaskRunner: SendCoinsTaskRunner
    ): SendPaymentService

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
}
