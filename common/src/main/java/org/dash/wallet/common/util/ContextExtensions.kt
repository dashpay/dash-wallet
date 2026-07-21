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

package org.dash.wallet.common.util

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import androidx.fragment.app.FragmentActivity
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.ArrayRes
import androidx.annotation.RequiresApi
import java.util.Locale

fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    val uri = Uri.fromParts("package", packageName, null)
    intent.data = uri
    startActivity(intent)
}

@RequiresApi(Build.VERSION_CODES.O)
fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    startActivity(intent)
}

@RequiresApi(Build.VERSION_CODES.O)
fun Context.openNotificationChannelSettings(channel: String) {
    val settingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    startActivity(settingsIntent)
}

fun Context.findFragmentActivity(): FragmentActivity {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    throw IllegalStateException("No FragmentActivity found in context chain")
}

/**
 * A locale replaces a string array wholesale, so a partially translated array can be shorter
 * than the default one. When the localized array has fewer than [expectedSize] items,
 * this returns the array from the default (untranslated) resources instead.
 */
fun Context.getStringArrayOrDefault(@ArrayRes id: Int, expectedSize: Int): Array<String> {
    val localized = resources.getStringArray(id)

    if (localized.size >= expectedSize) {
        return localized
    }

    val config = Configuration(resources.configuration)
    config.setLocale(Locale.ROOT)
    return createConfigurationContext(config).resources.getStringArray(id)
}

fun Context.shareText(textToShare: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "text/plain"
    intent.putExtra(Intent.EXTRA_TEXT, textToShare)
    startActivity(Intent.createChooser(intent, title))
}
