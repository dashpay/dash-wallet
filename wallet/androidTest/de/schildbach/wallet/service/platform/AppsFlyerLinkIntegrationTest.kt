package de.schildbach.wallet.service.platform

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appsflyer.AppsFlyerLib
import com.appsflyer.share.LinkGenerator
import com.appsflyer.share.ShareInviteHelper
import de.schildbach.wallet_test.BuildConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RunWith(AndroidJUnit4::class)
class AppsFlyerLinkIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Initialize AppsFlyer with test credentials
        // Replace with your actual dev key
        AppsFlyerLib.getInstance().init(BuildConfig.APPSFLYER_ID, null, context)
        AppsFlyerLib.getInstance().start(context)
        AppsFlyerLib.getInstance().setDebugLog(true)
        AppsFlyerLib.getInstance().setAppInviteOneLink(BuildConfig.APPSFLYER_TEMPLATE_ID)
    }

    @Test
    fun testCreateAppsFlyerLink() = runBlocking {
        val link = createTestLink(
            baseDeeplink = "dashpay://invite?user=testuser&action=invite",
            channel = "test_invitation",
            campaign = "test_campaign"
        )

        assert(link.isNotEmpty()) { "Generated link should not be empty" }
        assert(link.startsWith("https://")) { "Link should be HTTPS" }
        println("Generated AppsFlyer link: $link")
    }

    @Test
    fun testCreateLinkWithCustomDomain() = runBlocking {
        val link = createTestLinkWithDomain(
            baseDeeplink = "dashwallet://test?param=value",
            brandDomain = "dashpaytest.onelink.me"
        )

        assert(link.contains("dashpaytest.onelink.me")) {
            "Link should contain custom domain: $link" 
        }
        println("Generated custom domain link: $link")
    }

    private suspend fun createTestLink(
        baseDeeplink: String,
        channel: String,
        campaign: String
    ): String = suspendCancellableCoroutine { continuation ->
        val linkGenerator = ShareInviteHelper.generateInviteUrl(context)
        
        linkGenerator.setBaseDeeplink(baseDeeplink)
        linkGenerator.setChannel(channel)
        linkGenerator.setCampaign(campaign)
        linkGenerator.addParameters(
            mapOf(
                "af_og_title" to "Another user is inviting you to use DashPay",
                "af_og_description" to "DashPay Invitation to create a username",
                "af_og_image" to "https://www.iconpacks.net/icons/1/free-mail-icon-142-thumb.png"
            )
        )
        //linkGenerator.setBrandDomain("dashpaytest")
        
        linkGenerator.generateLink(context, object : LinkGenerator.ResponseListener {
            override fun onResponse(link: String?) {
                if (link != null) {
                    continuation.resume(link)
                } else {
                    continuation.resumeWithException(Exception("Generated link is null"))
                }
            }
            
            override fun onResponseError(error: String?) {
                continuation.resumeWithException(Exception("Failed to generate link: $error"))
            }
        })
    }

    private suspend fun createTestLinkWithDomain(
        baseDeeplink: String,
        brandDomain: String
    ): String = suspendCancellableCoroutine { continuation ->
        val linkGenerator = ShareInviteHelper.generateInviteUrl(context)

        linkGenerator.setBaseDeeplink(baseDeeplink)
        linkGenerator.setChannel("test")
        linkGenerator.setCampaign("test_domain")
        linkGenerator.setBrandDomain(brandDomain)

        linkGenerator.generateLink(context, object : LinkGenerator.ResponseListener {
            override fun onResponse(link: String?) {
                if (link != null) {
                    continuation.resume(link)
                } else {
                    continuation.resumeWithException(Exception("Generated link is null"))
                }
            }

            override fun onResponseError(error: String?) {
                continuation.resumeWithException(Exception("Failed to generate link: $error"))
            }
        })
    }
}