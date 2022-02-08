package org.dash.wallet.common.services

import androidx.fragment.app.FragmentActivity

interface SecurityModel {
    suspend fun requestPinCode(activity: FragmentActivity): String?
}