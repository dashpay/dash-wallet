package de.schildbach.wallet.util

import java.util.*
import java.util.concurrent.TimeUnit

fun Date.getDateAtHourZero(): Date {
    val daysInMillis = TimeUnit.DAYS.toMillis(1)
    val txDateCopy = Date(time)
    val timeAtHourZero = time / daysInMillis * daysInMillis
    txDateCopy.time = timeAtHourZero
    return txDateCopy
}


