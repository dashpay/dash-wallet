package de.schildbach.wallet;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import org.dash.wallet.common.data.AddressMetadata;
import org.dash.wallet.common.data.BlockchainState;
import org.dash.wallet.common.data.ExchangeRate;
import org.dash.wallet.common.data.RoomConverters;
import org.dash.wallet.common.data.TransactionMetadata;

import de.schildbach.wallet.data.AddressMetadataDao;
import de.schildbach.wallet.data.AppDatabaseMigrations;
import de.schildbach.wallet.data.BlockchainIdentityData;
import de.schildbach.wallet.data.BlockchainIdentityDataDao;
import de.schildbach.wallet.data.BlockchainStateDao;
import de.schildbach.wallet.data.BlockchainStateRoomConverters;
import de.schildbach.wallet.data.DashPayContactRequest;
import de.schildbach.wallet.data.DashPayContactRequestDao;
import de.schildbach.wallet.data.DashPayProfile;
import de.schildbach.wallet.data.DashPayProfileDao;
import de.schildbach.wallet.data.Invitation;
import de.schildbach.wallet.data.InvitationsDao;
import de.schildbach.wallet.data.TransactionMetadataChangeCacheDao;
import de.schildbach.wallet.data.TransactionMetadataCacheItem;
import de.schildbach.wallet.data.TransactionMetadataDocument;
import de.schildbach.wallet.data.TransactionMetadataDao;
import de.schildbach.wallet.data.TransactionMetadataDocumentDao;
import de.schildbach.wallet.data.UserAlertDao;
import de.schildbach.wallet.rates.ExchangeRatesDao;
import de.schildbach.wallet.ui.dashpay.UserAlert;
import kotlin.Deprecated;

/**
 * @author Samuel Barbosa
 */
@Database(entities = {
        ExchangeRate.class,
        BlockchainState.class,
        TransactionMetadata.class,
        AddressMetadata.class,
        BlockchainIdentityData.class,
        DashPayProfile.class,
        DashPayContactRequest.class,
        UserAlert.class,
        Invitation.class,
        TransactionMetadataCacheItem.class,
        TransactionMetadataDocument.class
}, version = 18)
@TypeConverters({RoomConverters.class, BlockchainStateRoomConverters.class})
public abstract class AppDatabase extends RoomDatabase {

    private static AppDatabase instance;

    public abstract ExchangeRatesDao exchangeRatesDao();
    public abstract BlockchainStateDao blockchainStateDao();
    public abstract TransactionMetadataDao transactionMetadataDao();
    public abstract AddressMetadataDao addressMetadataDao();

    public abstract BlockchainIdentityDataDao blockchainIdentityDataDao();

    public abstract DashPayProfileDao dashPayProfileDao();

    public abstract DashPayContactRequestDao dashPayContactRequestDao();

    public abstract InvitationsDao invitationsDao();

    public abstract TransactionMetadataChangeCacheDao transactionMetadataCacheDao();

    public abstract TransactionMetadataDocumentDao transactionMetadataDocumentDao();

    public abstract UserAlertDao userAlertDao();

    @Deprecated(message = "Inject instead")
    public static AppDatabase getAppDatabase() {
        if (instance == null) {
            // destructive migrations are used from versions 1 to 10
            instance = Room.databaseBuilder(WalletApplication.getInstance(),
                    AppDatabase.class, "dash-wallet-database")
                    .addMigrations(
                            AppDatabaseMigrations.getMigration11To17(),
                            AppDatabaseMigrations.getMigration16To17(),
                            AppDatabaseMigrations.getMigration17To18()
                    )
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8, 9, 10).build();
        }
        return instance;
    }

}
