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

// Event names should be <= 40 chars
object AnalyticsConstants {
    const val CALLING_ACTIVITY = "calling_activity"

    object Liquid {
        const val BUY_SELL_HOME = "liquid_buy_sell_home_screen"
        const val BUY_SELL_MORE = "liquid_buy_sell_more_screen"
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
        // TODO NMA-1014
        const val WIDGET_PROCESSING_CLOSE_OVERLAY = "liquid_processing_close_overlay"
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

    object UsersContacts {
        const val SEARCH_CONTACTS = "contacts_search"
        const val SEARCH_DASH_NETWORK = "contacts_search_dash_network"
        const val SEARCH_USER_ICON = "contacts_search_user_icon"
        const val SEND_REQUEST = "contacts_send_request"
        const val ACCEPT_REQUEST = "contacts_accept_request"
        const val NOTIFICATIONS_HOME_SCREEN = "notifications_home_screen"
        const val NOTIFICATIONS_ACCEPT_REQUEST = "notifications_accept_contact_request"
        const val NOTIFICATIONS_CONTACT_DETAILS = "notifications_contact_details"
        const val PROFILE_EDIT_MORE = "profile_edit_from_more"
        const val PROFILE_CHANGE_NAME = "profile_change_display_name"
        const val PROFILE_NAME_LENGTH = "profile_display_name_length"
        const val PROFILE_CHANGE_ABOUT_ME = "profile_change_about_me"
        const val PROFILE_ABOUT_ME_LENGTH = "profile_about_me_length"
        const val PROFILE_CHANGE_PICTURE_GRAVATAR = "profile_change_picture_gravatar"
        const val PROFILE_CHANGE_PICTURE_PUBLIC_URL = "profile_change_picture_public_url"
        const val PROFILE_CHANGE_PICTURE_CAMERA = "profile_change_picture_camera_photo"
        const val PROFILE_CHANGE_PICTURE_GALLERY = "profile_change_picture_gallery"
        const val TAB_SEND_TO_CONTACT = "send_tab_send_to_contact"
        const val SHORTCUT_SEND_TO_CONTACT = "shortcut_send_to_contact"
    }
}