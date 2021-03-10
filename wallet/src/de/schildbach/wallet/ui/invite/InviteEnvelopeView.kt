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

package de.schildbach.wallet.ui.invite

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.annotation.IntegerRes
import androidx.constraintlayout.widget.ConstraintLayout
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.invite_preview_envelope_view.view.*


@Suppress("LeakingThis")
open class InviteEnvelopeView(context: Context, attrs: AttributeSet?) : ConstraintLayout(context, attrs) {

    @IntegerRes
    open val contentViewResId: Int = 0

    init {
        inflate(context, contentViewResId, this)
    }

    var avatarProfile: DashPayProfile? = null
        set(value) {
            ProfilePictureDisplay.display(avatar, value)
        }

    val avatarView: ImageView
        get() = avatar
}