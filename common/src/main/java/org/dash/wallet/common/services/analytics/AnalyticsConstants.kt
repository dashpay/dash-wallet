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

package org.dash.wallet.common.services.analytics

object AnalyticsConstants {
    const val CALLING_ACTIVITY = "calling_activity"

    object Invites {
        const val ERROR_USERNAME_TAKEN = "invite_username_already_found"
        const val INVITE_CONTACTS = "invite_from_contacts"
        const val CREATE_MORE = "invite_create_from_more_menu"
        const val INVITE_FRIEND = "invite_friend"
        const val ERROR_CREATE = "invite_error_creating"
        const val ERROR_ALREADY_CLAIMED = "invite_already_claimed"
        const val ERROR_INVALID = "invite_invalid"
        const val ERROR_INSUFFICIENT_FUNDS = "invite_insufficient_funds"
        const val CREATED_COPY_LINK = "invite_created_copy_link"
        const val DETAILS_COPY_LINK = "invite_details_copy_link"
        const val CREATED_SEND = "invite_created_send"
        const val DETAILS_SEND_AGAIN = "invite_details_send_again"
        const val CREATED_LATER = "invite_created_maybe_later"
        const val CREATED_TAG = "invite_created_send_with_tag"
        const val DETAILS_TAG = "invite_details_send_again_with_tag"
        const val CREATED_PREVIEW = "invite_created_preview"
        const val DETAILS_PREVIEW = "invite_details_preview"
        const val CREATE_HISTORY = "invite_create_from_history"
        const val HISTORY_FILTER = "invite_history_filter"
        const val DETAILS = "invite_details"
        const val NEW_WALLET = "invite_new_wallet"
    }

    object Liquid {
        const val DISCONNECT = "liquid_disconnect"
        const val SUPPORTED_COUNTRIES = "liquid_see_supported_countries"
        const val BUY_DASH = "liquid_buy_dash"
        const val BUY_CREDIT_CARD = "liquid_buy_with_credit_card"
        const val BUY_SELL_HOME = "liquid_buy_sell_home_screen"
        const val BUY_SELL_MORE = "liquid_buy_sell_more_screen"
        const val ENTER_CONNECTED = "liquid_enter_connected"
        const val ENTER_DISCONNECTED = "liquid_enter_disconnected"
        const val WIDGET_QUOTE_CLOSE = "liquid_quote_screen_close"
        const val WIDGET_QUOTE_VISA = "liquid_quote_screen_buy_with_visa"
    }
}