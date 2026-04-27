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
- NavBar - use a named NavBar variant function (see NavBar section below); `TopNavBase` is the underlying base and remains available for full control
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
- ListX - ListItem (X is number 1 to 20)
- ListEmptyState - ListEmptyState
- tablelist-masternodekeys - TableListMasternodeKeyRow

## NavBar / TopNavBase (Figma: NavBar)

The navigation bar lives in:
```
common/src/main/java/org/dash/wallet/common/ui/components/TopNavBase.kt
```

**Always use a named variant function** — they map 1-to-1 to the Figma NavBar playground variants and set the correct defaults automatically. `TopNavBase` is the base composable kept for full control and backward compatibility.

**Figma Design System node:** `6828-4232`

### Named variant functions

| Figma variant | Kotlin function | Leading | Centre | Trailing |
|---|---|---|---|---|
| NavBarBack | `NavBarBack` | ← chevron | — | — |
| NavBarBackTitle | `NavBarBackTitle` | ← chevron | title | — |
| NavBarBackTitleInfo | `NavBarBackTitleInfo` | ← chevron | title | ℹ bare icon (blue) |
| NavBarTitleClose | `NavBarTitleClose` | — | title | ✕ circle button |
| NavBarBackTitlePlus | `NavBarBackTitlePlus` | ← chevron | title | + circle button |
| NavBarBackPlus | `NavBarBackPlus` | ← chevron | — | + circle button |
| NavBarTitle | `NavBarTitle` | — | title | — |
| NavBarClose | `NavBarClose` | — | — | ✕ circle button |
| NavBarActionTitleAction | `NavBarActionTitleAction` | text action | title | blue text action |
| NavBarBackTitleAction | `NavBarBackTitleAction` | ← chevron | title | blue text action |
| NavBarBackAction | `NavBarBackAction` | ← chevron | — | blue text action |

### Layout specs
- Height: 64 dp, horizontal padding: 20 dp
- Leading/trailing icon buttons: 34 dp circle (1.5 dp border) via `Template`
- Info icon (bare, no border): 22 dp, blue tint
- Title: 225 dp wide, absolutely centred in the bar
- Text actions: `MyTheme.CaptionMedium` (13sp medium); trailing text = `dashBlue`, leading text = `textPrimary`

### Examples

```kotlin
// Back only
NavBarBack(onBackClick = { findNavController().popBackStack() })

// Back + title
NavBarBackTitle(
    title = stringResource(R.string.masternode_keys_title),
    onBackClick = { findNavController().popBackStack() }
)

// Back + title + info icon
NavBarBackTitleInfo(
    title = stringResource(R.string.owner_keys_title),
    onBackClick = { findNavController().popBackStack() },
    onInfoClick = { showInfoDialog() }
)

// Back + title + plus button
NavBarBackTitlePlus(
    title = stringResource(R.string.owner_keys_title),
    onBackClick = { findNavController().popBackStack() },
    onPlusClick = { viewModel.addKey() }
)

// Title + close button
NavBarTitleClose(
    title = stringResource(R.string.confirm_title),
    onCloseClick = { dialog.dismiss() }
)

// Text action + title + blue text action
NavBarActionTitleAction(
    title = stringResource(R.string.filter_title),
    leadingActionText = stringResource(R.string.cancel),
    onLeadingActionClick = { dismiss() },
    trailingActionText = stringResource(R.string.apply),
    onTrailingActionClick = { applyFilters() }
)
```

### TopNavBase (base function — use only when no named variant fits)

```kotlin
TopNavBase(
    leadingIcon = ImageVector.vectorResource(R.drawable.ic_menu_chevron),
    onLeadingClick = onBackClick,
    trailingIcon = Icons.Default.Add,
    onTrailingClick = onAddClick,
    centralPart = false          // hide title area
)
```

Key parameters: `leadingIcon`, `leadingText`, `onLeadingClick`, `trailingIcon`, `trailingIconCircle` (false = bare icon, no border), `trailingText`, `onTrailingClick`, `centralPart`, `title`.

---

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

## ListItem / ListEmptyState (Figma: List1–List23, ListEmptyState)

The `ListItem` composable covers all numbered Figma list variants (`List1` … `List23`) in a **single component**. `ListEmptyState` handles the empty-list placeholder. Both live in:

