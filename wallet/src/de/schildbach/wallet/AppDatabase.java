package de.schildbach.wallet;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import de.schildbach.wallet.data.AddressMetadataDao;
import de.schildbach.wallet.data.BlockchainIdentityData;
import de.schildbach.wallet.data.BlockchainIdentityDataDaoAsync;
import de.schildbach.wallet.data.BlockchainIdentityDataDao;

import org.dash.wallet.common.data.AddressMetadata;
import org.dash.wallet.common.data.ExchangeRate;
import org.dash.wallet.common.data.RoomConverters;
import org.dash.wallet.common.data.TransactionMetadata;

import de.schildbach.wallet.data.AppDatabaseMigrations;
import de.schildbach.wallet.data.BlockchainState;
import de.schildbach.wallet.data.BlockchainStateDao;
import de.schildbach.wallet.data.DashPayContactRequest;
import de.schildbach.wallet.data.DashPayContactRequestDaoAsync;
import de.schildbach.wallet.data.DashPayContactRequestDao;
import de.schildbach.wallet.data.DashPayProfile;
import de.schildbach.wallet.data.DashPayProfileDaoAsync;
import de.schildbach.wallet.data.DashPayProfileDao;
import de.schildbach.wallet.data.Invitation;
import de.schildbach.wallet.data.InvitationsDao;
import de.schildbach.wallet.data.InvitationsDaoAsync;
import de.schildbach.wallet.data.TransactionMetadataDao;
import de.schildbach.wallet.data.UserAlertDao;
import de.schildbach.wallet.data.UserAlertDaoAsync;
import de.schildbach.wallet.data.BlockchainStateRoomConverters;
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
        Invitation.class
}, version = 12)
@TypeConverters({RoomConverters.class, BlockchainStateRoomConverters.class})
public abstract class AppDatabase extends RoomDatabase {

    private static AppDatabase instance;

    public abstract ExchangeRatesDao exchangeRatesDao();

    public abstract BlockchainStateDao blockchainStateDao();
    public abstract TransactionMetadataDao transactionMetadataDao();
    public abstract AddressMetadataDao addressMetadataDao();

    public abstract BlockchainIdentityDataDaoAsync blockchainIdentityDataDaoAsync();

    public abstract BlockchainIdentityDataDao blockchainIdentityDataDao();

    public abstract DashPayProfileDaoAsync dashPayProfileDaoAsync();

    public abstract DashPayProfileDao dashPayProfileDao();

    public abstract DashPayContactRequestDaoAsync dashPayContactRequestDaoAsync();

    public abstract DashPayContactRequestDao dashPayContactRequestDao();

    public abstract InvitationsDao invitationsDao();

    public abstract InvitationsDaoAsync invitationsDaoAsync();

    public abstract UserAlertDao userAlertDao();

    public abstract UserAlertDaoAsync userAlertDaoAsync();

    @Deprecated(message = "Inject instead")
    public static AppDatabase getAppDatabase() {
        if (instance == null) {
            instance = Room.databaseBuilder(WalletApplication.getInstance(),
                    AppDatabase.class, "dash-wallet-database")
                    .addMigrations(
                            AppDatabaseMigrations.getMigration11To12()
                    )
                    .fallbackToDestructiveMigration().build();
        }
        return instance;
    }

}
