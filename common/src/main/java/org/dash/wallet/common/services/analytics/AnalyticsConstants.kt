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
        const val WHERE_TO_SPEND = "explore__where_to_spend"
        const val PORTAL_ATM = "explore__atms"
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

        const val FILTER_MERCHANT_SELECT_DASH = "explore_filter_merch_select_dash"
        const val FILTER_MERCHANT_SELECT_GIFT_CARD = "explore_filter_merch_select_gift_card"
        const val FILTER_MERCHANT_SORT_BY_NAME = "explore_filter_merch_sort_by_name"
        const val FILTER_MERCHANT_SORT_BY_DISTANCE = "explore_filter_merch_sort_by_distance"
        const val FILTER_MERCHANT_CURRENT_LOCATION = "explore_filter_merch_current_location"
        const val FILTER_MERCHANT_SELECTED_LOCATION = "explore_filter_merch_selected_location"
        const val FILTER_MERCHANT_ONE_MILE = "explore_filter_merch_one_mile"
        const val FILTER_MERCHANT_FIVE_MILE = "explore_filter_merch_five_miles"
        const val FILTER_MERCHANT_TWENTY_MILE = "explore_filter_merch_twenty_miles"
        const val FILTER_MERCHANT_FIFTY_MILE = "explore_filter_merch_fifty_miles"
        const val FILTER_MERCHANT_LOCATION_ALLOWED = "explore_filter_merch_location_allowed"
        const val FILTER_MERCHANT_LOCATION_DENIED = "explore_filter_merch_location_denied"
        const val FILTER_MERCHANT_APPLY_ACTION = "explore_filter_merch_apply_action"
        const val FILTER_MERCHANT_CANCEL_ACTION = "explore_filter_merch_cancel_action"
        const val FILTER_MERCHANT_SWIPE_ACTION = "explore_filter_merch_swipe_action"

        const val FILTER_ATM_SELECT_DASH = "explore_filter_atm_select_dash"
        const val FILTER_ATM_SELECT_GIFT_CARD = "explore_filter_atm_select_gift_card"
        const val FILTER_ATM_SORT_BY_NAME = "explore_filter_atm_sort_by_name"
        const val FILTER_ATM_SORT_BY_DISTANCE = "explore_filter_atm_sort_by_distance"
        const val FILTER_ATM_CURRENT_LOCATION = "explore_filter_atm_current_location"
        const val FILTER_ATM_SELECTED_LOCATION = "explore_filter_atm_selected_location"
        const val FILTER_ATM_ONE_MILE = "explore_filter_atm_one_mile"
        const val FILTER_ATM_FIVE_MILE = "explore_filter_atm_five_miles"
        const val FILTER_ATM_TWENTY_MILE = "explore_filter_atm_twenty_miles"
        const val FILTER_ATM_FIFTY_MILE = "explore_filter_atm_fifty_miles"
        const val FILTER_ATM_LOCATION_ALLOWED = "explore_filter_atm_location_allowed"
        const val FILTER_ATM_LOCATION_DENIED = "explore_filter_atm_location_denied"
        const val FILTER_ATM_APPLY_ACTION = "explore_filter_atm_apply_action"
        const val FILTER_ATM_CANCEL_ACTION = "explore_filter_atm_cancel_action"
        const val FILTER_ATM_SWIPE_ACTION = "explore_filter_atm_swipe_action"

        const val MERCHANT_DETAILS_SHOW_ALL_LOCATIONS = "explore_merchant_details_go_to_all"
        const val MERCHANT_DETAILS_NAVIGATION = "explore_merchant_details_navigation"
        const val MERCHANT_DETAILS_DIAL_PHONE_CALL = "explore_merchant_details_dial_phone"
        const val MERCHANT_DETAILS_OPEN_WEBSITE = "explore_merchant_details_open_website"
        const val MERCHANT_DETAILS_BUY_GIFT_CARD = "explore_merchant_details_buy_gift_card"
        const val MERCHANT_DETAILS_BACK_FROM_ALL_LOCATIONS = "explore_merchant_details_back_from_all"
        const val MERCHANT_DETAILS_BACK_TOP = "explore_merchant_details_back_top"
        const val MERCHANT_DETAILS_BACK_BOTTOM = "explore_merchant_details_back_bottom"
        const val MERCHANT_DETAILS_PAY_WITH_DASH = "explore_merchant_details_pay_with_dash"
        const val MERCHANT_DETAILS_SCROLL_UP = "explore_merchant_details_scroll_up"
    }

    object CrowdNode {
        const val STAKING_ENTRY = "explore__staking"
        const val WELCOME_DIALOG_CONTINUE = "staking__welcome_modal__b_continue"
        const val CREATE_NEW_ACCOUNT = "staking__b_create_acc"
        const val CREATE_ACCOUNT_BUTTON = "staking__new_acc__b_create_acc"
        const val NOTIFY_WHEN_CREATED = "staking__new_acc__b_close_notify"
        const val CREATE_ACCOUNT_ERROR_RETRY = "staking__new_acc__dialogue_b_retry"
        const val CREATE_ACCOUNT_ERROR_CLOSE = "staking__new_acc__dialogue_b_close"

        const val LINK_EXISTING = "staking__b_link_acc"
        const val LINK_EXISTING_LOGIN_BUTTON = "staking__link_acc__b_login"
        const val LINK_EXISTING_HOW_TO_CONFIRM = "staking__link_acc__b_how_confirm_api_dash"
        const val LINK_EXISTING_SHOW_QR = "staking__link_acc__b_show_qr"
        const val LINK_EXISTING_SHARE_BUTTON = "staking__link_acc__b_share"

        const val PORTAL_DEPOSIT = "staking__crowdnode__b_deposit"
        const val PORTAL_WITHDRAW = "staking__crowdnode__b_withdraw"
        const val PORTAL_CREATE_ONLINE_ACCOUNT = "staking__crowdnode__b_create_online_acc"
        const val PORTAL_INFO_BUTTON = "staking__crowdnode__b_info"
        const val PORTAL_VERIFY = "staking__crowdnode__b_verify"

        const val CREATE_ONLINE_CONTINUE = "staking__crowdnode__online__b_continue"

        const val LOW_BALANCE_PROCEED = "crowdnode__dialogue_low_balance_proceed"
        const val LOW_BALANCE_CANCEL = "crowdnode__dialogue_low_balance_cancel"
    }
}