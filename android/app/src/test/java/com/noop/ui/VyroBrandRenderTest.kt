package com.noop.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.noop.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The VYRO wordmark and launcher layers inflate and draw (fork, 04.10.2026): a broken vector would crash Today. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VyroBrandRenderTest {
    @Test
    fun `wordmark and icon layers inflate and compose`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        for (id in listOf(R.drawable.vyro_wordmark, R.drawable.ic_launcher_foreground,
                R.drawable.ic_launcher_background, R.drawable.ic_launcher_monochrome)) {
            assertNotNull(ContextCompat.getDrawable(activity, id))
        }
        activity.setContent {
            Column {
                Image(painterResource(R.drawable.vyro_wordmark), null, Modifier.height(22.dp))
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.height(48.dp))
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
