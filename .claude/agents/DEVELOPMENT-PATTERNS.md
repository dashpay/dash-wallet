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
- feature.top.text - FeatureTopText
- feature.list - FeatureList
- feature.single.item - FeatureSingleItem
- Sheet/Buttons group - SheetButtonGroup

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

## Feature Components (feature.top.text, feature.list, feature.single.item)

Feature components are used to display lists of features, benefits, or steps in onboarding flows, upgrade dialogs, and informational screens.

### FeatureTopText (Figma: feature.top.text)

A header component that displays a centered heading with optional text description and button.

**Figma Node ID**: 4075:36448

```kotlin
FeatureTopText(
    heading = "Security Upgrade",
    text = "Your wallet security will be upgraded to a more secure encryption system",
    showText = true,
    showButton = false
)

// With optional button
FeatureTopText(
    heading = "New Feature",
    text = "Learn more about this new feature",
    showText = true,
    showButton = true,
    buttonLabel = "Learn More",
    buttonLeadingIcon = ImageVector.vectorResource(R.drawable.ic_info),
    onButtonClick = { /* action */ }
)
```

**Parameters**:
- `heading`: String (required) - Main heading in HeadlineSmallBold style
- `text`: String? - Optional descriptive text in BodyMedium style
- `showText`: Boolean (default: true) - Controls text visibility
- `showButton`: Boolean (default: false) - Controls button visibility
- `buttonLabel`: String? - Button text
- `buttonLeadingIcon`: ImageVector? - Optional icon before button text
- `buttonTrailingIcon`: ImageVector? - Optional icon after button text
- `onButtonClick`: (() -> Unit)? - Button click handler

### FeatureList (Figma: feature.list)

A vertical list container that displays multiple feature items with consistent spacing.

**Figma Node ID**: 4075:36433

```kotlin
FeatureList(
    items = listOf(
        FeatureItem(
            heading = "Enhanced Security",
            text = "Your wallet will use the latest encryption technology",
            icon = ImageVector.vectorResource(R.drawable.ic_security)
        ),
        FeatureItem(
            heading = "Biometric Support",
            text = "Unlock your wallet with fingerprint or face recognition",
            icon = ImageVector.vectorResource(R.drawable.ic_biometric)
        )
    )
)
```

### FeatureSingleItem (Figma: feature.single.item)

An individual feature item with icon/number and text content.

**Figma Node ID**: 4075:36400

```kotlin
// With custom icon
FeatureSingleItem(
    heading = "Secure PIN",
    text = "Create a 6-digit PIN to protect your wallet",
    icon = ImageVector.vectorResource(R.drawable.ic_lock)
)

// With numbered step (Figma node: 2905:40402)
FeatureSingleItem(
    heading = "Create a secure PIN",
    text = "Choose a 6-digit PIN that you'll use to unlock your wallet",
    number = "1"
)

// Default (bordered box)
FeatureSingleItem(
    heading = "Feature Title",
    text = "Feature description"
)
```

**Parameters**:
- `heading`: String (required) - Feature title in TitleSmallMedium style
- `text`: String (required) - Feature description in BodyMedium style
- `icon`: ImageVector? - Custom icon (20dp, gray tint)
- `number`: String? - Numbered badge (blue circle with white text)

**Priority**: If both `number` and `icon` are provided, `number` takes precedence.

### FeatureItem Data Class

```kotlin
data class FeatureItem(
    val heading: String,
    val text: String,
    val icon: ImageVector? = null,
    val number: String? = null
)
```

### FeatureItemNumber (Internal Component)

A blue circular badge with white number text, used for numbered lists.

**Figma Node ID**: 2905:40402 (Background component)

```kotlin
// Used internally by FeatureSingleItem when number is provided
FeatureItemNumber(number = "1")
```

**Visual Specs**:
- Size: 20dp circle
- Background: MyTheme.Colors.dashBlue
- Border radius: 8dp
- Text: 12sp, white, centered

### Complete Example

