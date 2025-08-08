/*
 * Copyright 2022 Dash Core Group.
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

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.schildbach.wallet.security.*
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.util.Constants.ANDROID_KEY_STORE
import org.dash.wallet.common.util.security.EncryptionProvider
import java.security.KeyStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    companion object {
        @Singleton
        @Provides
        fun providePinRetryController(): PinRetryController = PinRetryController.getInstance()

        @Provides
        @Singleton
        fun provideBiometricHelper(
            @ApplicationContext context: Context,
            configuration: Configuration
        ) = BiometricHelper(
            context,
            configuration
        )

        @Provides
        @Singleton
        fun provideEncryptionProvider(@ApplicationContext context: Context): EncryptionProvider {
            val securityPrefs = context.getSharedPreferences(SecurityGuard.SECURITY_PREFS_NAME, Context.MODE_PRIVATE)
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
            keyStore.load(null)

            return ModernEncryptionProvider(keyStore, securityPrefs)
        }
        
        @Provides
        @Singleton
        fun provideSecurityInitializer(
            encryptionProvider: EncryptionProvider,
            backupConfig: SecurityBackupConfig
        ): SecurityInitializer {
            return SecurityInitializer(encryptionProvider, backupConfig)
        }
    }

    @Binds
    abstract fun bindSecurityFunctions(
        securityFunctions: SecurityFunctions
    ): AuthenticationManager
}
