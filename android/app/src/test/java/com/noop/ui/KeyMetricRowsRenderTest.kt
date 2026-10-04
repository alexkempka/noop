package com.noop.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * Renders the Today Key-Metrics rows for real, compact and detailed. Fork addition.
 *
 * Builds 570/571 crashed the Today tab: the compact rows asked their tiles for intrinsic heights, and the
 * tile label autosizes through a SubcomposeLayout, which cannot answer that question. The JVM suite only
 * covered the logic around the tiles, so nothing drew one. This test composes and lays the rows out;
 * the crash comes back as an exception here instead of on the phone.
 */
@RunWith(RobolectricTestRunner::class)
// API 24+: the app's language wrapper reads Configuration.getLocales(), which the default (minSdk) image lacks.
@Config(sdk = [34])
class KeyMetricRowsRenderTest {

    private fun tile(label: String, caption: String? = null) = KeyTileData(
        label = label, value = "42", unit = "", tint = Color.Cyan, frac = 0.5,
        caption = caption, spark = listOf(1.0, 2.0, 3.0),
    )

    // A captioned tile (steps with a goal, blood pressure) next to plain ones — the row shape that crashed.
    private val tiles = listOf(
        KeyMetric.CHARGE to tile("Charge"),
        KeyMetric.EFFORT to tile("Effort"),
        KeyMetric.BLOOD_PRESSURE to tile("Blood pressure", caption = "Estimate · mmHg"),
        KeyMetric.STEPS to tile("Steps", caption = "Goal 4,000"),
    )

    // The tiles resolve their copy through NoopApplication; Robolectric starts a plain Application, so one
    // NoopApplication is attached over it (attachBaseContext only wraps the context and registers itself).
    private fun attachApp() {
        val m = com.noop.NoopApplication::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        m.isAccessible = true
        m.invoke(com.noop.NoopApplication(), RuntimeEnvironment.getApplication())
    }

    private fun render(detailed: Boolean) {
        attachApp()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            KeyMetricRows(tiles, detailed = detailed, windowDays = 14, tapFor = { {} })
        }
        shadowOf(Looper.getMainLooper()).idle()
        // Force a measure + layout pass of the whole tree, which is where intrinsics are asked.
        val root = activity.window.decorView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `compact rows lay out without crashing`() = render(detailed = false)

    @Test
    fun `detailed rows lay out without crashing`() = render(detailed = true)
}
