/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package de.schildbach.wallet.service.platform.sdk

import de.schildbach.wallet_test.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/** Build-variant rollout gates shared by SDK startup and cutover ownership. */
@Singleton
class SdkRolloutPolicy internal constructor(
    val migrationFlagsDefaultOn: Boolean,
    val cutoverEnabled: Boolean
) {
    @Inject
    constructor() : this(
        migrationFlagsDefaultOn = BuildConfig.SDK_MIGRATION_FLAGS_DEFAULT_ON,
        cutoverEnabled = BuildConfig.SDK_CUTOVER_ENABLED
    )
}