```
common/src/main/java/org/dash/wallet/common/ui/components/ListItem.kt
```

**Figma section node ID:** `2760:14713`

### Layout structure

```
[topLabel                                    ]
Row { [leadingContent]  [left]  [trailing]   }
[bottomLabel                                 ]
```

### Parameters

| Parameter | Type | Description |
|---|---|---|
| `topLabel` | `String?` | Full-width gray label above the row (List12/18 style) |
| `bottomLabel` | `String?` | Full-width gray label below the row |
| `leadingContent` | `@Composable (() -> Unit)?` | Icon/thumbnail prepended to the row (Merchant/ATM style) |
| `label` | `String?` | Tertiary gray "key" text — left side of **key-value** rows |
| `showInfoIcon` | `Boolean` | ℹ icon after `label` or `title` (List10) |
| `helpTextAbove` | `String?` | Small gray text above `title` (List13/14/22) |
| `title` | `String?` | Primary bold text — **content-block** mode |
| `subtitle` | `String?` | Small gray text below `title` |
| `bottomHelpText` | `String?` | Small gray text at the bottom of the left column (List13) |
| `trailingText` | `String?` | Primary value text on the right |
| `trailingTextLines` | `List<String>?` | Multiple lines of value text (List6) |
| `trailingHelpText` | `String?` | Secondary text below the trailing value |
| `trailingHelpIcon` | `@DrawableRes Int?` | Small icon before `trailingHelpText` (List16) |
| `trailingActionText` | `String?` | Blue action link below the value (List5) |
| `trailingLabel` | `String?` | Small outlined chip badge (List7) |
| `trailingLeadingIcon` | `@Composable (RowScope.() -> Unit)?` | Icon **before** trailing text (List2/15/17) |
| `trailingTrailingIcon` | `@Composable (RowScope.() -> Unit)?` | Icon **after** trailing text (List3/4/20) |
| `trailingContent` | `@Composable (() -> Unit)?` | Fully custom right-side slot (List18, ATM Buy/Sell) |
| `onClick` | `(() -> Unit)?` | Row click handler |

### Left-side modes (mutually exclusive)

**Key-value mode** — set `label`:
- `label` renders as tertiary gray text at its natural width
- A `Spacer(weight=1f)` automatically pushes trailing content to the end

**Content-block mode** — set `title` (and optionally surrounding texts):
- The column expands to fill available width (`weight=1f`)
- Stack order: `helpTextAbove` (BodySmall/gray) → `title` (Body2Medium/primary) → `subtitle` (BodySmall/gray) → `bottomHelpText` (BodySmall/gray)

### Variant quick-reference

| Figma variant | Parameters to use |
|---|---|
| List1, List11 | `label`, `trailingText` |
| List2, List15 | `label`, `trailingLeadingIcon { }`, `trailingText` |
| List3 | `label`, `trailingText`, `trailingTrailingIcon { }` |
| List4 | `label`, `trailingTrailingIcon { }` |
| List5 | `label`, `trailingText`, `trailingActionText` |
| List6 | `label`, `trailingTextLines` |
| List7 | `label`, `trailingLabel` |
| List8, List9 | `title` only |
| List10 | `label`, `showInfoIcon = true`, `trailingText` |
| List12 | `topLabel`, `bottomLabel`, `leadingContent { }`, `title`, `subtitle`, `trailingText` |
| List13 | `helpTextAbove`, `title`, `subtitle`, `bottomHelpText`, `trailingTrailingIcon { }` |
| List14, List22 | `helpTextAbove`, `title` |
| List16 | `title`, `subtitle`, `trailingText`, `trailingHelpText`, `trailingHelpIcon` |
| List17 | `label`, `trailingLeadingIcon { }`, `trailingText`, `trailingHelpText` |
| List18 | `topLabel`, `bottomLabel`, `leadingContent { }`, `title`, `subtitle`, `trailingContent { }` |
| List20 | `title`, `subtitle`, `trailingText`, `trailingTrailingIcon { }` |
| List23 | `title`, `subtitle` |
| MerchantListPrev | `leadingContent { }`, `title`, `subtitle`, `trailingText`, `trailingTrailingIcon { }` |
| ATMListPrev | `leadingContent { }`, `title`, `subtitle`, `trailingContent { BuySell() }` |

