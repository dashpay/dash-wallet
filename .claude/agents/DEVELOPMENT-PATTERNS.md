---
name: "development-patterns"
description: "Provides guidance on MVVM architecture patterns, Jetpack Compose UI development, and coding conventions for the Dash wallet project"
tools: ["*"]
---

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
Our Design System in Figma uses different names for components.  Here is a list that links the Figma 
component to our component:

## Component Mapping from Figma to JetPack Compose in this project
- TopNavBase - TopNavBase
- top-intro - TopIntro
- menu - Menu
- menuitem - MenuItem
- btn - DashButton
- toggle - DashSwitch
- BottomSheet - ComposeBottomSheet

## Button Mapping (btn -> DashButton)
When Figma designs specify button styles, map them to DashButton as follows:

### Button Sizes
- `btn-l` -> `DashButton(size = Size.Large)` - 48dp height, 16sp text
- `btn-m` -> `DashButton(size = Size.Medium)` - 42dp height, 14sp text  
- `btn-s` -> `DashButton(size = Size.Small)` - 36dp height, 13sp text
- `btn-xs` -> `DashButton(size = Size.ExtraSmall)` - 28dp height, 12sp text

### Button Styles  
- `filled-blue` -> `DashButton(style = Style.FilledBlue)` - Blue background, white text
- `filled-orange` -> `DashButton(style = Style.FilledOrange)` - Orange background, white text
- `filled-red` -> `DashButton(style = Style.FilledRed)` - Red background, white text
- `tinted-gray` -> `DashButton(style = Style.TintedGray)` - Light gray background, dark text
- `tinted-blue` -> `DashButton(style = Style.TintedBlue)` - Light blue background, blue text
- `plain-blue` -> `DashButton(style = Style.PlainBlue)` - Transparent background, blue text
- `plain-black` -> `DashButton(style = Style.PlainBlack)` - Transparent background, black text
- `stroke-gray` -> `DashButton(style = Style.StrokeGray)` - Gray border, transparent background

### Example
```kotlin
// Figma: btn-l filled-blue
DashButton(
    text = stringResource(R.string.continue_text),
    style = Style.FilledBlue,
    size = Size.Large,
    onClick = { /* action */ }
)

// Figma: btn-l tinted-gray  
DashButton(
    text = stringResource(R.string.cancel),
    style = Style.TintedGray,
    size = Size.Large,
    onClick = { /* action */ }
)
```

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

# Compose Bottom Sheet Dialogs

When creating bottom sheet dialogs with Compose content, use the `ComposeBottomSheet` class as the base. This provides consistent bottom sheet behavior with drag indicators, close buttons, and proper theming.

## Creating a Compose Dialog

1. **Factory Function Pattern**: Create a factory function that returns a `ComposeBottomSheet` instance
2. **String Resources**: Use string resources with descriptive prefixes (e.g., `create_instant_username_`)
3. **DashButton Components**: Use `DashButton` with appropriate styles instead of Material3 buttons
4. **Preview Function**: Always include a `@Preview` for the Compose content

### Example Implementation

```kotlin
// Factory function that returns ComposeBottomSheet
fun createInstantUsernameDialog(
    onCreateInstantUsername: () -> Unit = {},
    onCancel: () -> Unit = {}
): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false,
        content = { dialog ->
            CreateInstantUsernameContent(
                onCreateClick = {
                    onCreateInstantUsername()
                    dialog.dismiss()
                },
                onCancelClick = {
                    onCancel()
                    dialog.dismiss()
                }
            )
        }
    )
}

@Composable
private fun CreateInstantUsernameContent(
    onCreateClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(top = 60.dp) // Space for drag indicator and close button
    ) {
        // Content wrapper
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = stringResource(R.string.create_instant_username_title),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF191C1F),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Description
            Text(
                text = stringResource(R.string.create_instant_username_description),
                fontSize = 14.sp,
                color = Color(0xFF525C66),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
        
        // Buttons section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Primary action button
            DashButton(
                text = stringResource(R.string.create_instant_username_button),
                style = Style.FilledBlue,
                size = Size.Large,
                onClick = onCreateClick
            )
            
            // Secondary action button
            DashButton(
                text = stringResource(R.string.create_instant_username_cancel),
                style = Style.TintedGray,
                size = Size.Large,
                onClick = onCancelClick
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CreateInstantUsernameContentPreview() {
    CreateInstantUsernameContent(
        onCreateClick = { },
        onCancelClick = { }
    )
}
```

### Usage

```kotlin
// Show the dialog
val dialog = createInstantUsernameDialog(
    onCreateInstantUsername = { 
        // Handle primary action
    },
    onCancel = { 
        // Handle cancel action
    }
)
dialog.show(supportFragmentManager, "create_instant_username")
```

### Key Points

- **Top Padding**: Always add 60dp top padding for the drag indicator and close button
- **Button Spacing**: Use 10dp spacing between buttons in a Column with `Arrangement.spacedBy(10.dp)`
- **Content Padding**: Use 40dp horizontal padding for content to match design system
- **Auto-dismiss**: Call `dialog.dismiss()` in button click handlers
- **String Resources**: Prefix all strings with a descriptive name (e.g., `create_instant_username_`)
- **DashButton Styles**: Use `Style.FilledBlue` for primary actions, `Style.TintedGray` for secondary actions
- **Preview**: Always include a preview for development and design review