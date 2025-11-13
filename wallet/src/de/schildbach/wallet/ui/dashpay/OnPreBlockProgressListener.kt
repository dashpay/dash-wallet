package de.schildbach.wallet.ui.dashpay

enum class PreBlockStage (val value: Int) {
    None (-1),
    Starting(0),
    StartRecovery(1),
    InitWallet(2),
    GetIdentity(3),
    GetName(4),
    GetProfile(5),
    RecoveryComplete(6),
    Initialization (7),
    FixMissingProfiles (8),
    GetReceivedRequests(9),
    GetSentRequests (10),
    GetNewProfiles(11),
    GetUpdatedProfiles(12),
    GetInvites(13),
    TransactionMetadata(14),
    Topups(15),
    Complete(16),
    UpdateTotal(7),
    RecoveryAndUpdateTotal(13)
}

interface OnPreBlockProgressListener {
    fun onPreBlockProgressUpdated(stage: PreBlockStage)
}