```kotlin
@Composable
fun SecurityUpgradeDialog() {
    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Header section
        FeatureTopText(
            heading = "Security Upgrade",
            text = "Your wallet will be upgraded with the following security enhancements",
            showText = true,
            showButton = false
        )

        // Features list
        FeatureList(
            items = listOf(
                FeatureItem(
                    heading = "Modern Encryption",
                    text = "Latest AES-256 encryption for maximum security",
                    number = "1"
                ),
                FeatureItem(
                    heading = "Biometric Authentication",
                    text = "Use fingerprint or face recognition",
                    number = "2"
                ),
                FeatureItem(
                    heading = "Enhanced PIN Protection",
                    text = "Stronger PIN validation and recovery options",
                    number = "3"
                )
            )
        )

        // Action buttons
        DashButton(
            text = "Upgrade Now",
            style = Style.FilledBlue,
            size = Size.Large,
            onClick = { /* action */ }
        )
    }
}
```

## SheetButtonGroup (Figma: Sheet/Buttons group)

A button group component for bottom sheets and dialogs that supports 1-2 buttons in vertical or horizontal layouts. This component provides consistent button spacing, sizing, and positioning for sheet actions.

**Figma Node ID**: 4983-1849

### Basic Usage

```kotlin
// Single button (most common)
SheetButtonGroup(
    primaryButton = SheetButton(
        text = stringResource(R.string.continue_button),
        style = Style.FilledBlue,
        onClick = { /* action */ }
    )
)

// Two buttons - vertical layout
SheetButtonGroup(
    primaryButton = SheetButton(
        text = stringResource(R.string.continue_button),
        style = Style.FilledBlue,
        onClick = { /* primary action */ }
    ),
    secondaryButton = SheetButton(
        text = stringResource(R.string.cancel),
        style = Style.StrokeGray,
        onClick = { /* secondary action */ }
    ),
    orientation = ButtonGroupOrientation.Vertical
)

// Two buttons - horizontal layout
SheetButtonGroup(
    primaryButton = SheetButton(
        text = stringResource(R.string.confirm),
        style = Style.FilledBlue,
        onClick = { /* primary action */ }
    ),
    secondaryButton = SheetButton(
        text = stringResource(R.string.cancel),
        style = Style.StrokeGray,
        onClick = { /* secondary action */ }
    ),
    orientation = ButtonGroupOrientation.Horizontal
)
```

### SheetButton Data Class

```kotlin
data class SheetButton(
    val text: String,                    // Button text
    val style: Style,                    // Button style (FilledBlue, StrokeGray, etc.)
    val leadingIcon: ImageVector? = null, // Optional icon before text
    val trailingIcon: ImageVector? = null, // Optional icon after text
    val isEnabled: Boolean = true,        // Enable/disable state
    val isLoading: Boolean = false,       // Loading state with spinner
    val onClick: () -> Unit               // Click handler
)
```

### Layout Behavior

**Vertical Layout** (default):
- Primary button appears first (top)
- Secondary button appears below
- 10dp spacing between buttons
- Each button takes full width

**Horizontal Layout**:
- Secondary button appears on left
- Primary button appears on right
- 10dp spacing between buttons
- Buttons share width equally (50/50)

### Parameters

- `primaryButton`: SheetButton (required) - The main action button
- `secondaryButton`: SheetButton? (optional) - Secondary/cancel button
- `orientation`: ButtonGroupOrientation (default: Vertical) - Layout direction
- `modifier`: Modifier - Container modifier
- `horizontalPadding`: Dp (default: 40.dp) - Left/right padding
- `verticalPadding`: Dp (default: 20.dp) - Top/bottom padding
- `spacing`: Dp (default: 10.dp) - Space between buttons

### Button Style Guidelines

For bottom sheets and dialogs:
- **Primary action**: `Style.FilledBlue` (most common)
- **Secondary/Cancel**: `Style.StrokeGray` or `Style.TintedGray`
- **Destructive primary**: `Style.FilledRed`
- **Emphasized secondary**: `Style.TintedBlue`

### Complete Dialog Example

```kotlin
fun createConfirmationDialog(
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {}
): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false,
        content = { dialog ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 60.dp)
            ) {
                // Title and content
                FeatureTopText(
                    heading = stringResource(R.string.confirm_title),
                    text = stringResource(R.string.confirm_description),
                    showText = true,
                    showButton = false
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Button group
                SheetButtonGroup(
                    primaryButton = SheetButton(
                        text = stringResource(R.string.confirm),
                        style = Style.FilledBlue,
                        onClick = {
                            onConfirm()
                            dialog.dismiss()
                        }
                    ),
                    secondaryButton = SheetButton(
                        text = stringResource(R.string.cancel),
                        style = Style.StrokeGray,
                        onClick = {
                            onCancel()
                            dialog.dismiss()
                        }
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    )
}
```

