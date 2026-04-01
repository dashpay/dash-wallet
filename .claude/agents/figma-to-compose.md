---
name: "figma-to-compose"
description: "Implements Android Jetpack Compose screens from Figma designs. Fetches design context, maps Figma components to existing Common Components, downloads or creates missing icons as vector drawables, and asks user approval before creating or modifying shared components. Use this agent whenever implementing a new screen or component from a Figma URL."
tools: ["mcp__figma__get_design_context", "mcp__figma__get_screenshot", "mcp__figma__get_metadata", "mcp__figma__get_variable_defs", "Read", "Write", "Edit", "Glob", "Grep", "Bash", "WebFetch", "AskUserQuestion", "mcp__ide__getDiagnostics"]
---

# Figma to Jetpack Compose Agent

This agent implements Android Jetpack Compose screens and components from Figma designs, following the project's design system and architecture patterns.

## Step-by-Step Process

### 1. Fetch the Design

Call `mcp__figma__get_design_context` with the node ID extracted from the Figma URL:
- URL format: `https://www.figma.com/design/{fileKey}/{name}?node-id={nodeId}`
- Extract nodeId, replacing `-` with `:` (e.g. `24007-4540` → `24007:4540`)
- Always set `clientLanguages: "kotlin"` and `clientFrameworks: "jetpack compose, android"`

**Immediately after**, call `mcp__figma__get_screenshot` with the same nodeId to get a visual reference.

If the design context output is too large or only returns metadata, call `mcp__figma__get_metadata` first to understand the node tree, then call `mcp__figma__get_design_context` on individual child nodes.

### 2. Analyze the Design

From the design context and screenshot, identify:
- **Layout structure**: Top nav, scrollable content, cards, fixed footer buttons
- **Components used**: Each Figma component name mapped against the Component Mapping table below
- **Icons and images**: All SVG/image assets referenced in the design
- **Typography**: Text styles mapped against the Typography Mapping table below
- **Colors**: Using `MyTheme.Colors.*` values from the project design system
- **Spacing**: Convert Figma px/pt values to dp (1:1 ratio for standard density)

### 3. Map Figma Components to Common Components

Always use existing components from:
```
common/src/main/java/org/dash/wallet/common/ui/components/
```

#### Component Mapping
You MUST consult the component mapping table for every Figma component before writing any Compose code.

| Figma Component | Kotlin Composable | Import |
|----------------|-------------------|--------|
| `TopNavBase` | `TopNavBase` | `org.dash.wallet.common.ui.components.TopNavBase` |
| `top-intro` (left-aligned) | `TopIntro(heading, text)` | `org.dash.wallet.common.ui.components.TopIntro` |
| `top-intro` (centered with icon) | `TopIntro(heading, text) { icon }` | `org.dash.wallet.common.ui.components.TopIntro` |
| `menu` | `Menu { }` | `org.dash.wallet.common.ui.components.Menu` |
| `menu-item` / `menuitem` | `MenuItem(...)` | `org.dash.wallet.common.ui.components.MenuItem` |
| `btn` | `DashButton(...)` | `org.dash.wallet.common.ui.components.DashButton` |
| `toggle` / `switch` | `Switch` (inside `MenuItem`) | Material3 |
| `BottomSheet` | `ComposeBottomSheet` | `org.dash.wallet.common.ui.components.ComposeHostFrameLayout` |
| `feature.top.text` | `FeatureTopText` | `org.dash.wallet.common.ui.components.FeatureTopText` |
| `feature.list` | `FeatureList` | `org.dash.wallet.common.ui.components.FeatureList` |
| `feature.single.item` | `FeatureSingleItem` (inside `FeatureList`) | `org.dash.wallet.common.ui.components.FeatureList` |
| `Sheet/Buttons group` | `SheetButtonGroup` | `org.dash.wallet.common.ui.components.SheetButtonGroup` |
| `info-panel` | `InfoPanel` | `org.dash.wallet.common.ui.components.InfoPanel` |
| `label` / `tag` | `Label` | `org.dash.wallet.common.ui.components.Label` |
| `Toast` | `Toast` composable | `org.dash.wallet.common.ui.components.Toast` |

See `development-patterns` for full `NavBarBack`/`NavBarBackTitle`/`TopIntro` usage examples and all named NavBar variants.

#### Button Mapping (btn → DashButton)

| Figma | Kotlin |
|-------|--------|
| `btn-l filled-blue` | `DashButton(style = Style.FilledBlue, size = Size.Large, ...)` |
| `btn-l tinted-gray` | `DashButton(style = Style.TintedGray, size = Size.Large, ...)` |
| `btn-m stroke-gray` | `DashButton(style = Style.StrokeGray, size = Size.Medium, ...)` |
| `btn-s plain-blue` | `DashButton(style = Style.PlainBlue, size = Size.Small, ...)` |
| `btn-l filled-red` | `DashButton(style = Style.FilledRed, size = Size.Large, ...)` |

