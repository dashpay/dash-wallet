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

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import org.dash.wallet.common.R
import org.dash.wallet.common.customtabs.CustomTabActivityHelper

fun Activity.openCustomTab(url: String) {
    val fixedUrl = if (!url.startsWith("http")) "https://$url" else url
    val builder = CustomTabsIntent.Builder()
    val toolbarColor = ContextCompat.getColor(this, R.color.colorPrimary)
    val customTabsIntent = builder.setShowTitle(true).setToolbarColor(toolbarColor).build()

    CustomTabActivityHelper.openCustomTab(this, customTabsIntent, Uri.parse(fixedUrl)) { _, _ ->
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(fixedUrl)
        startActivity(intent)
    }
}

fun AppCompatActivity.getMainTask(): ActivityManager.AppTask {
    val activityManager = getSystemService(AppCompatActivity.ACTIVITY_SERVICE) as ActivityManager
    return activityManager.appTasks.last()
}
