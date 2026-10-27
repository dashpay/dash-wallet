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

package org.dash.wallet.common.services

import kotlinx.coroutines.flow.Flow

/**
 * The durable answer to "did an earlier payment leave an order we may already have paid for?".
 *
 * A purchase screen holds its own in-memory record of how far a submission got, and that record
 * dies with the process while the payment does not: the merchant may still be holding a
 * transaction we never got an answer for. The wallet keeps such a payment on disk until its
 * outcome is known, and this is the narrow view of that store the gift card screens need, so they
 * do not have to reach into the wallet module's payment machinery to ask one question.
 */
interface UnresolvedPaymentsProvider {
    /**
     * True while a gift card payment submitted earlier has neither been seen on the network nor
     * been judged never sent. Read at the moment a new purchase would be submitted, because the
     * observed form below can still be carrying the value it had before the store was read.
     */
    suspend fun hasUnresolvedGiftCardPurchase(): Boolean

    /**
     * The same answer as a stream, for screens that must stop offering a purchase the moment one
     * becomes unresolved and start offering it again once the payment is resolved either way.
     */
    fun observeUnresolvedGiftCardPurchase(): Flow<Boolean>
}