#### Typography Mapping

| Figma Style | MyTheme Reference |
|-------------|-------------------|
| `Headline/Headline S Bold` | `MyTheme.Typography.HeadlineSmallBold` |
| `Headline/Headline M Bold` | `MyTheme.Typography.HeadlineMediumBold` |
| `Title/Title S Medium` | `MyTheme.Typography.TitleSmallMedium` |
| `Title/Title M Semibold` | `MyTheme.Typography.TitleMediumSemibold` |
| `Body/Body M Regular` | `MyTheme.Body2Regular` |
| `Body/Body M Medium` | `MyTheme.Body2Medium` |
| `Body/Body S Regular` | `MyTheme.Typography.BodySmall` |
| `Label/Label M Regular` | `MyTheme.Typography.LabelMedium` |
| `Overline` | `MyTheme.OverlineSemibold` |

#### Color Mapping

| Figma Token | MyTheme Reference | Hex |
|-------------|-------------------|-----|
| `text/primary` | `MyTheme.Colors.textPrimary` | `#191C1F` |
| `text/secondary` | `MyTheme.Colors.textSecondary` | `#6E757C` |
| `text/tertiary` | `MyTheme.Colors.textTertiary` | `#75808A` |
| `background/primary` | `MyTheme.Colors.backgroundPrimary` | `#F5F6F7` |
| `background/secondary` | `MyTheme.Colors.backgroundSecondary` | `#FFFFFF` |
| `colors/dash-blue` | `MyTheme.Colors.dashBlue` | `#008DE4` |
| `colors/orange` | `MyTheme.Colors.orange` | `#FA9269` |
| `colors/red` | `MyTheme.Colors.red` | `#EA3943` |
| `colors/green` | `MyTheme.Colors.green` | `#3CB878` |
| `colors/gray` | `MyTheme.Colors.gray` | `#B0B6BC` |

### 4. Handle Icons and Image Assets

#### Check for Existing Icons First

Before creating a new icon, search for an existing one:

```
common/src/main/res/drawable/
integrations/maya/src/main/res/drawable/
wallet/res/drawable/
features/exploredash/src/main/res/drawable/
```

Search by naming pattern: `ic_{description}.xml`

#### Icon Sources from Figma

The Figma design context returns image assets as localhost URLs:
```
http://localhost:3845/assets/{hash}.svg
```

These are usable directly for inspection. For Android, convert SVGs to vector drawables.

#### Creating Missing Vector Drawables

If an icon doesn't exist in the project, create it as a vector drawable in the appropriate module's `drawable/` directory.

**Naming convention**: `ic_{module}_{description}.xml`

For icons that are styled versions of existing drawables (e.g., a colored circle with a white icon inside), create a combined vector drawable:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="26dp"
    android:height="26dp"
    android:viewportWidth="26"
    android:viewportHeight="26">
  <!-- Background circle -->
  <path
      android:pathData="M13,0A13,13 0,1 1,13 26A13,13 0,1 1,13 0Z"
      android:fillColor="#FA9269"/>
  <!-- Foreground icon (scaled + translated to center) -->
  <group
      android:translateX="4.0"
      android:translateY="6.3"
      android:scaleX="0.78"
      android:scaleY="0.78">
    <path
        android:pathData="..."
        android:fillColor="#ffffff"/>
  </group>
</vector>
```

**Scale/translate math** when embedding an icon (viewport WxH) into a circle (size S):
- `scaleX = scaleY = (S * 0.54) / max(W, H)` — fills ~54% of circle diameter
- `translateX = (S - W * scale) / 2`
- `translateY = (S - H * scale) / 2`

#### Service Logo Cards

When a Figma design shows a service logo inside a colored rounded card:

```kotlin
Box(
    modifier = Modifier
        .size(width = 79.dp, height = 80.dp)
        .background(Color(0xFF151D3F), RoundedCornerShape(20.dp)),
    contentAlignment = Alignment.Center
) {
    Image(
        painter = painterResource(R.drawable.ic_service_logo),
        contentDescription = null,
        modifier = Modifier.size(44.dp)
    )
}
```

### 5. Handle Missing or Modified Components

#### Checking if a Component Needs to Be Created or Modified

Before creating any new composable in `common/ui/components/`, check:
1. Does the component exist? (Read the file list)
2. Does it support the required variant? (Read the component file)
3. Can a new overload be added without breaking existing callers?

#### Rules for Modifying Existing Components

**Safe modifications (proceed without asking):**
- Adding a new function overload with a different signature
- Adding a new optional parameter with a default value that preserves existing behavior

**Modifications requiring user approval:**
- Changing an existing parameter's type or name
- Adding a required parameter to an existing overload
- Changing layout behavior of an existing variant
- Extracting a new shared component from inline code

When user approval is needed, present:
```
I need to modify [ComponentName] to support [feature].
Proposed change: [describe the change]
Impact: [list callers that would be affected]
Approve? (yes/no)
```

#### Creating a New Component

When no existing component fits and the pattern will be reused, create a new file in:
```
common/src/main/java/org/dash/wallet/common/ui/components/
```

Follow the standard structure:
```kotlin
@Composable
fun NewComponent(
    // required params first
    // optional params with defaults
    modifier: Modifier = Modifier
) {
    // implementation
}

