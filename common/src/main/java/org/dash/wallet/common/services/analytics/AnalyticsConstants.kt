/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.common.services.analytics

// Event names should be <= 40 chars
object AnalyticsConstants {
    const val CALLING_ACTIVITY = "calling_activity"

    object Liquid {
        const val BUY_SELL_MORE = "more_buy_sell_dash"
        const val ENTER_CONNECTED = "liquid_enter_connected"
        const val ENTER_DISCONNECTED = "liquid_enter_disconnected"
        const val DISCONNECT = "liquid_disconnect"
        const val SUPPORTED_COUNTRIES = "liquid_country_support_info"
        const val BUY_DASH = "liquid_buy_dash"
        const val BUY_CREDIT_CARD = "liquid_buy_with_credit_card"
        const val WIDGET_QUOTE_CLOSE = "liquid_quote_screen_close"
        const val WIDGET_QUOTE_BUY = "liquid_quote_screen_buy_with_visa"
        const val WIDGET_PROCESSING_DURATION = "liquid_processing_duration"
        const val WIDGET_PROCESSING_CLOSE_TOP_LEFT = "liquid_processing_close_top_left"
        const val WIDGET_PROCESSING_CLOSE_OVERLAY = "liquid_processing_close_overlay"
    }

    object Uphold {
        const val ENTER_CONNECTED = "uphold_enter_connected"
        const val ENTER_DISCONNECTED = "uphold_enter_disconnected"
        const val DISCONNECT = "uphold_disconnect"
        const val LINK_ACCOUNT = "uphold_link_account"
        const val TRANSFER_DASH = "uphold_transfer_dash"
        const val BUY_DASH = "uphold_buy_dash"
    }

    object Security {
        const val VIEW_RECOVERY_PHRASE = "security_view_recovery_phrase"
        const val CHANGE_PIN = "security_change_pin"
        const val FINGERPRINT_ON = "security_fingerprint_on"
        const val FINGERPRINT_OFF = "security_fingerprint_off"
        const val AUTOHIDE_BALANCE_ON = "security_autohide_balance_on"
        const val AUTOHIDE_BALANCE_OFF = "security_autohide_balance_off"
        const val ADVANCED_SECURITY = "security_advanced_security"
        const val RESET_WALLET = "security_reset_wallet"
        const val AUTO_LOGOUT_ON = "adv_security_auto_logout_on"
        const val AUTO_LOGOUT_OFF = "adv_security_auto_logout_off"
        const val AUTO_LOGOUT_TIMER_VALUE = "adv_security_auto_logout_timer_value"
        const val SPENDING_CONFIRMATION_ON = "adv_security_spending_confirmation_on"
        const val SPENDING_CONFIRMATION_OFF = "adv_security_spending_confirmation_off"
        const val SPENDING_CONFIRMATION_LIMIT = "adv_security_spending_confirmation_limit"
        const val RESET_TO_DEFAULT = "adv_security_reset_to_default"
    }

    object Settings {
        const val ADDRESS_BOOK = "tools_address_book"
        const val IMPORT_PRIVATE_KEY = "tools_import_private_key"
        const val NETWORK_MONITORING = "tools_network_monitoring"
        const val LOCAL_CURRENCY = "settings_local_currency"
        const val RESCAN_BLOCKCHAIN_RESET = "settings_rescan_blockchain_reset"
        const val RESCAN_BLOCKCHAIN_DISMISS = "settings_rescan_blockchain_dismiss"
        const val ABOUT = "settings_about"
        const val ABOUT_SUPPORT = "settings_about_contact_support"
    }

    object SendReceive {
        const val SCAN_TO_SEND = "send_scan_to_send"
        const val SEND_TO_ADDRESS = "send_to_address"
        const val SHOW_QR_CODE = "receive_show_qr_code"
        const val COPY_ADDRESS = "receive_copy_address"
        const val SPECIFY_AMOUNT = "receive_specify_amount"
        const val SHARE = "receive_tab_share"
        const val ENTER_AMOUNT_MAX = "enter_amount_max"
        const val ENTER_AMOUNT_DASH = "enter_amount_dash_amount"
        const val ENTER_AMOUNT_FIAT = "enter_amount_fiat_amount"
        const val ENTER_AMOUNT_SEND = "enter_amount_send"
        const val ENTER_AMOUNT_RECEIVE = "enter_amount_receive"
    }

