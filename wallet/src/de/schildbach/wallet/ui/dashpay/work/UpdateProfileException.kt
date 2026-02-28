package de.schildbach.wallet.ui.dashpay.work

enum class UpdateProfileError(val errorCode: Int) {
    NO_ERROR(0),
    DECRYPTION(1),
    PASSWORD(2),
    DOCUMENT(3),
    AUTHENTICATION(4),
    UPLOAD(5),
    BROADCAST(6);

    companion object {
        private val VALUES = values();
        fun getByValue(value: Int) = VALUES.firstOrNull { it.errorCode == value }
    }
}