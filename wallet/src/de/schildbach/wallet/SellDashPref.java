/*
 * Copyright 2014-2015 the original author or authors.
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

package de.schildbach.wallet;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;

import com.google.gson.Gson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.request.CreateAuthReq;
import de.schildbach.wallet.wallofcoins.response.CreateAuthResp;

/**
 * @author Andreas Schildbach
 */
public class SellDashPref {

    private final SharedPreferences prefs;
    private final Gson gson;


    private static final String CREATE_AUTH_RESP = "create_auth_resp";
    private static final String CREATE_AUTH_REQ = "create_auth_req";
    private static final String AUTH_TOKEN = "auth_token";


    private static final Logger log = LoggerFactory.getLogger(SellDashPref.class);

    public String getAuthToken() {
        return prefs.getString(AUTH_TOKEN, "");
    }

    public void setAuthToken(String authToken) {
        prefs.edit().putString(AUTH_TOKEN, authToken).apply();
    }

    public SellDashPref(final SharedPreferences prefs) {
        this.prefs = prefs;
        gson = new Gson();
    }

    public CreateAuthResp getCreateAuthResp() {
        return gson.fromJson(prefs.getString(CREATE_AUTH_RESP, ""), CreateAuthResp.class);
    }

    public void setCreateAuthResp(CreateAuthResp createAuthResp) {
        prefs.edit().putString(CREATE_AUTH_RESP, gson.toJson(createAuthResp)).apply();
    }

    public CreateAuthReq getCreateAuthReq() {
        return gson.fromJson(prefs.getString(CREATE_AUTH_REQ, ""), CreateAuthReq.class);
    }

    public void setCreateAuthReq(CreateAuthReq createAuthReq) {
        prefs.edit().putString(CREATE_AUTH_REQ, gson.toJson(createAuthReq)).apply();
    }

    public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