    object Home {
        const val SHORTCUT_SECURE_WALLET = "shortcut_secure_wallet"
        const val SHORTCUT_SCAN_TO_PAY = "shortcut_scan_to_pay"
        const val SHORTCUT_SEND_TO_ADDRESS = "shortcut_send_to_address"
        const val SHORTCUT_RECEIVE = "shortcut_receive"
        const val SHORTCUT_BUY_AND_SELL = "shortcut_buy_and_sell_dash"
        const val HIDE_BALANCE = "home_hide_balance"
        const val SHOW_BALANCE = "home_show_balance"
        const val TRANSACTION_DETAILS = "home_transaction_details"
        const val TRANSACTION_FILTER = "home_transaction_filter"
        const val SEND_RECEIVE_BUTTON = "home_send_receive_button"
    }

    object ExploreDash {
        const val WHERE_TO_SPEND = "explore_portal_where_to_spend"
        const val PORTAL_ATM = "explore_portal_atm"
        const val LEARN_MORE = "explore_info_learn_more"
        const val CONTINUE = "explore_info_continue"
        const val ONLINE_MERCHANTS = "explore_online_merchants"
        const val NEARBY_MERCHANTS = "explore_nearby_merchants"
        const val ALL_MERCHANTS = "explore_all_merchants"
        const val FILTER_MERCHANTS_TOP = "explore_filter_merchants_top"
        const val FILTER_MERCHANTS_BOTTOM = "explore_filter_merchants_bottom"
        const val SELECT_MERCHANT_LOCATION = "explore_select_merchant_location"
        const val SELECT_MERCHANT_MARKER = "explore_select_merchant_marker"
        const val INFO_EXPLORE_MERCHANT = "explore_info_search"
        const val PAN_MERCHANT_MAP = "explore_pan_merchant_map"
        const val ZOOM_MERCHANT_MAP = "explore_zoom_merchant_map"

        const val ALL_ATM = "explore_all_atm"
        const val BUY_ATM = "explore_buy_atm"
        const val SELL_ATM = "explore_sell_atm"
        const val BUY_SELL_ATM = "explore_buy_sell_atm"
        const val FILTER_ATM_BOTTOM = "explore_filter_atm_bottom"
        const val FILTER_ATM_TOP = "explore_filter_atm_top"
        const val SELECT_ATM_MARKER = "explore_select_atm_marker"
        const val SELECT_ATM_LOCATION = "explore_select_atm_location"
        const val PAN_ATM_MAP = "explore_pan_atm_map"
        const val ZOOM_ATM_MAP = "explore_zoom_atm_map"

        const val FILTER_SELECT_DASH_ON = "explore_select_dash_on"
        const val FILTER_SELECT_DASH_OFF = "explore_select_dash_off"
        const val FILTER_GIFT_CARD_ON = "explore_select_card_on"
        const val FILTER_GIFT_CARD_OFF = "explore_select_card_off"
        const val FILTER_SORT_BY_NAME = "explore_sort_by_name"
        const val FILTER_SORT_BY_DISTANCE = "explore_sort_by_distance"
        const val FILTER_CURRENT_LOCATION = "explore_current_location"
        const val FILTER_SELECTED_LOCATION = "explore_selected_location"
        const val FILTER_ONE_MILE = "explore_one_mile"
        const val FILTER_FIVE_MILE = "explore_five_miles"
        const val FILTER_TWENTY_MILE = "explore_twenty_miles"
        const val FILTER_FIFTY_MILE = "explore_fifty_miles"
        const val FILTER_LOCATION_ALLOWED = "explore_location_allowed"
        const val FILTER_LOCATION_DENIED = "explore_location_denied"
        const val FILTER_APPLY_ACTION = "explore_apply_action"
        const val FILTER_CANCEL_ACTION = "explore_cancel_action"
    }
}