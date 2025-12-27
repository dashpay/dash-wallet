# Development Patterns

This app uses MVVM (ModelView ViewModel) as the primary design pattern.

The ViewModel class should be as follows.  It should have a UIState class for all data that is displayed
in the UI rather than having many Flows, one for each.

```Kotlin
data class GiftCardUIState(
    val giftCardId: String? = null,
    val error: Exception? = null,
    val status: String? = null,
    val queries: Int = 0
)

@HiltViewModel
class GiftCardDetailsViewModel @Inject constructor(
    private val applicationScope: CoroutineScope,
    private val giftCardDao: GiftCardDao,
    private val metadataProvider: TransactionMetadataProvider,
    private val analyticsService: AnalyticsService,
    private val repository: CTXSpendRepository,
    private val walletData: WalletDataProvider,
    private val ctxSpendConfig: CTXSpendConfig
) : ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(GiftCardDetailsViewModel::class.java)
    }

    lateinit var transactionId: Sha256Hash
        private set
    private var tickerJob: Job? = null

    private var exchangeRate: ExchangeRate? = null
    private var retries = 3

    private val _uiState = MutableStateFlow(GiftCardUIState())
    val uiState: StateFlow<GiftCardUIState> = _uiState.asStateFlow()

    suspend fun performAction() {
        _uiState.value = GiftCardUIState(GiftCard("111"), null, "paid", 0)
    }
}
```
Rules for fields:
1. Asynchronously updated fields must be Flow (most of the time StateFlow) not LiveData
2. A private mutable state flow named starting with _ should be used internally in the class, while a immutable state should be created with asStateFlow on the private field

# UI

New user interfaces, whether new or updated, should use JetPack Compose.  Components such as buttons,
radio buttons, checkboxes and menu items should be keep in this folder:
```sh
../../common/src/main/java/org/dash/wallet/common/ui/components
```
Our Design System in Figma uses differnet names for components.  Here is a list that links the Figma 
component to our component
TopNavBase - TopNavBase
top-intro - TopIntro
menu - Menu
menuitem - MenuItem
button - DashButton
toggle - DashSwitch

# Screen Structure
New screens should be written in JetPack Compose

