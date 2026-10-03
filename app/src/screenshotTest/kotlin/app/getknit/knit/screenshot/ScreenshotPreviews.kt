package app.getknit.knit.screenshot

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

// Multipreview annotations for the Compose Preview Screenshot Testing plugin. Each `@PreviewTest` function
// renders once per `@Preview` here, so every subject is checked in both themes; `KnitPreview`'s `KnitTheme`
// follows `uiMode` through `isSystemInDarkTheme()`. A reference image is keyed by the function's name and the
// preview's name, so renaming either means regenerating with `updateDebugScreenshotTest`.

/** A component on its own, sized to its content, light and dark. */
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class ComponentShots

/**
 * A whole screen on a fixed phone-sized canvas, light and dark. A `Scaffold` fills whatever it is given, so
 * without a fixed size the image would depend on the renderer's default.
 */
@Preview(name = "Light", showBackground = true, device = SCREEN_SPEC)
@Preview(
    name = "Dark",
    showBackground = true,
    device = SCREEN_SPEC,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class ScreenShots

private const val SCREEN_SPEC = "spec:width=411dp,height=891dp"
