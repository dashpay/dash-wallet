/*
 * Copyright 2015-present the original author or authors.
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
package org.dash.wallet.integration.liquid.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.preference.PreferenceManager
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.dash.wallet.common.Constants
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.customtabs.CustomTabActivityHelper
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.data.LiquidClient
import org.dash.wallet.integration.liquid.data.LiquidConstants
import org.dash.wallet.integration.liquid.dialog.CountrySupportDialog
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class LiquidSplashActivity : InteractionAwareActivity() {

    private var loadingDialog: ProgressDialog? = null
    private var liquidClient: LiquidClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        log.info("liquid: starting liquid splash activity")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.liquid_splash_screen)
        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        liquidClient = LiquidClient.getInstance()
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowHomeEnabled(true)
        }
        actionBar?.setTitle(R.string.link_liquid_account)
        loadingDialog = ProgressDialog(this)
        loadingDialog!!.isIndeterminate = true
        loadingDialog!!.setCancelable(false)
        loadingDialog!!.setMessage(getString(R.string.loading))
        findViewById<View>(R.id.liquid_link_account).setOnClickListener { authUser() }
        findViewById<View>(R.id.ivInfo).setOnClickListener {
            CountrySupportDialog(this, true).show()
        }

        handleIntent(intent)

        val filter = IntentFilter(FINISH_ACTION)
        filter.addDataScheme(LiquidConstants.OAUTH_CALLBACK_SCHEMA)

        LocalBroadcastManager.getInstance(this).registerReceiver(finishLinkReceiver, filter)
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getBoolean(REMIND_UNSUPPORTED_COUNTRIES, true)) {
            prefs.edit().putBoolean(REMIND_UNSUPPORTED_COUNTRIES, false).apply()
            CountrySupportDialog(this, true).show()
        }
    }


    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Check deeplinking to get user logged in or not
     */

    private fun handleIntent(intent: Intent?) {
        val action = intent!!.action
        val intentUri = intent.data
        val scheme = intentUri?.scheme
        val host = intentUri?.host
        log.info("liquid: deep link handleIntent($action, $intentUri, $scheme, $host)")

        if (Intent.ACTION_VIEW == action
                && scheme == LiquidConstants.OAUTH_CALLBACK_SCHEMA
                && host == LiquidConstants.OAUTH_CALLBACK_HOST) {
            log.info("liquid: action is ACTION_VIEW, so get the userId")
            log.info("liquid: session id is valid: ${liquidClient?.storedSessionId?.isNotEmpty()}: ${liquidClient?.storedSessionId}")
            //TODO: it is possible that liquidClient?.storedSessionId == null, and if it is
            //TODO: getUserId() will fail and an error is reported to the user
            getUserId()
        }
    }

    private val finishLinkReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.action = Intent.ACTION_VIEW
            startActivity(intent) // this will ensure that the custom tab is closed
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left)
    }

    override fun onDestroy() {
        log.info("liquid: closing liquid splash activity")
        loadingDialog?.dismiss()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(finishLinkReceiver)
        super.onDestroy()
    }

    /**
     * call api for get session id
     */
    private fun authUser() {
        log.info("liquid: authenticating user (obtaining session id)...")
        if (GenericUtils.isInternetConnected(this)) {
            if (liquidClient!!.storedSessionId!!.isEmpty()) {
                loadingDialog!!.show()
                liquidClient?.getSessionId(LiquidConstants.PUBLIC_API_KEY, object : LiquidClient.Callback<String> {

                    override fun onSuccess(data: String) {
                        log.info("liquid: get session id successful")
                        if (isFinishing) {
                            return
                        }
                        loadingDialog!!.hide()
                        openLoginUrl(data)
                    }

                    override fun onError(e: Exception?) {
                        log.error("liquid: cannot obtain session id: ${e?.message}")
                        if (isFinishing) {
                            return
                        }
                        loadingDialog!!.hide()
                        showLoadingErrorAlert()
                    }
                })
            } else {
                openLoginUrl(liquidClient?.storedSessionId!!)
            }
        } else {
            GenericUtils.showToast(this, getString(R.string.internet_connected))
            log.error("liquid: cannot connect to internet")
        }
    }

    /**
     * Call api to get user liquid details
     */
    private fun getUserId() {
        log.info("liquid: requesting user details")
        if (GenericUtils.isInternetConnected(this)) {
            loadingDialog!!.show()
            liquidClient?.getUserKycState(liquidClient?.storedSessionId!!, object : LiquidClient.Callback<String> {

                override fun onSuccess(data: String) {
                    log.info("liquid: get user id successful")
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                    startLiquidBuyAndSellDashActivity()
                }

                override fun onError(e: Exception?) {
                    log.error("liquid: cannot obtain user id: ${e?.message}")
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                    showLoadingErrorAlert()
                    liquidClient?.clearStoredSessionData()
                }
            })
        } else {
            GenericUtils.showToast(this, getString(R.string.internet_connected))
            log.error("liquid: cannot connect to internet")
        }
    }

    private fun showLoadingErrorAlert() {
        val builder = AlertDialog.Builder(this)
        builder.setCancelable(false)
        builder.setMessage(R.string.loading_error)
        builder.setPositiveButton(android.R.string.ok, null)
        val dialog: Dialog = builder.show()
        dialog.setOnDismissListener { finish() }
    }

    private fun startLiquidBuyAndSellDashActivity() {

        //setResult(RESULT_OK)
        val intent = Intent(this, LiquidBuyAndSellDashActivity::class.java)
        val extras = getIntent().extras
        if (extras != null) {
            intent.putExtras(extras)
        }
        startActivityForResult(intent, Constants.USER_BUY_SELL_DASH)
        //finish()
    }

    /**
     * Open login url in webview
     */
    private fun openLoginUrl(sessionId: String) {
        val url = "${LiquidConstants.INITIAL_URL}${sessionId}/liquid_oauth?preferred_action=follow_href&theme=light&return_url=${LiquidConstants.OAUTH_CALLBACK_URL}"
        log.info("liquid: opening webview to log in: $url")

        val builder = CustomTabsIntent.Builder()
        val toolbarColor = ContextCompat.getColor(this, R.color.colorPrimary)
        val colorSchemeParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(toolbarColor)
                .build()
        val customTabsIntent = builder.setShowTitle(true)
                .setDefaultColorSchemeParams(colorSchemeParams)
                .build()

        val uri = Uri.parse(url)

        CustomTabActivityHelper.openCustomTab(this, customTabsIntent, uri
        ) { _, _ ->
            log.info("liquid: login failure because custom tabs is not available")
            log.info("liquid: using the web browser instead for $uri")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = uri
            startActivity(intent)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        fun createIntent(context: Context): Intent? {
            return Intent(context, LiquidSplashActivity::class.java)
        }

        const val LOGIN_REQUEST_CODE = 102
        val log: Logger = LoggerFactory.getLogger(LiquidSplashActivity::class.java)
        const val FINISH_ACTION = "LiquidSplashActivity.FINISH_ACTION"
        const val REMIND_UNSUPPORTED_COUNTRIES = "LiquidSplashActivity.REMIND_UNSUPPORTED_COUNTRIES"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when {
            requestCode == LOGIN_REQUEST_CODE && resultCode == Activity.RESULT_OK -> {
                getUserId()
            }
            requestCode == Constants.USER_BUY_SELL_DASH -> {
                when (resultCode) {
                    Activity.RESULT_OK -> {
                        finish()
                    }
                    Constants.RESULT_CODE_GO_HOME -> {
                        setResult(Constants.RESULT_CODE_GO_HOME)
                        finish()
                    }
                }
            }
        }
    }
}