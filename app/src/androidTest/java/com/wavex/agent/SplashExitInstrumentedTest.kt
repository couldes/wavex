package com.wavex.agent

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.wavex.agent.state.WavexViewModel
import com.wavex.agent.ui.ThemeChoice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks window appearance after the real splash exit, without changing saved theme preferences. */
class SplashExitInstrumentedTest {
    @Test(timeout = 30_000)
    fun darkThemeKeepsLightSystemBarIconsAfterSplashExit() = checkSystemBarIcons(ThemeChoice.DARK, false)

    @Test(timeout = 30_000)
    fun lightThemeKeepsDarkSystemBarIconsAfterSplashExit() = checkSystemBarIcons(ThemeChoice.LIGHT, true)

    @Test(timeout = 30_000)
    fun systemThemeFollowsDeviceAfterSplashExit() {
        val config = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        val darkIcons = config.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        checkSystemBarIcons(ThemeChoice.SYSTEM, darkIcons)
    }

    private fun checkSystemBarIcons(choice: ThemeChoice, darkIcons: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as WavexApplication
        val created = CountDownLatch(1)
        val drawn = CountDownLatch(1)
        var host: MainActivity? = null
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is MainActivity) return
                host = activity
                // Only update in-memory state; do not persist a test theme or edit user configuration.
                val state = ViewModelProvider(activity, WavexViewModel.factory(app.container))[WavexViewModel::class.java]
                state.themeChoice = choice
                val decor = activity.window.decorView
                val listener = object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        drawn.countDown()
                        decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
                    }
                }
                decor.viewTreeObserver.addOnDrawListener(listener)
                created.countDown()
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        instrumentation.runOnMainSync { app.registerActivityLifecycleCallbacks(callbacks) }
        try {
            instrumentation.targetContext.startActivity(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            // MIUI can block instrumentation's background launch. Allow an external adb harness
            // to launch upon this metadata-only marker; stock Android needs no fallback.
            if (!created.await(1, TimeUnit.SECONDS)) {
                val runId = InstrumentationRegistry.getArguments().getString("splashProbeRunId", "manual")
                Log.i("WavexSplashProbe", "ready:$runId:${choice.name}")
            }
            assertTrue("Activity must launch (on restricted ROMs use external adb fallback)", created.await(15, TimeUnit.SECONDS))
            assertTrue("Home must draw", drawn.await(10, TimeUnit.SECONDS))
            // Check settled window state, not the initial theme effect before the splash callback.
            // This is test-only observation time, never a delay in the application startup path.
            SystemClock.sleep(1_000)
            instrumentation.runOnMainSync {
                val activity = host
                assertNotNull("Launch must retain an activity", activity)
                val controller = WindowCompat.getInsetsController(activity!!.window, activity.window.decorView)
                assertEquals("Status icons must match the current app theme after splash removal", darkIcons, controller.isAppearanceLightStatusBars)
                assertEquals("Navigation icons must match the current app theme after splash removal", darkIcons, controller.isAppearanceLightNavigationBars)
                val content = activity.findViewById<android.view.View>(android.R.id.content)
                val position = IntArray(2)
                content.getLocationOnScreen(position)
                assertEquals("Splash exit must preserve edge-to-edge content origin", 0, position[1])
                assertEquals("Splash exit must preserve full-window content height", activity.window.decorView.height, content.height)
            }
        } finally {
            instrumentation.runOnMainSync {
                app.unregisterActivityLifecycleCallbacks(callbacks)
                host?.finish()
            }
        }
    }
}