### Examples

```kotlin
// List1 — key-value
ListItem(label = "Fee", trailingText = "0.001 DASH")

// List2 — key-value with leading checkbox
ListItem(
    label = "Network",
    trailingText = "Mainnet",
    trailingLeadingIcon = { CheckboxIcon(checked = true) }
)

// List5 — key-value with blue action link
ListItem(
    label = "Address",
    trailingText = "XabCD…1234",
    trailingActionText = "Copy"
)

// List6 — key-value with multi-line value
ListItem(
    label = "Notes",
    trailingTextLines = listOf("Line 1", "Line 2", "Line 3")
)

// List7 — key-value with chip badge
ListItem(label = "Status", trailingLabel = "Active")

// List8 — standalone title
ListItem(title = "Section header")

// List10 — label with ℹ info icon
ListItem(label = "Public key", showInfoIcon = true, trailingText = "XpubABCD…")

// List13 — full multi-line left block with trailing icon
ListItem(
    helpTextAbove = "Registered on",
    title = "XAbcDeFgHi1234…",
    subtitle = "Valid until 2025-12-31",
    bottomHelpText = "Tap to view details",
    trailingTrailingIcon = {
        Icon(
            painter = painterResource(R.drawable.ic_dash_blue_filled),
            contentDescription = null,
            tint = MyTheme.Colors.dashBlue,
            modifier = Modifier.size(32.dp)
        )
    }
)

// List12 — wrapper labels + leading checkbox + trailing value
ListItem(
    topLabel = "Voting keys",
    bottomLabel = "Tap to select",
    leadingContent = { CheckboxIcon(checked = false) },
    title = "Key #1",
    subtitle = "Not used",
    trailingText = "0 used"
)

// List16 — two-line content on both sides
ListItem(
    title = "Operator key",
    subtitle = "Active",
    trailingText = "XpubABCD…",
    trailingHelpText = "last used today",
    trailingHelpIcon = R.drawable.ic_swap_blue
)

// Merchant-style — leading image + trailing price + arrow
ListItem(
    leadingContent = {
        AsyncImage(
            model = merchant.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(CircleShape)
        )
    },
    title = merchant.name,
    subtitle = merchant.address,
    trailingText = "~2%",
    trailingTrailingIcon = {
        Icon(
            painter = painterResource(R.drawable.ic_menu_row_arrow),
            contentDescription = null,
            tint = MyTheme.Colors.textTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
)

// ATM-style — custom Buy/Sell trailing buttons
ListItem(
    leadingContent = {
        Image(
            painter = painterResource(R.drawable.ic_atm),
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
    },
    title = "Coinme ATM",
    subtitle = "0.3 mi away",
    trailingContent = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DashButton(text = "Buy", style = Style.TintedBlue,
                size = Size.Small, stretch = false, onClick = { })
            DashButton(text = "Sell", style = Style.TintedGray,
                size = Size.Small, stretch = false, onClick = { })
        }
    }
)
```

### ListEmptyState

Displays a centred icon, heading, optional body text, and optional action row when a list has no items.

```kotlin
ListEmptyState(
    icon = {
        Icon(
            painter = painterResource(R.drawable.ic_dash_blue_filled),
            contentDescription = null,
            tint = MyTheme.Colors.dashBlue,
            modifier = Modifier.size(48.dp)
        )
    },
    heading = stringResource(R.string.no_masternode_keys),
    body = stringResource(R.string.no_masternode_keys_description),
    actions = {
        DashButton(
            text = stringResource(R.string.add_key),
            style = Style.PlainBlue,
            size = Size.Small,
            stretch = false,
            onClick = onAddKeyClick
        )
    }
)
```

### Wrapping with Menu

`ListItem` is transparent — it does not add its own card background. Wrap one or more items in `Menu` for the standard rounded white card:

