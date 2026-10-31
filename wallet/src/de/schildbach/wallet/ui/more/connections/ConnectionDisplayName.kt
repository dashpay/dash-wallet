/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

internal fun connectionDisplayName(label: String): String {
    val safeLabel = label.replace(Regex("[\\p{Cc}\\p{Cf}]"), "").ifBlank { "Unknown app" }
    return DashConnectBranding.appName(safeLabel)
}
