package de.schildbach.wallet.ui.dashpay

enum class PreBlockStage (val value: Int) {
    None (-1),
    Starting(0),
    StartRecovery(1),
    GetIdentity(2),
    GetName(3),
    GetProfile(4),
    RecoveryComplete(5),
    Initialization (6),
    FixMissingProfiles (7),
    GetSentRequests (8),
    GetReceivedRequests(9),
    GetNewProfiles(10),
    GetUpdatedProfiles(11),
    Complete(12),
    UpdateTotal(7),
    RecoveryAndUpdateTotal(12)
}

interface OnPreBlockProgressListener {
    fun onPreBlockProgressUpdated(stage: PreBlockStage)
}