```kotlin
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onRescanBlockchainClick: () -> Unit = {},
    onAboutDashClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onCoinJoinClick: () -> Unit = {},
    onTransactionMetadataClick: () -> Unit = {},
    onBatteryOptimizationClick: () -> Unit = {}
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    
    SettingsScreen(
        uiStateFlow = viewModel.uiState,
        onBackClick = onBackClick,
        onLocalCurrencyClick = onLocalCurrencyClick,
        onRescanBlockchainClick = onRescanBlockchainClick,
        onAboutDashClick = onAboutDashClick,
        onNotificationsClick = onNotificationsClick,
        onCoinJoinClick = onCoinJoinClick,
        onTransactionMetadataClick = onTransactionMetadataClick,
        onBatteryOptimizationClick = onBatteryOptimizationClick
    )
}

@Composable
fun SettingsScreen(
    uiStateFlow: StateFlow<SettingsUIState>,
    onBackClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onRescanBlockchainClick: () -> Unit = {},
    onAboutDashClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onCoinJoinClick: () -> Unit = {},
    onTransactionMetadataClick: () -> Unit = {},
    onBatteryOptimizationClick: () -> Unit = {}
) {
    val uiState by uiStateFlow.collectAsState()
    
    SettingsScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onLocalCurrencyClick = onLocalCurrencyClick,
        onRescanBlockchainClick = onRescanBlockchainClick,
        onAboutDashClick = onAboutDashClick,
        onNotificationsClick = onNotificationsClick,
        onCoinJoinClick = onCoinJoinClick,
        onTransactionMetadataClick = onTransactionMetadataClick,
        onBatteryOptimizationClick = onBatteryOptimizationClick
    )
}

@Composable
private fun SettingsScreenContent(
    uiState: SettingsUIState,
    onBackClick: () -> Unit = {},
    onLocalCurrencyClick: () -> Unit = {},
    onRescanBlockchainClick: () -> Unit = {},
    onAboutDashClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onCoinJoinClick: () -> Unit = {},
    onTransactionMetadataClick: () -> Unit = {},
    onBatteryOptimizationClick: () -> Unit = {}
) {
    @StringRes val statusId: Int
    var balance: String? = null
    var balanceIcon: Int? = null
    val decimalFormat = DecimalFormat("0.000")
    
    if (uiState.coinJoinMixingMode == CoinJoinMode.NONE) {
        statusId = R.string.turned_off
   } else {
        if (uiState.coinJoinMixingStatus == MixingStatus.FINISHED) {
            statusId = R.string.coinjoin_progress_finished
        } else {
            statusId = when(uiState.coinJoinMixingStatus) {
                MixingStatus.NOT_STARTED -> R.string.coinjoin_not_started
                MixingStatus.MIXING -> R.string.coinjoin_mixing
                MixingStatus.FINISHING -> R.string.coinjoin_mixing_finishing
                MixingStatus.PAUSED -> R.string.coinjoin_paused
                else -> R.string.error
            }
            if (!uiState.hideBalance) {
                balance = stringResource(
                    R.string.coinjoin_progress_balance,
                    decimalFormat.format(uiState.mixedBalance.toBigDecimal()),
                    decimalFormat.format(uiState.totalBalance.toBigDecimal())
                )
                balanceIcon = R.drawable.ic_dash_d_black
            } else {
                balance = stringResource(R.string.coinjoin_progress_amount_hidden)
            }
        }
    }
    val coinJoinStatusText = when {
        uiState.coinJoinMixingMode != CoinJoinMode.NONE && (uiState.coinJoinMixingStatus == MixingStatus.MIXING || uiState.coinJoinMixingStatus == MixingStatus.FINISHING) ->
            stringResource(R.string.coinjoin_progress_status_percentage, stringResource(statusId), uiState.mixingProgress.toInt())
        else -> stringResource(statusId)
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyTheme.Colors.backgroundPrimary)
    ) {
        // Top Navigation
        TopNavBase(
            leadingIcon = ImageVector.vectorResource(R.drawable.ic_menu_chevron),
            onLeadingClick = onBackClick,
            centralPart = false,
            trailingPart = false
        )

        // Settings Header
        TopIntro(
            heading = stringResource(R.string.settings_title),
            modifier = Modifier.padding(top = 10.dp, start = 20.dp, end = 20.dp, bottom = 20.dp)
        )

        // Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Menu {
                // Local Currency
                MenuItem(
                    title = stringResource(R.string.menu_local_currency),
                    subtitle = uiState.localCurrencySymbol,
                    icon = R.drawable.ic_local_currency,
                    action = onLocalCurrencyClick
                )

                // Rescan Blockchain
                MenuItem(
                    title = "Rescan blockchain",
                    icon = R.drawable.ic_rescan_blockchain,
                    action = onRescanBlockchainClick
                )

                // About Dash
                MenuItem(
                    title = "About Dash",
                    icon = R.drawable.ic_dash_blue_filled,
                    action = onAboutDashClick
                )

                // Notifications
                MenuItem(
                    title = "Notifications",
                    icon = R.drawable.ic_notification,
                    action = onNotificationsClick
                )

                // CoinJoin
                MenuItem(
                    title = stringResource(R.string.coinjoin),
                    subtitle = coinJoinStatusText,
                    icon = R.drawable.ic_mixing,
                    action = onCoinJoinClick,
                    dashAmount = balance,
                    dashIcon = balanceIcon
                )

                // Transaction Metadata
                if (Constants.SUPPORTS_TXMETADATA) {
                    MenuItem(
                        title = "Transaction metadata",
                        icon = R.drawable.transaction_metadata,
                        action = onTransactionMetadataClick
                    )
                }

                // Battery Optimization
                MenuItem(
                    title = "Battery optimization",
                    subtitle = stringResource(
                        if (uiState.ignoringBatteryOptimizations) {
                            R.string.battery_optimization_subtitle_unrestricted
                        } else {
                            R.string.battery_optimization_subtitle_optimized
                        },
                    ),
                    icon = R.drawable.ic_battery,
                    action = onBatteryOptimizationClick
                )
            }
        }
    }
}

@Composable
@Preview
fun MoreScreenPreview() {
    SettingsScreenContent(uiState = SettingsUIState())
}

@Composable
@Preview(name = "Settings with CoinJoin Active")
fun MoreScreenPreviewWithCoinJoin() {
    val customState = SettingsUIState(
        localCurrencySymbol = "USD",
        coinJoinMixingMode = CoinJoinMode.INTERMEDIATE,
        coinJoinMixingStatus = MixingStatus.MIXING,
        mixingProgress = 50.0,
        mixedBalance = Coin.COIN,
        totalBalance = Coin.COIN.multiply(2L),
        hideBalance = false,
        ignoringBatteryOptimizations = true
    )
    SettingsScreenContent(uiState = customState)
}
```