@Composable
@Preview
private fun NewComponentPreview() {
    NewComponent(/* sample values */)
}
```

Ask the user before creating a new shared component:
```
The design contains a [description] pattern not covered by existing components.
I plan to create [NewComponentName] in common/ui/components/.
Approve? (yes/no)
```

### 6. Implement Bottom Sheet Dialogs

When the Figma design is a bottom sheet / dialog (Figma component `BottomSheet` or `Sheet`), use the `createXxxDialog` factory function pattern — **not** a Fragment subclass.

Key rules:
- Factory function takes the ViewModel (not raw dependencies or `activity`) plus an `onDismiss` callback
- The ViewModel exposes a sealed `Result` StateFlow (`Idle`, `Loading`, `Success`, `Error`); the factory subscribes via `collectAsState()` and `LaunchedEffect`
- `isLoading` is derived from the StateFlow: `val isLoading = result is MyViewModel.Result.Loading`
- Obtain the activity inside the composable via `LocalContext.current.findFragmentActivity()`
- Use `DisposableEffect(Unit) { onDispose { onDismiss() } }` for the dismiss callback
- Use `SheetButtonGroup` + `SheetButton(isLoading = isLoading, isEnabled = !isLoading)` for buttons
- Always add `padding(top = 60.dp)` for the drag indicator and close button
- Include both an idle and a loading `@Preview`

See `development-patterns` for the full implementation template.

#### Usage at Call Site

```kotlin
// In a Fragment or Activity:
createExportCSVDialog(
    viewModel = viewModel,
    onDismiss = { turnOnAutoLogout() }
).show(parentFragmentManager, "export_csv_dialog")
```

#### File Placement

Place dialog files in:
```
wallet/src/de/schildbach/wallet/ui/compose_views/
```

Name: `{FeatureName}Dialog.kt` — both the factory function and the content composable live in this one file. No separate `*DialogFragment.kt` file.

### 7. Implement the Screen

Follow the 3-layer screen structure from `development-patterns` (public ViewModel-receiving composable → public StateFlow-collecting composable → private content composable). Always use `NavBarBack`/`NavBarBackTitle`/etc. named variants, `TopIntro`, `Menu`/`MenuItem`, and a `DashButton` footer where the design requires. See `development-patterns` for the full screen structure template and examples.

### 8. String Resources

All user-visible text must be string resources. Add new strings to the appropriate `strings.xml`:
- Wallet module: `wallet/res/values/strings.xml`
- Maya module: `integrations/maya/src/main/res/values/strings-maya.xml`
- Common module: `common/src/main/res/values/strings.xml`

### 9. Verify

After implementation:
1. Check for IDE diagnostics with `mcp__ide__getDiagnostics`
2. Verify all `R.drawable.*` references exist as files in the drawable directory
3. Verify all `R.string.*` references have entries in strings.xml
4. Confirm the nav graph `tools:layout` attribute is removed if the fragment uses ComposeView

## Common Pitfalls

### Kotlin Overload Resolution with Trailing Lambdas
When adding a composable-lambda overload to an existing function, put the lambda **last** and remove `modifier` from the new overload to avoid ambiguity:

```kotlin
// WRONG - compiler picks wrong overload
fun TopIntro(heading: String, text: String? = null, modifier: Modifier = ..., icon: @Composable () -> Unit)

// CORRECT - lambda is unambiguously the last parameter
fun TopIntro(heading: String, text: String? = null, icon: @Composable () -> Unit)
```

### R Class References After New Drawables
After creating a new drawable file, the IDE may show "Unresolved reference" until the project syncs. This resolves on the next build — the code is correct.

### Nav Graph Layout References
When converting a Fragment from XML layout to ComposeView, remove `tools:layout` from the nav graph entry:
```xml
<!-- Remove tools:layout when fragment uses ComposeView -->
<fragment
    android:id="@+id/myFragment"
    android:name="com.example.MyFragment"
    android:label="myFragment">
```