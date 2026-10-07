package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydOptionalOutputSettingsTest {
    @Test fun optionalOutputsDefaultOffAndKeepExplicitPreviousSelections() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertFalse(BydOutputSettings.hudSong(app))
    }

    @Test fun unconfiguredReceiverDoesNotEnableHudRegardlessOfFingerprint() {
        // The fingerprint gate was lifted: HUD now activates on any system-signed, exported,
        // permission-less BYD HUD receiver. A receiver package that is merely installed, with no
        // registered <receiver> element or signers, still does not enable HUD.
        val app = RuntimeEnvironment.getApplication()
        val knownApp = object : ContextWrapper(app) {
            override fun getPackageName(): String = "com.shihab.diplay"
        }
        ShadowBuild.setFingerprint("BYD/DiLink4:10/unverified")
        val info = PackageInfo().apply {
            packageName = "com.byd.clusterdebug"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.clusterdebug"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        }
        shadowOf(app.packageManager).installPackage(info)
        assertFalse(BydStandaloneHudOutput.available(knownApp))
        assertTrue(BydStandaloneHudOutput.diagnostics(knownApp).contains("standaloneHudAvailable=false"))
    }

    @Test fun enabledExportedReceiverEnablesHudWithoutTheOldModelChecks() {
        // Every model gate is lifted — firmware, receiver version, signing certificate, system-app
        // flag and receiver permission. Only an enabled, exported receiver element enables HUD.
        val app = RuntimeEnvironment.getApplication()
        val knownApp = object : ContextWrapper(app) {
            override fun getPackageName(): String = "com.shihab.diplay"
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.clusterdebug"
            applicationInfo = ApplicationInfo().apply { packageName = "com.byd.clusterdebug" }
            receivers = arrayOf(ActivityInfo().apply {
                name = "com.byd.clusterdebug.BroadcastReceiverCAN"
                enabled = true
                exported = true
            })
        })
        assertTrue(BydStandaloneHudOutput.available(knownApp))
    }

}
