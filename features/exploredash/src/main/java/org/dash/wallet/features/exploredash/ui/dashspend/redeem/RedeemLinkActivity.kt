/*
 * Copyright 2026 Dash Core Group.
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
package org.dash.wallet.features.exploredash.ui.dashspend.redeem

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.ui.components.DashButton
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.common.ui.components.MyTheme
import org.dash.wallet.common.ui.components.Style
import org.dash.wallet.common.ui.components.TopNavBase
import org.dash.wallet.common.util.copy
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.utils.CTXSpendConstants
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import javax.inject.Inject

@AndroidEntryPoint
class RedeemLinkActivity : InteractionAwareActivity() {
    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_CHALLENGE = "extra_challenge"
        private const val EXTRA_MERCHANT_NAME = "extra_merchant_name"
        private const val MAX_JS_DIALOGS = 3
        private val log = LoggerFactory.getLogger(RedeemLinkActivity::class.java)

        fun createIntent(context: Context, url: String, challenge: String?, merchantName: String?): Intent {
            val intent = Intent(context, RedeemLinkActivity::class.java)
            intent.putExtra(EXTRA_URL, url)
            intent.putExtra(EXTRA_CHALLENGE, challenge)
            intent.putExtra(EXTRA_MERCHANT_NAME, merchantName)
            return intent
        }
    }

    @Inject lateinit var walletData: WalletDataProvider

    private lateinit var redeemUrl: String
    private var webView by mutableStateOf<WebView?>(null)
    private var host by mutableStateOf<String?>(null)
    private var loadProgress by mutableIntStateOf(0)
    private var hasError by mutableStateOf(false)
    private var jsDialog: AlertDialog? = null
    private var hasCommittedPage = false
    private var jsDialogCount = 0
    private var downloadNoticeShown = false

    // Auto-logout can be switched off, and then nothing else closes this screen when the device locks.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    private val redeemWebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString()

            if (RedeemLinkPolicy.isAllowedNavigation(url, request.isForMainFrame)) {
                return false
            }

            if (request.isForMainFrame) {
                when {
                    request.hasGesture() && RedeemLinkPolicy.isContactLink(url) -> openContactLink(request.url)
                    request.hasGesture() -> showBlockedNotice()
                    // A redirect chain that ends here leaves a blank view; give the user Retry / Open in browser.
                    !hasCommittedPage -> hasError = true
                }
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.url?.scheme.equals("http", ignoreCase = true)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            host = RedeemLinkPolicy.displayHost(url)
            // shouldOverrideUrlLoading is not consulted for form posts, so check what was committed too.
            if (RedeemLinkPolicy.isAllowedNavigation(url, isMainFrame = true)) {
                hasCommittedPage = true
            } else {
                view.stopLoading()
                hasError = true
                // Destroying a WebView from inside its own callback is only supported for onRenderProcessGone.
                Handler(Looper.getMainLooper()).post { discardWebView(view) }
            }
        }

        // Once a page has committed, the WebView's own error page keeps Back working.
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && !hasCommittedPage) {
                hasError = true
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            val status = errorResponse.statusCode
            if (RedeemLinkPolicy.isInitialServerError(request.isForMainFrame, hasCommittedPage, status)) {
                hasError = true
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (!hasCommittedPage) {
                hasError = true
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            discardWebView(view)
            hasError = true
            return true
        }
    }

    private val redeemWebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            loadProgress = newProgress
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            return showJsDialog(view, url, message, result, canDecline = false)
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            return showJsDialog(view, url, message, result, canDecline = true)
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult
        ): Boolean {
            result.cancel()
            return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            // For beforeunload, confirm() means "leave the page"; cancel() would let a page trap the user.
            result.confirm()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerScreenOffReceiver()

        // A restored instance would show the page without the wallet's lock screen in front of it.
        if (savedInstanceState != null) {
            finish()
            return
        }

        val url = intent.getStringExtra(EXTRA_URL)
        val ctxBaseUrl = CTXSpendConstants.baseUrl(walletData.networkParameters)

        if (url == null || !RedeemLinkPolicy.canOpenInApp(url, Build.VERSION.SDK_INT, ctxBaseUrl)) {
            finish()
            return
        }

        redeemUrl = url

        if (!loadRedeemPage(wipeData = true)) {
            openInBrowser()
            finish()
            return
        }

        val challenge = RedeemLinkPolicy.normalizeChallenge(intent.getStringExtra(EXTRA_CHALLENGE))
        val merchantName = intent.getStringExtra(EXTRA_MERCHANT_NAME)

        setContentView(
            ComposeView(this).apply {
                setContent {
                    DashWalletTheme {
                        RedeemLinkScreen(
                            title = merchantName,
                            host = host,
                            challenge = challenge,
                            progress = loadProgress,
                            hasError = hasError,
                            webView = webView,
                            onClose = { finish() },
                            onCopyChallenge = { code ->
                                code.copy(this@RedeemLinkActivity, "challenge code", sensitive = true)
                            },
                            onRetry = { retry() },
                            onOpenInBrowser = { openInBrowser() }
                        )
                    }
                }
            }
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val view = webView
                    if (!hasError && view != null && view.canGoBack()) {
                        view.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
    }

    // Only a touch on this window lifts the dialog and notice limits, so a page cannot reset them by navigating.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            jsDialogCount = 0
            downloadNoticeShown = false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        val view = webView
        webView = null

        if (isFinishing) {
            wipeWebData(view)
        }
        view?.let { destroyWebView(it) }
        super.onDestroy()
    }

    private fun registerScreenOffReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }
    }

    private fun loadRedeemPage(wipeData: Boolean): Boolean {
        val view = createWebView() ?: return false
        webView?.let { destroyWebView(it) }
        webView = view

        if (wipeData) {
            wipeWebData(view)
        }
        host = null
        hasCommittedPage = false
        jsDialogCount = 0
        downloadNoticeShown = false
        hasError = false
        loadProgress = 0
        view.loadUrl(redeemUrl)
        return true
    }

    private fun retry() {
        if (!loadRedeemPage(wipeData = false)) {
            openInBrowser()
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createWebView(): WebView? {
        return try {
            val view = WebView(this)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                setGeolocationEnabled(false)
                saveFormData = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            view.webViewClient = redeemWebViewClient
            view.webChromeClient = redeemWebChromeClient
            view.setDownloadListener { _, _, _, _, _ ->
                if (!hasCommittedPage) {
                    hasError = true
                } else if (!downloadNoticeShown) {
                    downloadNoticeShown = true
                    showBlockedNotice()
                }
            }
            view
        } catch (e: Exception) {
            log.warn("WebView is not available: {}", e.javaClass.simpleName)
            null
        }
    }

    private fun wipeWebData(view: WebView?) {
        try {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        } catch (e: Exception) {
            log.warn("WebView data could not be wiped: {}", e.javaClass.simpleName)
        }
        if (view != null) {
            view.clearCache(true)
            view.clearHistory()
        } else {
            // The HTTP cache is shared by the whole app but can only be cleared through a WebView.
            runCatching {
                WebView(applicationContext).apply {
                    clearCache(true)
                    destroy()
                }
            }
        }
    }

    private fun discardWebView(view: WebView) {
        if (view === webView) {
            webView = null
            runCatching { view.clearCache(true) }
            destroyWebView(view)
        }
    }

    private fun destroyWebView(view: WebView) {
        jsDialog?.dismiss()
        jsDialog = null
        view.stopLoading()
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    private fun showJsDialog(
        view: WebView,
        url: String?,
        message: String?,
        result: JsResult,
        canDecline: Boolean
    ): Boolean {
        if (isFinishing || isDestroyed || jsDialogCount >= MAX_JS_DIALOGS) {
            result.cancel()
            return true
        }
        jsDialogCount++

        var accepted = false
        val builder = AlertDialog.Builder(this)
            .setTitle(RedeemLinkPolicy.displayHost(url) ?: host)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> accepted = true }
            .setOnDismissListener {
                // The result belongs to a view that may have been destroyed along with this dialog.
                if (view !== webView) {
                    return@setOnDismissListener
                }
                if (accepted) {
                    result.confirm()
                } else {
                    result.cancel()
                }
            }

        if (canDecline) {
            builder.setNegativeButton(android.R.string.cancel, null)
        }
        jsDialog = builder.show()
        return true
    }

    private fun openInBrowser() {
        startExternal(Intent(Intent.ACTION_VIEW, redeemUrl.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun showBlockedNotice() {
        Toast.makeText(this, R.string.redeem_link_blocked, Toast.LENGTH_SHORT).show()
    }

    private fun openContactLink(uri: Uri) {
        startExternal(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun startExternal(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.redeem_link_no_browser, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun RedeemLinkScreen(
    title: String?,
    host: String?,
    challenge: String?,
    progress: Int,
    hasError: Boolean,
    webView: WebView?,
    onClose: () -> Unit = {},
    onCopyChallenge: (String) -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenInBrowser: () -> Unit = {}
) {
    val colors = LocalDashColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.backgroundPrimary)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        TopNavBase(
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_open_link),
            leadingContentDescription = stringResource(R.string.redeem_link_open_in_browser),
            onLeadingClick = onOpenInBrowser,
            trailingIcon = MyImages.NavBarClose,
            trailingContentDescription = stringResource(R.string.button_close),
            onTrailingClick = onClose,
            centralPart = !title.isNullOrEmpty(),
            title = title.orEmpty()
        )
        Text(
            text = host.orEmpty(),
            style = MyTheme.Typography.TitleSmallSemibold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
        Text(
            text = stringResource(R.string.redeem_link_caution),
            style = MyTheme.Typography.BodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
        )

        if (!challenge.isNullOrBlank()) {
            ChallengeBanner(challenge = challenge, onCopy = { onCopyChallenge(challenge) })
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(4.dp)
        ) {
            if (!hasError && progress < 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    color = colors.dashBlue,
                    trackColor = colors.dashBlue5,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (hasError || webView == null) {
            ErrorState(
                onRetry = onRetry,
                onOpenInBrowser = onOpenInBrowser,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            key(webView) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ChallengeBanner(challenge: String, onCopy: () -> Unit) {
    val colors = LocalDashColors.current
    val copyDescription = stringResource(R.string.redeem_link_copy_challenge)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.backgroundSecondary)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.redeem_link_challenge_label),
                style = MyTheme.Typography.LabelMedium,
                color = colors.textTertiary
            )
            Text(
                text = challenge,
                style = MyTheme.Typography.TitleLargeSemibold.copy(fontFamily = FontFamily.Monospace),
                color = colors.textPrimary
            )
        }
        DashButton(
            modifier = Modifier
                .padding(start = 8.dp)
                .semantics { contentDescription = copyDescription },
            text = stringResource(R.string.redeem_link_copy),
            style = Style.TintedBlue,
            stretch = false,
            onClick = onCopy
        )
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit, onOpenInBrowser: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDashColors.current

    Column(
        modifier = modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.redeem_link_error),
            style = MyTheme.Typography.BodyMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        DashButton(text = stringResource(R.string.try_again), onClick = onRetry)
        DashButton(
            text = stringResource(R.string.redeem_link_open_in_browser),
            style = Style.PlainBlue,
            onClick = onOpenInBrowser
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RedeemLinkErrorPreview() {
    DashWalletTheme {
        RedeemLinkScreen(
            title = "Target",
            host = "storedvalue.com",
            challenge = "A1B2C3D4E5",
            progress = 100,
            hasError = true,
            webView = null
        )
    }
}
