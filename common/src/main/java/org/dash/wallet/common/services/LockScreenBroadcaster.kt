package org.dash.wallet.common.services

import org.dash.wallet.common.data.SingleLiveEvent

// TODO: this class is created as a transitional measure for dismissing AlertDialogs.
// Instead of using it, consider deriving your dialog from DialogFragment.
// That way, it will be dismissed automatically.
class LockScreenBroadcaster {
    val activatingLockScreen = SingleLiveEvent<Void>()
}
