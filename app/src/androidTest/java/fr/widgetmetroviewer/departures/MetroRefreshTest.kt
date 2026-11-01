package fr.widgetmetroviewer.departures
import android.widget.RemoteViews
import android.widget.FrameLayout
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
@RunWith(AndroidJUnit4::class)
class MetroRefreshTest {
    @Test fun minuteRefreshIsDelayedAndNetworkConstrainedWithoutSecretInput() {
        val request = MetroRefreshScheduler.minuteRequest()
        assertEquals(60_000L, request.workSpec.initialDelay)
        assertFalse(request.workSpec.isPeriodic)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.input.keyValueMap.isEmpty())
    }
    @Test fun manualRequestIsImmediateForcedAndPrioritizedOnAndroidTwelve() {
        val request = MetroRefreshScheduler.immediateRequest(force = true)
        assertEquals(0L, request.workSpec.initialDelay)
        assertTrue(request.workSpec.input.getBoolean(MetroRefreshScheduler.FORCE, false))
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(android.os.Build.VERSION.SDK_INT >= 31, request.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, request.workSpec.outOfQuotaPolicy)
        assertFalse(MetroRefreshScheduler.immediateRequest(force = false).workSpec.expedited)
    }

    @Test fun widgetUsesOnlyRemoteViewsSupportedViews() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            val view = RemoteViews(context.packageName, R.layout.metro_widget).apply(context, FrameLayout(context))
            assertNotNull(view.findViewById<android.view.View>(R.id.widget_refresh))
        }
    }
    @Test fun refreshReceiverIsPrivateAndWorkContainsNoCredential() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, MetroRefreshReceiver::class.java), PackageManager.GET_META_DATA)
        assertFalse(receiver.exported)
        val request = PeriodicWorkRequestBuilder<MetroRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(MetroRefreshScheduler.FORCE to true)).build()
        assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(setOf(MetroRefreshScheduler.FORCE), request.workSpec.input.keyValueMap.keys)
    }

    @Test fun groupedInformationUsesNativeFlipperAndMultipleLineBadges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val nominal = MetroDirection(listOf("Rosny-Bois-Perrier"), true) to MetroDirection(listOf("Châtelet"), true)
        val base = MetroPanelPresentation.build("Montreuil - Hôpital", "11", nominal,
            DeparturesResult(DeparturesState.DEMO, message = "Test"), java.time.Instant.now())
        val panel = base.copy(informationNotices = listOf(
            MetroTrafficNotice("Travaux à République.", listOf("3", "5", "8", "9")),
            MetroTrafficNotice("Autre information.", listOf("11"))))
        instrumentation.runOnMainSync {
            val view = MetroInformationViews.build(context, "11", panel).apply(context, FrameLayout(context))
            val flipper = view.findViewById<android.widget.ViewFlipper>(R.id.widget_info_flipper)
            assertEquals(2, flipper.childCount)
            assertEquals(10000, flipper.flipInterval)
            assertTrue(flipper.isAutoStart)
            assertEquals(280L, flipper.inAnimation.duration)
            assertEquals(280L, flipper.outAnimation.duration)
            assertNotNull(flipper.getChildAt(0).findViewById<android.widget.ImageView>(R.id.info_metro_symbol).drawable)
            val badges = flipper.getChildAt(0).findViewById<android.widget.LinearLayout>(R.id.info_group_lines)
            assertEquals(5, (badges.getChildAt(0) as android.widget.LinearLayout).childCount)
            assertEquals(5f, (badges.getChildAt(0) as android.widget.LinearLayout).weightSum, 0f)
            flipper.showNext()
            assertTrue(flipper.displayedChild in 0..1)
            val single = MetroInformationViews.build(context, "11", base).apply(context, FrameLayout(context))
                .findViewById<android.widget.ViewFlipper>(R.id.widget_info_flipper)
            val manyPanel = base.copy(informationNotices = listOf(MetroTrafficNotice("Plusieurs lignes.",
                (1..14).map { it.toString() } + listOf("3B", "7B"))))
            val many = MetroInformationViews.build(context, "11", manyPanel).apply(context, FrameLayout(context))
            many.measure(android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(459, android.view.View.MeasureSpec.EXACTLY))
            many.layout(0, 0, 1000, 459)
            val manyRows = many.findViewById<android.widget.LinearLayout>(R.id.info_group_lines)
            assertEquals(1, manyRows.childCount)
            val manyRow = manyRows.getChildAt(0) as android.widget.LinearLayout
            assertEquals(17, manyRow.childCount)
            assertEquals(17f, manyRow.weightSum, 0f)
            val metroIcon = manyRow.getChildAt(0) as android.widget.ImageView
            for (index in 1 until manyRow.childCount) {
                val badge = manyRow.getChildAt(index) as android.widget.ImageView
                assertTrue(kotlin.math.abs(metroIcon.width - badge.width) <= 1)
                assertEquals(metroIcon.height, badge.height)
                assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER, badge.scaleType)
            }
            assertFalse(single.isAutoStart)
            assertNull(single.inAnimation)
            assertEquals("Information 2 / 2", flipper.getChildAt(1).findViewById<android.widget.TextView>(R.id.info_group_count).text.toString())
        }
    }
}
