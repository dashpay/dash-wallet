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
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.ui.notifications.NotificationManagerWrapper
import de.schildbach.wallet.ui.security.PinCodeRequestLauncher
import org.dash.wallet.common.services.LockScreenBroadcaster
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.SecurityModel
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
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
    abstract fun bindNotificationService(
        notificationService: NotificationManagerWrapper
    ): NotificationService

    @Binds
    abstract fun bindSecurityModel(
        pinCodeRequestLauncher: PinCodeRequestLauncher
    ): SecurityModel
}