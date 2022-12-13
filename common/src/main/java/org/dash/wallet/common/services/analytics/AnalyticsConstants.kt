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
    object Parameters {
        const val VALUE = "value"
    }

    object Uphold {
        const val ENTER_CONNECTED = "uphold_enter_connected"
        const val ENTER_DISCONNECTED = "uphold_enter_disconnected"
        const val DISCONNECT = "uphold_disconnect"
        const val LINK_ACCOUNT = "uphold_link_account"
        const val TRANSFER_DASH = "uphold_transfer_dash"
        const val BUY_DASH = "uphold_buy_dash"
    }

    object MoreMenu {
        const val BUY_SELL_MORE = "more_buy_sell_dash"
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
        const val ENTER_AMOUNT_SHOW_BALANCE = "enter_amount_show_balance"
        const val ENTER_AMOUNT_HIDE_BALANCE = "enter_amount_hide_balance"
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

    object Explore {
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

        const val WELCOME_DIALOG_CONTINUE = "staking_cn__welcome_modal__b_continue"
        const val CREATE_NEW_ACCOUNT = "staking_cn__b_create_acc"
        const val CREATE_ACCOUNT_BUTTON = "staking_cn__new_acc__b_create_acc"
        const val NOTIFY_WHEN_CREATED = "staking_cn__new_acc__b_close_notify"
        const val CREATE_ACCOUNT_ERROR_RETRY = "staking_cn__new_acc__dialogue_b_retry"
        const val CREATE_ACCOUNT_ERROR_CLOSE = "staking_cn__new_acc__dialogue_b_close"

        const val LINK_EXISTING = "staking_cn__b_link_acc"
        const val LINK_EXISTING_LOGIN_BUTTON = "staking_cn__link_acc__b_login"
        const val LINK_EXISTING_HOW_TO_CONFIRM = "staking_cn__link_acc__b_how_confirm_api"
        const val LINK_EXISTING_SHOW_QR = "staking_cn__link_acc__b_show_qr"
        const val LINK_EXISTING_SHARE_BUTTON = "staking_cn__link_acc__b_share"

        const val PORTAL_DEPOSIT = "staking_cn__b_deposit"
        const val PORTAL_WITHDRAW = "staking_cn__b_withdraw"
        const val PORTAL_WITHDRAW_CANCEL = "staking_cn__withdraw__m_b_cancel"
        const val PORTAL_WITHDRAW_BUY = "staking_cn__withdraw__m_b_buy"
        const val PORTAL_CREATE_ONLINE_ACCOUNT = "staking_cn__b_create_online_acc"
        const val PORTAL_INFO_BUTTON = "staking_cn__b_info"
        const val PORTAL_VERIFY = "staking_cn__b_verify"

        const val CREATE_ONLINE_CONTINUE = "staking_cn__online__b_continue"
        const val WITHDRAWAL_REQUESTED = "staking_cn__b_withdraw_final"
        const val DEPOSIT_REQUESTED = "staking_cn__b_deposit_final"

        const val LOW_BALANCE_PROCEED = "staking_cn__dialogue_low_balance_proceed"
        const val LOW_BALANCE_CANCEL = "staking_cn__dialogue_low_balance_cancel"
    }

    object Coinbase {
        const val ENTER_CONNECTED = "coinbase_enter_connected"
        const val ENTER_DISCONNECTED = "coinbase_enter_disconnected"

        const val BUY_DASH = "coinbase_buy_dash"
        const val CONVERT_DASH = "coinbase_convert_dash"
        const val TRANSFER_DASH = "coinbase_transfer_dash"
        const val DISCONNECT = "coinbase_disconnect"

        const val BUY_CREATE_ACCOUNT = "coinbase_buy_b_create_dash_acc"
        const val BUY_ADD_PAYMENT_METHOD = "coinbase_buy_b_add_payment_method"
        const val BUY_CHANGE_PAYMENT_METHOD = "coinbase_buy_b_change_p_method"
        const val BUY_PAYMENT_METHOD = "coinbase_buy_p_method"
        const val BUY_ENTER_FIAT = "coinbase_buy_enter_amount_fiat"
        const val BUY_ENTER_DASH = "coinbase_buy_enter_amount_dash"
        const val BUY_CHANGE_FIAT_CURRENCY = "coinbase_buy_b_change_fiat_currency" // Currency selector isn't shown in Coinbase.
        const val BUY_CONTINUE = "coinbase_buy_b_continue"
        const val BUY_AUTH_LIMIT = "coinbase_buy_b_auth_limit"

        const val BUY_QUOTE_TOP_BACK = "coinbase_buy_quote_b_back"
        const val BUY_QUOTE_ANDROID_BACK = "coinbase_buy_quote_b_back_android"
        const val BUY_QUOTE_CANCEL = "coinbase_buy_quote_b_cancel"
        const val BUY_QUOTE_CANCEL_NO = "coinbase_buy_quote_modal_b_no"
        const val BUY_QUOTE_CANCEL_YES = "coinbase_buy_quote_modal_b_yes"
        const val BUY_QUOTE_CONFIRM = "coinbase_buy_quote_b_confirm"
        const val BUY_QUOTE_RETRY = "coinbase_buy_quote_b_retry"
        const val BUY_QUOTE_FEE_INFO = "coinbase_buy_quote_b_fee_info"

        const val BUY_SUCCESS_CLOSE = "coinbase_buy_success_b_close"
        const val BUY_ERROR_RETRY = "coinbase_buy_error_b_retry"
        const val BUY_ERROR_CLOSE = "coinbase_buy_error_b_close"

        // ----------------- TODO: NMA-1209
        const val SELL_DASH = "coinbase_sell_dash"
        const val SELL_CREATE_ACCOUNT = "coinbase_sell_b_create_dash_acc"
        const val SELL_ADD_PAYMENT_METHOD = "coinbase_sell_b_add_p_method"
        const val SELL_MAX = "coinbase_sell_b_max"
        const val SELL_CONTINUE = "coinbase_sell_b_get_quote"
        const val SELL_ENTER_AMOUNT_FIAT = "coinbase_sell_enter_amount_fiat"
        const val SELL_ENTER_AMOUNT_DASH = "coinbase_sell_enter_amount_dash"

        const val SELL_QUOTE_TOP_BACK = "coinbase_sell_b_back"
        const val SELL_QUOTE_ANDROID_BACK = "coinbase_sell_b_back_android"
        const val SELL_QUOTE_CANCEL = "coinbase_sell_preview_b_cancel"
        const val SELL_QUOTE_CANCEL_NO = "coinbase_sell_preview_modal_b_no"
        const val SELL_QUOTE_CANCEL_YES = "coinbase_sell_preview_modal_b_yes"
        const val SELL_QUOTE_CONFIRM = "coinbase_sell_preview_b_confirm"
        const val SELL_QUOTE_RETRY = "coinbase_sell_preview_b_retry"
        const val SELL_QUOTE_FEE_INFO = "coinbase_sell_preview_b_fee_info"

        const val SELL_ERROR_RETRY = "coinbase_sell_error_b_retry"
        const val SELL_ERROR_CLOSE = "coinbase_sell_error_b_close"
        const val SELL_SUCCESS_CLOSE = "coinbase_sell_success_b_close"
        // ------------------

        const val CONVERT_SELECT_COIN = "coinbase_convert_b_select_coin"
        const val CONVERT_BUY_ON_COINBASE = "coinbase_convert_b_buy_on_coinbase"
        const val CONVERT_CONTINUE = "coinbase_convert_b_get_quote"
        const val CONVERT_ENTER_DASH = "coinbase_convert_enter_amount_dash"
        const val CONVERT_ENTER_CRYPTO = "coinbase_convert_enter_amount_crypto"
        const val CONVERT_ENTER_FIAT = "coinbase_convert_enter_amount_fiat"

        const val CONVERT_QUOTE_TOP_BACK = "coinbase_convert_preview_b_back"
        const val CONVERT_QUOTE_ANDROID_BACK = "coinbase_convert_preview_b_back_android"
        const val CONVERT_QUOTE_CONFIRM = "coinbase_convert_preview_b_confirm"
        const val CONVERT_QUOTE_CANCEL = "coinbase_convert_preview_b_cancel"
        const val CONVERT_QUOTE_CANCEL_YES = "coinbase_convert_prev_modal_b_yes"
        const val CONVERT_QUOTE_CANCEL_NO = "coinbase_convert_prev_modal_b_no"
        const val CONVERT_QUOTE_RETRY = "coinbase_convert_preview_b_retry"
        const val CONVERT_QUOTE_FEE_INFO = "coinbase_convert_preview_b_fee_info"

        const val CONVERT_SUCCESS_CLOSE = "coinbase_convert_success_b_close"
        const val CONVERT_ERROR_RETRY = "coinbase_convert_error_b_retry"
        const val CONVERT_ERROR_CLOSE = "coinbase_convert_error_b_close"

        const val TRANSFER_CONTINUE = "coinbase_transfer_b_transfer"
        const val TRANSFER_ENTER_DASH = "coinbase_transfer_enter_amount_dash"
        const val TRANSFER_ENTER_FIAT = "coinbase_transfer_enter_amount_fiat"
        const val TRANSFER_AUTH_LIMIT = "coinbase_transfer_b_auth_balance"

        const val TRANSFER_SUCCESS_CLOSE = "coinbase_transfer_success_b_close"
        const val TRANSFER_ERROR_RETRY = "coinbase_transfer_error_b_retry"
        const val TRANSFER_ERROR_CLOSE = "coinbase_transfer_error_b_close"
    }
}