```kotlin
Menu {
    ListItem(label = "Owner",    trailingText = "XpubAB…")
    ListItem(label = "Voting",   trailingText = "XpubCD…")
    ListItem(label = "Operator", trailingText = "XpubEF…")
}
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
        NavBarBack(onBackClick = onBackClick)

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

## Factory Function Pattern

**Never** create a separate `*DialogFragment` subclass. Instead, create a factory function that returns a `ComposeBottomSheet` instance. The ViewModel owns all async work; the factory function receives the ViewModel and an `onDismiss` callback only.

### ViewModel: sealed result state

Define a sealed class in the ViewModel to represent the async operation's state:

```kotlin
sealed class ExportCsvResult {
    object Idle : ExportCsvResult()
    object Loading : ExportCsvResult()
    data class Success(val file: File) : ExportCsvResult()
    object Error : ExportCsvResult()
}

private val _exportCsvResult = MutableStateFlow<ExportCsvResult>(ExportCsvResult.Idle)
val exportCsvResult: StateFlow<ExportCsvResult> = _exportCsvResult.asStateFlow()

fun exportCsv(cacheDir: File) {
    if (_exportCsvResult.value is ExportCsvResult.Loading) return
    viewModelScope.launch {
        _exportCsvResult.value = ExportCsvResult.Loading
        try {
            val file = withContext(Dispatchers.IO) { /* ... produce file ... */ }
            _exportCsvResult.value = ExportCsvResult.Success(file)
        } catch (e: Exception) {
            _exportCsvResult.value = ExportCsvResult.Error
        }
    }
}

