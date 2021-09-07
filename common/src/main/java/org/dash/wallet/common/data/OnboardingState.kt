package org.dash.wallet.common.data

import org.dash.wallet.common.Configuration

object OnboardingState {
    private lateinit var configuration: Configuration
    private var refCount = 0

    fun init(configuration: Configuration) {
        if (!this::configuration.isInitialized) {
            this.configuration = configuration
        }
        refCount = configuration.onboardingStage
    }

    fun add() {
        refCount++
        configuration.onboardingStage = refCount
    }

    fun remove() {
        refCount--
        configuration.onboardingStage = refCount
    }

    fun clear() {
        refCount = 0
    }

    fun isOnboarding(): Boolean {
        return refCount > 0
    }
}