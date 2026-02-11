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

package de.schildbach.wallet.ui.dashpay

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.room.Entity
import androidx.room.PrimaryKey
import de.schildbach.wallet_test.R
import java.util.*

@Entity(tableName = "user_alerts")
data class UserAlert(
    @PrimaryKey val stringResId: Int,
    @DrawableRes val iconResId: Int,
    var dismissed: Boolean = false,
    val createdAt: Long = Date().time)
{
    companion object {
        const val INVITATION_NOTIFICATION_TEXT = 1
        val textMap = hashMapOf(INVITATION_NOTIFICATION_TEXT to R.string.invitation_notification_text)

        const val INVITATION_NOTIFICATION_ICON = 1000
        val iconMap = hashMapOf(INVITATION_NOTIFICATION_ICON to R.drawable.ic_invitation)

        fun getIdFromStringRes(@StringRes stringResId: Int) =
            textMap.filter { it.value == stringResId }.map { it.key }.firstOrNull()
    }


    val stringResourceId: Int
        get() = textMap[stringResId] ?: INVITATION_NOTIFICATION_TEXT
    val iconResourceId: Int
        get() = iconMap[iconResId] ?: INVITATION_NOTIFICATION_ICON
}