fun resetExportCsvResult() {
    _exportCsvResult.value = ExportCsvResult.Idle
}
```

### Factory function

```kotlin
fun createExportCSVDialog(
    viewModel: ToolsViewModel,
    onDismiss: () -> Unit = {}
): ComposeBottomSheet {
    return ComposeBottomSheet(
        backgroundStyle = R.style.SecondaryBackground,
        forceExpand = false
    ) { dialog ->
        val context = LocalContext.current
        val activity = remember(context) { context.findFragmentActivity() }
        val exportResult by viewModel.exportCsvResult.collectAsState()
        val isLoading = exportResult is ToolsViewModel.ExportCsvResult.Loading

        DisposableEffect(Unit) {
            onDispose { onDismiss() }
        }

        LaunchedEffect(exportResult) {
            when (val result = exportResult) {
                is ToolsViewModel.ExportCsvResult.Loading -> {
                    dialog.dialog?.setCancelable(false)
                    dialog.dialog?.setCanceledOnTouchOutside(false)
                }
                is ToolsViewModel.ExportCsvResult.Success -> {
                    if (!activity.isDestroyed) {
                        startSendIntent(activity, result.file)
                        dialog.dismiss()
                    }
                    viewModel.resetExportCsvResult()
                }
                is ToolsViewModel.ExportCsvResult.Error -> {
                    dialog.dialog?.setCancelable(true)
                    dialog.dialog?.setCanceledOnTouchOutside(true)
                    if (!activity.isDestroyed) {
                        AdaptiveDialog.create(
                            null,
                            activity.getString(R.string.error_title),
                            activity.getString(R.string.error_message),
                            activity.getString(R.string.button_close)
                        ).showAsync(activity)
                        dialog.dismiss()
                    }
                    viewModel.resetExportCsvResult()
                }
                is ToolsViewModel.ExportCsvResult.Idle -> Unit
            }
        }

        ExportCSVContent(
            isLoading = isLoading,
            onExportClick = {
                if (isLoading) return@ExportCSVContent
                viewModel.exportCsv(activity.cacheDir)
            }
        )
    }
}
```

### Content composable

Keep the `@Composable` content function separate and `internal`. Use `SheetButtonGroup` for all button layouts. Include two `@Preview` functions — one for idle, one for loading state:

```kotlin
@Composable
internal fun ExportCSVContent(
    isLoading: Boolean = false,
    onExportClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp), // space for drag indicator and close button
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // icon, title, body text ...

        SheetButtonGroup(
            primaryButton = SheetButton(
                text = stringResource(R.string.export_transactions),
                style = Style.FilledBlue,
                isEnabled = !isLoading,
                isLoading = isLoading,
                onClick = onExportClick
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ExportCSVContentPreview() {
    ExportCSVContent(isLoading = false, onExportClick = {})
}

@Preview(showBackground = true)
@Composable
private fun ExportCSVContentLoadingPreview() {
    ExportCSVContent(isLoading = true, onExportClick = {})
}
```

### Usage at call site

```kotlin
val secureActivity = requireActivity() as? SecureActivity
secureActivity?.turnOffAutoLogout()
createExportCSVDialog(
    viewModel = viewModel,
    onDismiss = { secureActivity?.turnOnAutoLogout() }
).show(parentFragmentManager, "export_csv_dialog")
```

### Key rules

- **ViewModel owns async work**: The ViewModel exposes a `StateFlow<SealedResult>` and a trigger function (e.g. `exportCsv()`). The factory function never launches coroutines itself.
- **`isLoading` from state**: Derive `isLoading` from the StateFlow inside the composable: `val isLoading = result is MyViewModel.Result.Loading`.
- **`LaunchedEffect(result)`**: React to state changes — lock/unlock dismissal, trigger navigation, show error dialogs, and call `viewModel.resetResult()`.
- **`DisposableEffect(Unit)`**: Use `onDispose { onDismiss() }` to call the dismiss callback regardless of how the dialog is dismissed.
- **`context.findFragmentActivity()`**: Obtain the activity inside the composable via `LocalContext.current`, not as a factory parameter.
- **Blocking dismissal**: Set `setCancelable(false)` / `setCanceledOnTouchOutside(false)` in the `Loading` branch; reset in the `Error` branch.
- **Top padding**: Always add `padding(top = 60.dp)` for the drag indicator and close button.
- **SheetButtonGroup**: Use for all button layouts (pass `isLoading` and `isEnabled` to the primary `SheetButton`).
- **File placement**: `wallet/src/de/schildbach/wallet/ui/compose_views/{Feature}Dialog.kt` — factory function and content composable in one file.
- **String resources**: Prefix all strings with a descriptive name.
- **Preview**: Always include both an idle and a loading `@Preview`.

# Compose Bottom Sheet Dialogs with an Owned ViewModel (Subclass Pattern)

The factory function pattern above is the default. Use this **subclass pattern** only when the factory function pattern is insufficient — specifically, when the dialog needs to **own** its own Hilt-injected ViewModel with a lifecycle scoped to the dialog itself (not the host fragment/activity), and/or needs Fragment-only APIs that cannot be obtained reliably from inside a `@Composable`.

## When to use this pattern

Use the subclass pattern when **any** of the following apply:

1. **Dialog-scoped Hilt ViewModel.** The dialog has its own `@HiltViewModel` injected via `by viewModels<MyViewModel>()` and that ViewModel must be scoped to the dialog instance (e.g. it holds per-instance state like `transactionId`, polling jobs, retry counters, or temporary caches that should die with the dialog).
2. **Bundle arguments via `newInstance(...)`.** The dialog is opened with non-trivial parameters that need to survive configuration changes — pass them via `arguments = bundleOf(...)` and read them in `onViewCreated`.
3. **Fragment-only APIs.** The dialog needs `registerForActivityResult` (must be called before `STARTED`), `BottomSheetBehavior` callbacks, dialog window manipulation (e.g. `screenBrightness`), or `onDestroyView` cleanup.
4. **Multiple Hilt-injected ViewModels** that need to coexist (e.g. one dialog-scoped, one shared with the parent activity).

If none of these apply, prefer the factory function pattern — it has fewer moving parts.

## Structure

The dialog file contains three logical layers, each with a clear responsibility:

```
@AndroidEntryPoint
class FeatureDetailsDialog : ComposeBottomSheet(...) {   // Layer 1: lifecycle + plumbing
    override fun Content() { FeatureDetailsContent(...) } // bridges to layer 2
}

@Composable
private fun FeatureDetailsContent(viewModel, callbacks)   // Layer 2: state collection
                                                          // collectAsState, LaunchedEffect, side effects

@Composable
internal fun FeatureDetailsView(uiState, callbacks)       // Layer 3: pure UI (preview-friendly)
```

**Layer 1 — `ComposeBottomSheet` subclass:** owns the ViewModel, reads arguments, manages window/sheet behaviour, exposes Fragment-only callbacks. Overrides `Content()` to bridge into the composable layer.

**Layer 2 — `*Content` composable (private):** receives the ViewModel, calls `collectAsState`, runs `LaunchedEffect` side effects, then delegates UI to layer 3. Holds **no** UI of its own.

**Layer 3 — `*View` composable (internal):** pure UI. Takes a `UIState` data class plus stateless lambda callbacks. Drives all `@Preview` functions.

## Example

```kotlin
@AndroidEntryPoint
class GiftCardDetailsDialog : ComposeBottomSheet(
    backgroundStyle = R.style.PrimaryBackground,
    forceExpand = true
) {
    companion object {
        private const val ARG_TRANSACTION_ID = "transactionId"
        private const val ARG_CARD_INDEX = "cardIndex"
        private const val WAIT_LIMIT_FOR_ERROR = 60

        fun newInstance(transactionId: Sha256Hash, cardIndex: Int = 0) =
            GiftCardDetailsDialog().apply {
                arguments = bundleOf(
                    ARG_TRANSACTION_ID to transactionId,
                    ARG_CARD_INDEX to cardIndex
                )
            }
    }

    // Dialog-scoped Hilt ViewModel (lives and dies with this dialog instance)
    private val viewModel by viewModels<GiftCardDetailsViewModel>()
    // Activity-scoped ViewModel for cross-screen state (use `by activityViewModels()` if needed)
    private val ctxSpendViewModel by viewModels<DashSpendViewModel>()

    private var originalBrightness: Float = -1f

    private val bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) {}
        override fun onSlide(bottomSheet: View, slideOffset: Float) {
            if (slideOffset < -0.5) setMaxBrightness(false)
        }
    }

    // registerForActivityResult MUST be a property of the Fragment — cannot live in a composable
    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* handle result */ }

    @Composable
    override fun Content() {
        GiftCardDetailsContent(
            viewModel = viewModel,
            waitLimitForError = WAIT_LIMIT_FOR_ERROR,
            onMaxBrightness = { enable -> setMaxBrightness(enable) },
            onViewTransaction = {
                deepLinkNavigate(DeepLinkDestination.Transaction(viewModel.transactionId.toString()))
            },
            onContactSupport = { contactSupport() },
            onErrorLogged = { error, msg -> ctxSpendViewModel.logError(error, msg) }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read Bundle args and forward to the ViewModel exactly once
        (requireArguments().getSerializable(ARG_TRANSACTION_ID) as? Sha256Hash)?.let { txId ->
            val cardIndex = requireArguments().getInt(ARG_CARD_INDEX, 0)
            viewModel.init(txId, cardIndex)
        }
        subscribeToBottomSheetCallback()
    }

    private fun setMaxBrightness(enable: Boolean) {
        val window = dialog?.window ?: return
        val params = window.attributes
        if (enable) {
            if (originalBrightness < 0) originalBrightness = params.screenBrightness
            params.screenBrightness = 1.0f
        } else {
            params.screenBrightness = originalBrightness
        }
        window.attributes = params
    }

    private fun subscribeToBottomSheetCallback() {
        val sheet = (dialog as BottomSheetDialog)
            .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let { BottomSheetBehavior.from(it).addBottomSheetCallback(bottomSheetCallback) }
    }

    override fun dismiss() {
        setMaxBrightness(false)
        super.dismiss()
    }

    override fun onDestroyView() {
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let { BottomSheetBehavior.from(it).removeBottomSheetCallback(bottomSheetCallback) }
        setMaxBrightness(false)
        super.onDestroyView()
    }
}

