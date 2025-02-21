package de.schildbach.wallet.util

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

class LogMarkerFilter(acceptedMarkers: List<String>) : Filter<ILoggingEvent?>() {

    constructor() : this(listOf())

    private val acceptedMarkers = arrayListOf<String>()

    init {
        this.acceptedMarkers.addAll(acceptedMarkers)
    }

    fun addAcceptedMarker(marker: String) {
        acceptedMarkers.add(marker)
    }
    override fun decide(event: ILoggingEvent?): FilterReply {
        val marker = event?.markers?.get(0) ?: return FilterReply.ACCEPT

        return if (acceptedMarkers.contains(marker.name)) {
            FilterReply.ACCEPT
        } else {
            FilterReply.DENY
        }
    }
}