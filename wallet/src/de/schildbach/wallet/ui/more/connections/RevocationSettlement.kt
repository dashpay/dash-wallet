/* Copyright 2026 Dash Core Group. SPDX-License-Identifier: GPL-3.0-or-later */
package de.schildbach.wallet.ui.more.connections

import org.json.JSONArray
import org.json.JSONObject

/** Null means no app-wide audit was performed, not a verified absence of surviving keys. */
internal fun settleConfirmedRevocation(record: JSONObject, appWideRemainingKeys: List<Int>?): JSONObject {
    val settled = JSONObject(record.toString())
    val remaining = if (appWideRemainingKeys == null) {
        if (record.has("unrecordedKeyIds")) JSONArray(record.getJSONArray("unrecordedKeyIds").toString()) else JSONArray()
    } else JSONArray(appWideRemainingKeys)
    val incomplete = remaining.length() > 0 ||
        (appWideRemainingKeys == null && record.optString("state") == "partialRevocation")
    val appConfirmed = !incomplete && (appWideRemainingKeys != null ||
        (record.optString("state") == "disabled" && record.optBoolean("permanentRevocationConfirmed") &&
            record.optBoolean("appRevocationConfirmed")))
    return settled.put("state", if (incomplete) "partialRevocation" else "disabled")
        .put("permanentRevocationConfirmed", true)
        .put("appRevocationConfirmed", appConfirmed)
        .put("unrecordedKeyIds", remaining)
}