// ─── Layer 2: state-collection bridge ────────────────────────────────────────
@Composable
private fun GiftCardDetailsContent(
    viewModel: GiftCardDetailsViewModel,
    waitLimitForError: Int,
    onMaxBrightness: (Boolean) -> Unit,
    onViewTransaction: () -> Unit,
    onContactSupport: () -> Unit,
    onErrorLogged: (Exception, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }

    LaunchedEffect(uiState.error, uiState.queries) {
        if (uiState.error != null && uiState.queries == waitLimitForError) {
            onErrorLogged(uiState.error!!, "delivery failed after retries")
        }
    }

    GiftCardDetailsView(
        uiState = uiState,
        waitLimitForError = waitLimitForError,
        onMaxBrightness = onMaxBrightness,
        onViewTransaction = onViewTransaction,
        onContactSupport = onContactSupport,
        onHowToUse = { viewModel.logEvent(AnalyticsConstants.DashSpend.HOW_TO_USE) },
        onBalanceCheck = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
        onCopyNumber = { number -> number.copy(activity, "card number") },
        onCopyPin = { pin -> pin.copy(activity, "card pin") }
    )
}

// ─── Layer 3: pure UI (used by previews) ─────────────────────────────────────
@Composable
internal fun GiftCardDetailsView(
    uiState: GiftCardUIState,
    waitLimitForError: Int = 60,
    onMaxBrightness: (Boolean) -> Unit = {},
    onViewTransaction: () -> Unit = {},
    onContactSupport: () -> Unit = {},
    onHowToUse: () -> Unit = {},
    onBalanceCheck: (String) -> Unit = {},
    onCopyNumber: (String) -> Unit = {},
    onCopyPin: (String) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 60.dp)) {
        // … pure UI driven by uiState …
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingPreview() {
    GiftCardDetailsView(uiState = GiftCardUIState(/* loading state */))
}

@Preview(showBackground = true)
@Composable
private fun ErrorPreview() {
    GiftCardDetailsView(uiState = GiftCardUIState(/* error state */))
}
```

## Usage at call site

```kotlin
GiftCardDetailsDialog
    .newInstance(transactionId, cardIndex)
    .show(parentFragmentManager, "gift_card_details")
```

## Key rules

- **`@AndroidEntryPoint` is required** on the subclass for Hilt to inject the ViewModel obtained via `by viewModels<...>()`.
- **`newInstance(...) + bundleOf(...)`** for arguments — never pass parameters through constructors. Read in `onViewCreated` and forward to the ViewModel via an `init(...)` function exactly once.
- **ViewModel scope:**
  - `by viewModels<X>()` → scoped to the dialog (recreated per instance) — use this when state must die with the dialog.
  - `by activityViewModels<X>()` / `by exploreViewModels<X>()` (project helper) → shared with the host — use this only for cross-screen state already owned by the host.
- **Three-layer separation is mandatory.** Keep the `*Content` bridge composable **private** and **stateless apart from `collectAsState`/`LaunchedEffect`**. Keep the `*View` composable **internal** and **pure** so previews can drive it directly with `UIState` instances.
- **Side effects in layer 2, not layer 3.** All `LaunchedEffect`, `DisposableEffect`, `findFragmentActivity()`, `LocalContext` access happens in the `*Content` layer. Layer 3 receives only `uiState` and lambdas.
- **Fragment APIs stay in the subclass.** `registerForActivityResult`, `BottomSheetBehavior` callbacks, `dialog?.window` manipulation, `onDestroyView` cleanup — all live in layer 1 and are exposed to the composable via lambda parameters (e.g. `onMaxBrightness`).
- **Lifecycle cleanup is mandatory.** Anything subscribed in `onViewCreated` (sheet callbacks, brightness overrides) must be reverted in `onDestroyView` and `dismiss()`. Don't leak `BottomSheetBehavior` callbacks across recreations.
- **`forceExpand`:** set `true` for full-height detail dialogs (e.g. gift card details), `false` for short confirmation/info sheets.
- **Top padding:** the `*View` composable still needs `padding(top = 60.dp)` to clear the drag indicator and close button.
- **Previews:** drive every meaningful state (loading, success, error, empty) through layer 3 with hand-built `UIState` instances. Never preview layer 2 — it depends on a real ViewModel.
- **File placement:** `features/{module}/.../dialogs/{Feature}Dialog.kt` — all three layers in one file.

## Choosing between the two patterns

| Need | Factory function | Subclass |
|---|---|---|
| ViewModel already lives in the host (activity/parent fragment) | ✅ | — |
| One-shot async action (export, submit, retry) | ✅ | — |
| Dialog has no parameters, or only simple lambdas | ✅ | — |
| Dialog-scoped ViewModel with per-instance state | — | ✅ |
| Bundle arguments needed (survives config changes) | — | ✅ |
| `registerForActivityResult` from inside the dialog | — | ✅ |
| `BottomSheetBehavior` callbacks / window brightness control | — | ✅ |
| Long-lived polling, ticker jobs, or retry loops scoped to the dialog | — | ✅ |