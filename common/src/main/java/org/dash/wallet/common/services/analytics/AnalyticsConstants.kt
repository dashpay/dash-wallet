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
}