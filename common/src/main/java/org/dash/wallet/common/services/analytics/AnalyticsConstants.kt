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
        const val WIDGET_QUOTE_BUY = "liquid_quote_screen_buy_with_visa"
        // TODO
        const val WIDGET_PROCESSING_DURATION = "liquid_processing_duration"
        const val WIDGET_PROCESSING_CLOSE_OVERLAY = "liquid_processing_close_overlay"
        const val WIDGET_PROCESSING_CLOSE_TOP_LEFT = "liquid_processing_close_top_left"
    }
}