## Typography Mapping (Figma Design System → MyTheme.Typography)

When implementing designs from Figma, use the following typography mappings. All styles are available in `MyTheme.Typography.*`:

### Display Styles (Hero Text)
| Figma Name | MyTheme.Typography | Size | Weight | Usage |
|------------|-------------------|------|--------|-------|
| DisplayLargeBold | `DisplayLargeBold` | 57sp/64sp | Bold (700) | Hero headers, splash screens |
| DisplayLargeMedium | `DisplayLargeMedium` | 57sp/64sp | Medium (500) | Large promotional text |
| DisplayLarge | `DisplayLarge` | 57sp/64sp | Regular (400) | Display text, emphasis |
| DisplayMediumBold | `DisplayMediumBold` | 45sp/52sp | Bold (700) | Large section headers |
| DisplayMediumMedium | `DisplayMediumMedium` | 45sp/52sp | Medium (500) | Medium promotional text |
| DisplayMedium | `DisplayMedium` | 45sp/52sp | Regular (400) | Display text variation |
| DisplaySmallBold | `DisplaySmallBold` | 36sp/44sp | Bold (700) | Section headers |
| DisplaySmallMedium | `DisplaySmallMedium` | 36sp/44sp | Medium (500) | Emphasized headers |
| DisplaySmall | `DisplaySmall` | 36sp/44sp | Regular (400) | Large body text |

### Headline Styles (Page Headers)
| Figma Name | MyTheme.Typography | Size | Weight | Usage |
|------------|-------------------|------|--------|-------|
| HeadlineLargeBold | `HeadlineLargeBold` | 32sp/40sp | Bold (650) | Main page titles |
| HeadlineLargeSemibold | `HeadlineLargeSemibold` | 32sp/40sp | Semibold (600) | Important headers |
| HeadlineLargeMedium | `HeadlineLargeMedium` | 32sp/40sp | Medium (500) | Standard headers |
| HeadlineLarge | `HeadlineLarge` | 32sp/40sp | Regular (400) | Subtle headers |
| HeadlineMediumBold | `HeadlineMediumBold` | 28sp/36sp | Bold (650) | Section titles |
| HeadlineMediumSemibold | `HeadlineMediumSemibold` | 28sp/36sp | Semibold (600) | Dialog titles |
| HeadlineMediumMedium | `HeadlineMediumMedium` | 28sp/36sp | Medium (500) | Card headers |
| HeadlineMedium | `HeadlineMedium` | 28sp/36sp | Regular (400) | Subtle section titles |
| HeadlineSmallBold | `HeadlineSmallBold` | 24sp/32sp | Bold (650) | Card titles, dialog headers |
| HeadlineSmallSemibold | `HeadlineSmallSemibold` | 24sp/32sp | Semibold (600) | Emphasized titles |
| HeadlineSmallMedium | `HeadlineSmallMedium` | 24sp/32sp | Medium (500) | Standard titles |
| HeadlineSmall | `HeadlineSmall` | 24sp/32sp | Regular (400) | Light titles |

### Title Styles (Section Headers)
| Figma Name | MyTheme.Typography | Size | Weight | Usage |
|------------|-------------------|------|--------|-------|
| TitleLargeBold | `TitleLargeBold` | 22sp/28sp | Bold (700) | List section headers |
| TitleLargeSemibold | `TitleLargeSemibold` | 22sp/28sp | Semibold (600) | Important labels |
| TitleLargeMedium | `TitleLargeMedium` | 22sp/28sp | Medium (500) | Standard section titles |
| TitleLarge | `TitleLarge` | 22sp/28sp | Regular (400) | Subtle section titles |
| TitleMediumBold | `TitleMediumBold` | 16sp/24sp | Bold (700) | Form field labels |
| TitleMediumSemibold | `TitleMediumSemibold` | 16sp/24sp | Semibold (600) | MenuItem titles |
| TitleMediumMedium | `TitleMediumMedium` | 16sp/24sp | Medium (500) | Standard labels |
| TitleMedium | `TitleMedium` | 16sp/24sp | Regular (400) | Light labels |
| TitleSmallBold | `TitleSmallBold` | 14sp/20sp | Bold (700) | Small section headers |
| TitleSmallSemibold | `TitleSmallSemibold` | 14sp/20sp | Semibold (600) | Emphasized labels |
| TitleSmallMedium | `TitleSmallMedium` | 14sp/20sp | Medium (500) | Standard small titles |
| TitleSmall | `TitleSmall` | 14sp/20sp | Regular (400) | Light small titles |

