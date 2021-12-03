package org.dash.wallet.common.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.qualifiers.ActivityContext
import org.dash.wallet.common.ui.BaseAlertDialogBuilder

@Module
@InstallIn(ActivityComponent::class)
object BaseAlertDialogBuilderModule {
    @Provides
    fun provideBaseAlertDialogBuilder(@ActivityContext context: Context) : BaseAlertDialogBuilder =
        BaseAlertDialogBuilder(context)
}
