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

    enum class Parameter(val paramName: String) {
        // When adding a parameter, make sure to create a custom definition for it in Firebase Analytics.
        // Better yet, use one of the parameters already added here.
        VALUE("value"),
        TIME("time"),
        ARG1("arg1"),
        ARG2("arg2")
    }

    object Uphold {
        const val ENTER_CONNECTED = "uphold_enter_connected"
        const val ENTER_DISCONNECTED = "uphold_enter_disconnected"
        const val DISCONNECT = "uphold_disconnect"
        const val LINK_ACCOUNT = "uphold_link_account"
        const val TRANSFER_DASH = "uphold_transfer_dash"
        const val BUY_DASH = "uphold_buy_dash"
    }

    object Topper {
        const val ENTER_BUY_SELL = "buy_sell_portal_topper"
        const val ENTER_UPHOLD = "uphold_topper_buy_dash"
    }

    object MoreMenu {
        const val BUY_SELL = "more_buy_sell_dash"
        const val EXPLORE = "more_explore"
        const val SECURITY = "more_security"
        const val SETTINGS = "more_settings"
        const val TOOLS = "more_tools"
        const val CONTACT_SUPPORT = "more_contact_support"
        const val INVITE = "more_invite"
        const val USERNAME_VOTING = "more_username_voting"
        const val UPDATE_PROFILE = "more_user_updated_profile"
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
        const val RESCAN_BLOCKCHAIN_RESET = "settings_rescan_btn_rescan"
        const val RESCAN_BLOCKCHAIN_DISMISS = "settings_rescan"
        const val ABOUT = "settings_about"
        const val ABOUT_SUPPORT = "settings_about_contact_support"
        const val COINJOIN = "settings_coinjoin"
    }

    object Tools {
        const val EXPORT_CSV = "tools_export_csv"
        const val ZENLEDGER = "tools_export_zenledger"
    }

    object SendReceive {
        const val SEND_TX = "send_tx" // also include amount sent
        const val SEND_TX_CONTACT = "send_tx_to_contact"  // also include amount sent
        const val SCAN_TO_SEND = "send_scan_to_send"
        const val SEND_TO_ADDRESS = "send_to_address"
        const val SHOW_QR_CODE = "receive_show_qr_code"
        const val COPY_ADDRESS = "receive_copy_address"
        const val SPECIFY_AMOUNT = "receive_specify_amount"
        const val SHARE = "receive_tab_share"
        const val IMPORT_PRIVATE_KEY = "receive_btn_import_private_key"
        const val IMPORT_PRIVATE_KEY_SUCCESS = "receive_btn_import_private_key_success"
        const val ENTER_AMOUNT_MAX = "enter_amount_max"
        const val ENTER_AMOUNT_DASH = "enter_amount_dash_amount"
        const val ENTER_AMOUNT_FIAT = "enter_amount_fiat_amount"
        const val ENTER_AMOUNT_SEND = "enter_amount_send"
        const val ENTER_AMOUNT_RECEIVE = "enter_amount_receive"
        const val ENTER_AMOUNT_SHOW_BALANCE = "enter_amount_show_balance"
        const val ENTER_AMOUNT_HIDE_BALANCE = "enter_amount_hide_balance"
        const val SEND_SUCCESS = "send_address_success"
        const val SEND_ERROR = "send_address_error"
        const val SEND_USERNAME_SUCCESS = "send_username_success"
        const val SEND_USERNAME_ERROR = "send_username_error"
    }

    object AddressInput {
        const val SCAN_QR = "send_inner_scan_qr"
        const val SHOW_CLIPBOARD = "send_show_content"
        const val ADDRESS_TAP = "send_show_content_tap_address"
        const val CONTINUE = "send_b_continue"
    }

    object Home {
        const val NAV_HOME = "bottom_nav_home"
        const val NAV_MORE = "bottom_nav_more"
        const val NAV_CONTACTS = "bottom_nav_contacts"
        const val NAV_EXPLORE = "bottom_nav_explore"
        const val SHORTCUT_SECURE_WALLET = "shortcut_secure_wallet"
        const val SHORTCUT_SCAN_TO_PAY = "shortcut_scan_to_pay"
        const val SHORTCUT_SEND_TO_ADDRESS = "shortcut_send_to_address"
        const val SHORTCUT_RECEIVE = "shortcut_receive"
        const val SHORTCUT_SEND = "shortcut_send"
        const val SHORTCUT_BUY_AND_SELL = "shortcut_buy_and_sell_dash"
        const val SHORTCUT_EXPLORE = "shortcut_explore"
        const val HIDE_BALANCE = "home_hide_balance"
        const val SHOW_BALANCE = "home_show_balance"
        const val TRANSACTION_DETAILS = "home_transaction_details"
        const val TRANSACTION_FILTER = "home_transaction_filter"
        const val SEND_RECEIVE_BUTTON = "bottom_nav_payments"
        const val AVATAR = "home_avatar"
        const val NOTIFICATIONS = "home_notifications"
    }

    object Invites {
        const val ERROR_USERNAME_TAKEN = "invite_username_already_found"
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

    object UsersContacts {
        const val CREATE_USERNAME = "create_username"
        const val CREATE_USERNAME_INSTANT = "create_username_instant"
        const val JOIN_DASHPAY = "start_btn_join_dashpay"
        const val CREATE_USERNAME_CONFIRM = "start_username_btn_confirm"
        const val CREATE_USERNAME_SUCCESS = "start_username_created_success"
        const val CREATE_USERNAME_INVITE_SUCCESS = "start_username_created_success_from_invitation"
        const val CREATE_USERNAME_ERROR = "start_username_created_fail"
        const val CREATE_USERNAME_TRYAGAIN = "start_username_btn_try_again"
        const val SEARCH_CONTACTS = "contacts_search"
        const val SEARCH_DASH_NETWORK = "contacts_search_dash_network"
        const val SEARCH_USER_ICON = "contacts_btn_add_contact"
        const val SEND_REQUEST = "contacts_request_sent"
        const val ACCEPT_REQUEST = "contacts_request_accepted"
        const val NOTIFICATIONS_HOME_SCREEN = "notifications_home_screen"
        const val NOTIFICATIONS_CONTACT_DETAILS = "notifications_contact_details"
        const val PROFILE_EDIT_MORE = "profile_edit_from_more"
        const val PROFILE_EDIT_HOME = "profile_edit_from_home"
        const val PROFILE_CHANGE_NAME = "profile_change_display_name"
        const val PROFILE_NAME_LENGTH = "profile_display_name_length"
        const val PROFILE_CHANGE_ABOUT_ME = "profile_change_about_me"
        const val PROFILE_ABOUT_ME_LENGTH = "profile_about_me_length"
        const val PROFILE_CHANGE_PICTURE = "profile_change_picture"
        const val PROFILE_CHANGE_PICTURE_GRAVATAR = "profile_change_picture_gravatar"
        const val PROFILE_CHANGE_PICTURE_PUBLIC_URL = "profile_change_picture_public_url"
        const val PROFILE_CHANGE_PICTURE_CAMERA = "profile_change_picture_camera_photo"
        const val PROFILE_CHANGE_PICTURE_GALLERY = "profile_change_picture_gallery"
        const val TAB_SEND_TO_CONTACT = "send_tab_send_to_contact"
        const val SHORTCUT_SEND_TO_CONTACT = "shortcut_send_to_contact"
        const val INVITE_CONTACTS = "contacts_btn_invite"
        const val INVITE_CONTACTS_CREATE = "contacts_btn_invite_btn_create"
        const val INVITE_CONTACTS_CREATE_PAY = "contacts_btn_invite_btn_create_btn_pay"
        const val INVITE_CONTACTS_CREATE_SUCCESS = "contacts_invitation_created_success"
        const val INVITE_CONTACTS_CREATE_FAIL = "contacts_invitation_created_fail"
    }

    object Process {
        const val PROCESS_USERNAME_CREATE_STEP_1 = "process_username_create_1"
        const val PROCESS_USERNAME_CREATE_STEP_2 = "process_username_create_2"
        const val PROCESS_USERNAME_CREATE_STEP_3 = "process_username_create_3"
        const val PROCESS_USERNAME_CREATE = "process_username_create"
        const val PROCESS_USERNAME_CREATE_ISLOCK = "process_username_create_islock"
        const val PROCESS_USERNAME_IDENTITY_CREATE = "process_username_identity_create"
        const val PROCESS_USERNAME_PREORDER_CREATE = "process_username_preorder_create"
        const val PROCESS_USERNAME_DOMAIN_CREATE = "process_username_domain_create"
        const val PROCESS_INVITATION_CLAIM = "process_invitation_claim"
        const val PROCESS_USERNAME_SEARCH_QUERY = "process_username_search_query"
        const val PROCESS_USERNAME_SEARCH_UI = "process_username_search_ui"
        const val PROCESS_CONTACT_REQUEST_SEND = "process_contact_request_send"
        const val PROCESS_CONTACT_REQUEST_RECEIVE = "process_contact_request_receive"
        const val PROCESS_PROFILE_CREATE = "process_profile_create"
        const val PROCESS_PROFILE_UPDATE = "process_profile_update"
        const val PROCESS_BIP7O_GET_PAYMENT_REQUEST = "process_bip70_get_payment_request"
        const val PROCESS_BIP7O_SEND_PAYMENT = "process_bip70_send_payment"
        const val PROCESS_GIFT_CARD_PURCHASE = "process_gift_card_purchase"
    }

    object Explore {
        const val ONLINE_MERCHANTS = "explore_online_merchants"
        const val NEARBY_MERCHANTS = "explore_nearby_merchants"
        const val ALL_MERCHANTS = "explore_all_merchants"
        const val FILTER_MERCHANTS_TOP = "explore_filter_merchants_top"

        const val ALL_ATM = "explore_all_atm"
        const val BUY_ATM = "explore_buy_atm"
        const val SELL_ATM = "explore_sell_atm"
        const val BUY_SELL_ATM = "explore_buy_sell_atm"
        const val FILTER_ATM_TOP = "explore_filter_atm_top"
        const val SELECT_ATM_LOCATION = "explore_select_atm_location"

        const val FILTER_MERCHANT_SELECT_DASH = "explore_filter_merch_select_dash"
        const val FILTER_MERCHANT_SELECT_GIFT_CARD = "explore_filter_merch_select_gift_card"
        const val FILTER_MERCHANT_SORT_BY_NAME = "explore_filter_merch_sort_by_name"
        const val FILTER_MERCHANT_SORT_BY_DISTANCE = "explore_filter_merch_sort_by_distance"
        const val FILTER_MERCHANT_SORT_BY_DISCOUNT = "explore_filter_merch_sort_by_discount"
        const val FILTER_MERCHANT_CURRENT_LOCATION = "explore_filter_merch_current_location"
        const val FILTER_MERCHANT_SELECTED_LOCATION = "explore_filter_merch_selected_location"
        const val FILTER_MERCHANT_ONE_MILE = "explore_filter_merch_one_mile"
        const val FILTER_MERCHANT_FIVE_MILE = "explore_filter_merch_five_miles"
        const val FILTER_MERCHANT_TWENTY_MILE = "explore_filter_merch_twenty_miles"
        const val FILTER_MERCHANT_FIFTY_MILE = "explore_filter_merch_fifty_miles"
        const val FILTER_MERCHANT_LOCATION_ALLOWED = "explore_filter_merch_location_allowed"
        const val FILTER_MERCHANT_LOCATION_DENIED = "explore_filter_merch_location_denied"
        const val FILTER_MERCHANT_APPLY_ACTION = "explore_filter_merch_apply_action"

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

        const val MERCHANT_DETAILS_DIAL_PHONE_CALL = "explore_merchant_details_dial_phone"
        const val MERCHANT_DETAILS_OPEN_WEBSITE = "explore_merchant_details_open_website"
        const val MERCHANT_DETAILS_BUY_GIFT_CARD = "explore_merchant_details_buy_gift_card"
        const val MERCHANT_DETAILS_PAY_WITH_DASH = "explore_merchant_details_pay_with_dash"

        const val PAY_WITH_DASH_SUCCESS = "explore_merchant_pay_with_dash_success"
        const val PAY_WITH_DASH_ERROR = "explore_merchant_pay_with_dash_error"
    }

    object DashSpend {
        const val CREATE_ACCOUNT = "dashspend__btn_create_new_account"
        const val LOGIN = "dashspend__btn_login"
        const val SUCCESSFUL_LOGIN = "dashspend__success_login"
        const val UNSUCCESSFUL_LOGIN = "dashspend__not_success_login"
        const val SUCCESSFUL_PURCHASE = "dashspend__success_purchase"
        const val PURCHASE_AMOUNT = "dashspend__purchase_amount"
        const val DISCOUNT_AMOUNT = "dashspend__discount_amount"
        const val MERCHANT_NAME = "dashspend__merchant_name"
        const val HOW_TO_USE = "dashspend__btn_how_to_use"
        const val FILTER_GIFT_CARD = "home_transaction_filter_gift_card"
        const val DETAILS_GIFT_CARD = "home_transaction_details_gift_card"
    }

    object CrowdNode {
        const val STAKING_ENTRY = "explore__staking"

        const val WELCOME_DIALOG_CONTINUE = "staking_cn__welcome_modal__b_continue"
        const val CREATE_NEW_ACCOUNT = "staking_cn__b_create_acc"
        const val CREATE_ACCOUNT_BUTTON = "staking_cn__new_acc__b_create_acc"
        const val CREATE_ACCOUNT_ERROR_RETRY = "staking_cn__new_acc__dialogue_b_retry"
        const val CREATE_ACCOUNT_SUCCESS = "staking_cn__new_account_created"

        const val LINK_EXISTING = "staking_cn__b_link_acc"
        const val LINK_EXISTING_LOGIN_BUTTON = "staking_cn__link_acc__b_login"

        const val PORTAL_DEPOSIT = "staking_cn__b_deposit"
        const val PORTAL_DEPOSIT_SUCCESS = "staking_cn_deposit_success"
        const val PORTAL_DEPOSIT_ERROR = "staking_cn_deposit_error"
        const val PORTAL_WITHDRAW = "staking_cn__b_withdraw"
        const val PORTAL_WITHDRAW_CANCEL = "staking_cn__withdraw__m_b_cancel"
        const val PORTAL_WITHDRAW_SUCCESS = "staking_cn_withdraw_success"
        const val PORTAL_WITHDRAW_ERROR = "staking_cn_withdraw_error"
        const val PORTAL_WITHDRAW_BUY = "staking_cn__withdraw__m_b_buy"
        const val PORTAL_CREATE_ONLINE_ACCOUNT = "staking_cn__b_create_online_acc"
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
        const val NEW_CONNECTION = "coinbase_new_connection"
        const val DISCONNECT = "coinbase_disconnect"

        const val BUY_CREATE_ACCOUNT = "coinbase_buy_b_create_dash_acc"
        const val BUY_ADD_PAYMENT_METHOD = "coinbase_buy_b_add_payment_method"
        const val BUY_ENTER_FIAT = "coinbase_buy_enter_amount_fiat"
        const val BUY_ENTER_DASH = "coinbase_buy_enter_amount_dash"
        const val BUY_CONTINUE = "coinbase_buy_b_continue"
        const val BUY_AUTH_LIMIT = "coinbase_buy_b_auth_limit"
        const val BUY_QUOTE_FEE_INFO = "coinbase_buy_quote_b_fee_info"
        const val BUY_QUOTE_RETRY = "coinbase_buy_quote_b_retry" // TODO: no retry in the Buy Quote screen

        const val BUY_SUCCESS_CLOSE = "coinbase_buy_success"
        const val BUY_ERROR_RETRY = "coinbase_buy_error_b_retry"
        const val BUY_ERROR_CLOSE = "coinbase_buy_error_b_close"
        const val BUY_ERROR = "coinbase_buy_error"

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

        const val CONVERT_SUCCESS_CLOSE = "coinbase_convert_success"
        const val CONVERT_ERROR_RETRY = "coinbase_convert_error_b_retry"
        const val CONVERT_ERROR_CLOSE = "coinbase_convert_error_b_close"

        const val TRANSFER_CONTINUE = "coinbase_transfer_dash_btn_transfer"
        const val TRANSFER_ENTER_DASH = "coinbase_transfer_enter_amount_dash"
        const val TRANSFER_ENTER_FIAT = "coinbase_transfer_enter_amount_fiat"

        const val TRANSFER_SUCCESS = "coinbase_transfer_success"
        const val TRANSFER_SUCCESS_CLOSE = "coinbase_transfer_success_b_close"
        const val TRANSFER_ERROR = "coinbase_transfer_error"
        const val TRANSFER_ERROR_RETRY = "coinbase_transfer_error_b_retry"
        const val TRANSFER_ERROR_CLOSE = "coinbase_transfer_error_b_close"

        const val QUOTE_CONFIRM = "coinbase_buy_quote_b_confirm"
    }

    object CoinJoinPrivacy {
        const val COINJOIN_START_MIXING = "settings_coinjoin_btn_start_mixing"
        const val COINJOIN_STOP_MIXING = "settings_coinjoin_btn_stop_mixing"
        const val COINJOIN_MIXING_SUCCESS = "settings_coinjoin_mixed_success"
        const val COINJOIN_MIXING_FAIL = "settings_coinjoin_mixed_fail"
        const val USERNAME_PRIVACY_BTN_CONTINUE = "username_privacy_btn_continue"
        const val USERNAME_PRIVACY_WIFI_BTN_CONTINUE = "username_privacy_wifi_btn_continue"
        const val USERNAME_PRIVACY_WIFI_BTN_CANCEL = "username_privacy_wifi_btn_cancel"
        const val USERNAME_PRIVACY_CONFIRMATION_BTN_CONFIRM = "username_privacy_confirm_btn_confirm"
        const val USERNAME_PRIVACY_CONFIRMATION_BTN_CANCEL = "username_privacy_confirm_btn_cancel"
    }

    object UsernameVoting {
        const val BLOCK = "username_voting_btn_block"
        const val DETAILS = "username_voting_details_open"
        const val VOTE = "username_voting_details_btn_vote"
        const val VOTE_SUCCESS = "username_voting_details_btn_vote_success"
        const val VOTE_ERROR = "username_voting_details_btn_vote_fail"
        const val VOTE_CANCEL = "username_voting_details_btn_vote_cancel"
    }

    object Onboarding {
        const val SKIP = "start_btn_skip"
        const val GET_STARTED = "start_btn_get_started"
        const val NEW_WALLET = "start_btn_create_new_wallet"
        const val NEW_WALLET_SUCCESS = "start_btn_create_new_wallet_success"
        const val RECOVERY = "start_btn_restore_recovery_phrase"
        const val RECOVERY_SUCCESS = "start_btn_recovery_phrase_success"
        const val RESTORE_FROM_FILE = "start_btn_restore_file"
        const val RESTORE_FROM_FILE_SUCCESS = "start_btn_restore_file_finish_success"
    }

    object LockScreen {
        const val QUICK_RECEIVE = "locked_screen_btn_quick_receive"
        const val QUICK_RECEIVE_SUCCESS = "locked_screen_btn_quick_receive_success" // TODO: need to track received coins; moved to a separate story
        const val QUICK_RECEIVE_AMOUNT = "locked_screen_btb_quick_receive_amount"
        const val SCAN_TO_SEND = "locked_screen_btn_scan_to_send"
        const val SCAN_TO_SEND_SEND = "locked_screen_btn_scan_to_send_btn_send"
        const val SCAN_TO_SEND_SUCCESS = "locked_screen_btn_scan_to_send_success"
    }

    object BlockchainExplorer {
        const val INSIGHT_PICKED = "blockchain_explorer_insight_picked"
        const val BLOCKCHAIR_PICKED = "blockchain_explorer_blockchair_picked"
    }
}
