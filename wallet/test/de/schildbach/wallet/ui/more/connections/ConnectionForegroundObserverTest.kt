package de.schildbach.wallet.ui.more.connections

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34])
class ConnectionForegroundObserverTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun `pause invalidates immediately and resuming requires a new generation`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        val states = mutableListOf<Boolean>()
        val owner = Owner()
        owner.lifecycle.addObserver(ConnectionForegroundObserver(context, states::add))
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(states.last())
        owner.registry.currentState = Lifecycle.State.STARTED
        assertFalse(states.last())
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertEquals(listOf(true, false, true), states)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        assertFalse(states.last())
    }

    @Test fun `screen off invalidates even without a pause and screen on does not restore authority`() {
        val context = RuntimeEnvironment.getApplication()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsInteractive(true)
        val states = mutableListOf<Boolean>()
        val owner = Owner()
        val observer = ConnectionForegroundObserver(context, states::add)
        owner.lifecycle.addObserver(observer)
        owner.registry.currentState = Lifecycle.State.RESUMED
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(states.last())
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(states.last())
        shadowOf(power).setIsInteractive(false)
        assertFalse(observer.isDeviceAvailable())
        shadowOf(power).setIsInteractive(true)
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setIsDeviceLocked(true)
        assertFalse(observer.isDeviceAvailable())
        owner.registry.currentState = Lifecycle.State.DESTROYED
        val count = states.size
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(count, states.size)
    }
}