### Body Styles (Main Content)
| Figma Name | MyTheme.Typography | Size | Weight | Usage |
|------------|-------------------|------|--------|-------|
| BodyLargeBold | `BodyLargeBold` | 16sp/24sp | Bold (700) | Important body text |
| BodyLargeSemibold | `BodyLargeSemibold` | 16sp/24sp | Semibold (600) | Emphasized paragraphs |
| BodyLargeMedium | `BodyLargeMedium` | 16sp/24sp | Medium (500) | Standard large body |
| BodyLarge | `BodyLarge` | 16sp/24sp | Regular (400) | Large body text |
| BodyMediumBold | `BodyMediumBold` | 14sp/20sp | Bold (700) | Important content |
| BodyMediumSemibold | `BodyMediumSemibold` | 14sp/20sp | Semibold (600) | Emphasized text |
| BodyMediumMedium | `BodyMediumMedium` | 14sp/20sp | Medium (500) | Dialog descriptions |
| BodyMedium | `BodyMedium` | 14sp/20sp | Regular (400) | Standard body text |
| BodySmallBold | `BodySmallBold` | 12sp/16sp | Bold (700) | Small important text |
| BodySmallSemibold | `BodySmallSemibold` | 12sp/16sp | Semibold (600) | Small emphasized text |
| BodySmallMedium | `BodySmallMedium` | 12sp/16sp | Medium (500) | Small standard text |
| BodySmall | `BodySmall` | 12sp/16sp | Regular (400) | Small body text |

### Label Styles (Captions & Metadata)
| Figma Name | MyTheme.Typography | Size | Weight | Usage |
|------------|-------------------|------|--------|-------|
| LabelLargeBold | `LabelLargeBold` | 14sp/20sp | Bold (700) | Important labels |
| LabelLargeSemibold | `LabelLargeSemibold` | 14sp/20sp | Semibold (600) | Button text, tabs |
| LabelLargeMedium | `LabelLargeMedium` | 14sp/20sp | Medium (500) | Form labels |
| LabelLarge | `LabelLarge` | 14sp/20sp | Regular (400) | Standard labels |
| LabelMediumBold | `LabelMediumBold` | 12sp/16sp | Bold (700) | Small important labels |
| LabelMediumSemibold | `LabelMediumSemibold` | 12sp/16sp | Semibold (600) | Tags, chips |
| LabelMediumMedium | `LabelMediumMedium` | 12sp/16sp | Medium (500) | Metadata, timestamps |
| LabelMedium | `LabelMedium` | 12sp/16sp | Regular (400) | Captions, hints |
| LabelSmallBold | `LabelSmallBold` | 11sp/16sp | Bold (700) | Tiny important text |
| LabelSmallSemibold | `LabelSmallSemibold` | 11sp/16sp | Semibold (600) | Small badges |
| LabelSmallMedium | `LabelSmallMedium` | 11sp/16sp | Medium (500) | Small metadata |
| LabelSmall | `LabelSmall` | 11sp/16sp | Regular (400) | Fine print |

### Usage Examples

```kotlin
// Dialog title - Use Headline S Bold
Text(
    text = stringResource(R.string.upgrade_pin_title),
    style = MyTheme.Typography.HeadlineSmallBold,
    color = MyTheme.Colors.textPrimary
)

// Dialog description - Use Body M (Regular)
Text(
    text = stringResource(R.string.upgrade_pin_description),
    style = MyTheme.Typography.BodyMedium,
    color = MyTheme.Colors.textSecondary
)

// List item title - Use Title M Semibold
Text(
    text = "Local Currency",
    style = MyTheme.Typography.TitleMediumSemibold
)

// Timestamp or metadata - Use Label M (Regular)
Text(
    text = "2 hours ago",
    style = MyTheme.Typography.LabelMedium,
    color = MyTheme.Colors.textTertiary
)

// Button text - Use Label L Semibold (handled by DashButton)
// Amount display - Use Headline M Bold
Text(
    text = "1,234.56 DASH",
    style = MyTheme.Typography.HeadlineMediumBold
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