/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

internal fun unverifiedAppName(label: String, appId: String): String {
    val safeLabel = label.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").ifBlank { "Unknown app" }
    return "Unverified app: \"$safeLabel\" [$appId]"
}
