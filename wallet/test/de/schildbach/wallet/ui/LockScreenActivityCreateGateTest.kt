package de.schildbach.wallet.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.WalletData
import de.schildbach.wallet.ui.transactions.TransactionResultActivity
import io.mockk.every
import io.mockk.mockk
import org.bitcoinj.wallet.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * LockScreenActivity.onCreate's early exit (degraded launch, or no wallet) used
 * to `return` from the superclass only: every subclass carried on after its
 * super.onCreate() call into a screen without a usable wallet. Subclasses now
 * build their screen in onCreateWithWallet(), which that early exit never
 * calls.
 *
 * The activities are created without the Hilt graph: each generated Hilt base
 * is marked injected, and the two collaborators the gate reads are set by
 * hand. Nothing else is injected, so a subclass that ran its setup anyway
 * would fail on its own uninitialised dependencies or missing extras.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], manifest = Config.NONE)
class LockScreenActivityCreateGateTest {

    /** Records whether the template method ran. */
    class ProbeActivity : LockScreenActivity() {
        var createdWithWallet = false
        val finishedEarly: Boolean
            get() = finishedForNoWallet

        override fun onCreateWithWallet(savedInstanceState: Bundle?) {
            super.onCreateWithWallet(savedInstanceState)
            createdWithWallet = true
        }
    }

    @Test
    fun `a degraded launch redirects to onboarding and never runs the subclass setup`() {
        // A backup-recovered wallet whose primary save failed: in memory,
        // but not usable.
        val controller = buildWithoutHilt(ProbeActivity::class.java, degraded = true, wallet = mockk())
        val activity = controller.create().get()

        assertFalse(activity.createdWithWallet)
        assertTrue(activity.finishedEarly)
        assertTrue(activity.isFinishing)
        val redirect = shadowOf(activity).nextStartedActivity
        assertNotNull(redirect)
        assertEquals(OnboardingActivity::class.java.name, redirect.component?.className)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            redirect.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)

        // The system still drives the finishing activity through its lifecycle.
        controller.start().resume().pause().stop().destroy()
        assertFalse(activity.createdWithWallet)
    }

    @Test
    fun `no wallet finishes without a redirect and never runs the subclass setup`() {
        val controller = buildWithoutHilt(ProbeActivity::class.java, degraded = false, wallet = null)
        val activity = controller.create().get()

        assertFalse(activity.createdWithWallet)
        assertTrue(activity.finishedEarly)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.start().resume().pause().stop().destroy()
    }

    @Test
    fun `TransactionResultActivity does not build its binder after a degraded redirect`() {
        // Review finding (PR #1576): TransactionResultActivity continued into
        // its binder construction (walletData.wallet!!) after the redirect.
        // Its setup reads EXTRA_TX_ID, which this intent lacks, and its own
        // injected fields are unset, so running it would throw here.
        val controller = buildWithoutHilt(
            TransactionResultActivity::class.java,
            degraded = true,
            wallet = mockk<Wallet>()
        )
        val activity = controller.create().get()

        assertTrue(activity.isFinishing)
        assertEquals(
            OnboardingActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className
        )
        controller.start().resume().pause().stop().destroy()
    }

    private fun <T : LockScreenActivity> buildWithoutHilt(
        type: Class<T>,
        degraded: Boolean,
        wallet: Wallet?
    ): ActivityController<T> {
        val controller = Robolectric.buildActivity(type)
        val activity = controller.get()
        markHiltInjected(activity)
        activity.walletApplication = mockk(relaxed = true) {
            every { isWalletLoadDegraded } returns degraded
        }
        val walletData = mockk<WalletData>(relaxed = true)
        every { walletData.wallet } returns wallet
        activity.walletData = walletData
        return controller
    }

    /** Every generated Hilt base in the hierarchy skips its inject(). */
    private fun markHiltInjected(activity: Activity) {
        var type: Class<*>? = activity.javaClass
        while (type != null && type != Activity::class.java) {
            if (type.simpleName.startsWith("Hilt_")) {
                type.getDeclaredField("injected").apply {
                    isAccessible = true
                    setBoolean(activity, true)
                }
            }
            type = type.superclass
        }
    }
}
