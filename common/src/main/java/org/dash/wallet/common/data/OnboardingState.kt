/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dash.wallet.common.data

import org.dash.wallet.common.Configuration
import org.slf4j.LoggerFactory

/**
 * This object keeps track of the onboarding process
 *
 * It has a refCount field that is incremented with add(), which should be called when
 * an activity that is part of the onboarding process is created.  remove() will decrement
 * the refCount when that activity is destroyed.
 *
 * If the the app is closed during the onboarding process, then all activities will be
 * destroyed and the refCount goes to 0, which means onboarding is not in progress
 *
 * If refCount > 0, then onboarding is in progress
 */
object OnboardingState {
    private val log = LoggerFactory.getLogger(OnboardingState::class.java)
    private lateinit var configuration: Configuration
    private var refCount = 0

    fun init(configuration: Configuration) {
        if (!this::configuration.isInitialized) {
            this.configuration = configuration
        }
        refCount = configuration.onboardingStage
    }

    fun add() {
        // After process death Android can recreate an onboarding activity before
        // any code path has called init(); dropping the count is better than crashing
        if (!this::configuration.isInitialized) {
            log.warn("add() called before init(); ignoring — onboarding stage not persisted")
            return
        }
        refCount++
        configuration.onboardingStage = refCount
    }

    fun remove() {
        if (!this::configuration.isInitialized) {
            log.warn("remove() called before init(); ignoring — onboarding stage not persisted")
            return
        }
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