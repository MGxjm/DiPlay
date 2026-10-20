package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.setup.DiLinkGeneration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The DiLink 4 ADB cluster switch is DiLink 4 only; no warning is shown on DiLink 3. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class DiLink3ClusterRouteSettingsTest {
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var page: LinearLayout

    @Before fun setUp() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        controller.setup().visible()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        CarPlayBackgroundSession.clear()
    }

    @Test fun adbRouteOnDiLink3OffersNoSwitchAndNoWarning() {
        DiLinkGeneration.save(activity, DiLinkGeneration.DILINK_3)
        AirPlayPersistence.saveAdbClusterEnabled(activity, true)
        renderAdvanced()

        assertFalse(hasText(R.string.adb_cluster_activity_mode))
    }

    @Test fun diLink3WithNoRouteOffersNoSwitch() {
        DiLinkGeneration.save(activity, DiLinkGeneration.DILINK_3)
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        renderAdvanced()

        assertFalse(hasText(R.string.adb_cluster_activity_mode))
    }

    @Test fun diLink4KeepsTheAdbSwitch() {
        DiLinkGeneration.save(activity, DiLinkGeneration.DILINK_4)
        renderAdvanced()

        assertTrue(hasText(R.string.adb_cluster_activity_mode))
    }

    private fun renderAdvanced() {
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        page = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll").getChildAt(0) as LinearLayout
    }

    private fun hasText(id: Int) = views(page).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(id) }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
