/*
 * Copyright 2023 Dash Core Group.
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

package de.schildbach.wallet.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import de.schildbach.wallet.database.dao.AddressMetadataDao
import de.schildbach.wallet.database.dao.BlockchainIdentityDataDao
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.dao.ExchangeRatesDao
import de.schildbach.wallet.database.dao.IconBitmapDao
import de.schildbach.wallet.database.dao.InvitationsDao
import de.schildbach.wallet.database.dao.TransactionMetadataChangeCacheDao
import de.schildbach.wallet.database.dao.TransactionMetadataDao
import de.schildbach.wallet.database.dao.TransactionMetadataDocumentDao
import de.schildbach.wallet.database.dao.UserAlertDao
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.database.entity.TransactionMetadataCacheItem
import de.schildbach.wallet.database.entity.TransactionMetadataDocument
import de.schildbach.wallet.ui.dashpay.UserAlert
import org.dash.wallet.common.data.RoomConverters
import org.dash.wallet.common.data.entity.AddressMetadata
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.data.entity.IconBitmap
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.features.exploredash.data.dashdirect.GiftCardDao
import org.dash.wallet.features.exploredash.data.dashdirect.model.GiftCard

@Database(
    entities =
    [
        ExchangeRate::class,
        BlockchainState::class,
        TransactionMetadata::class,
        AddressMetadata::class,
        IconBitmap::class,
        GiftCard::class,
        BlockchainIdentityData::class,
        DashPayProfile::class,
        DashPayContactRequest::class,
        UserAlert::class,
        Invitation::class,
        TransactionMetadataCacheItem::class,
        TransactionMetadataDocument::class
    ],
    version = 18 // if increasing version, we need migrations to preserve tx/addr metadata
)
@TypeConverters(RoomConverters::class, BlockchainStateRoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exchangeRatesDao(): ExchangeRatesDao
    abstract fun blockchainStateDao(): BlockchainStateDao
    abstract fun transactionMetadataDao(): TransactionMetadataDao
    abstract fun addressMetadataDao(): AddressMetadataDao
    abstract fun iconBitmapDao(): IconBitmapDao
    abstract fun giftCardDao(): GiftCardDao
    abstract fun blockchainIdentityDataDao(): BlockchainIdentityDataDao
    abstract fun dashPayProfileDao(): DashPayProfileDao
    abstract fun dashPayContactRequestDao(): DashPayContactRequestDao
    abstract fun invitationsDao(): InvitationsDao
    abstract fun transactionMetadataCacheDao(): TransactionMetadataChangeCacheDao
    abstract fun transactionMetadataDocumentDao(): TransactionMetadataDocumentDao
    abstract fun userAlertDao(): UserAlertDao
}
