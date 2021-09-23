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