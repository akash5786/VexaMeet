package com.vexa.meet

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.vexa.meet.Helper.WebRTCManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallNotificationLifecycleTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    )

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var scenario: ActivityScenario<MeetingActivity>

    @Before
    fun launch() {
        scenario = ActivityScenario.launch(MeetingActivity::class.java)
    }

    @After
    fun cleanup() {
        if (::scenario.isInitialized) {
            scenario.onActivity { invokeActivity(it, "endCall") }
            scenario.close()
        }
        CallForegroundService.stop(context)
    }

    @Test
    fun endingInAppRemovesNotificationAndItStaysGone() {
        startCall()
        scenario.onActivity { invokeActivity(it, "endCall") }
        assertNotificationStaysGone()
    }

    @Test
    fun delayedMuteAndOldNotificationActionsCannotRepostAfterEnd() {
        startCall()
        val oldMute = callNotification()!!.notification.actions[0].actionIntent
        scenario.onActivity { invokeActivity(it, "endCall") }
        await { callNotification() == null }
        oldMute.send()
        scenario.onActivity {
            // A pre-update/queued service intent must also be rejected.
            it.startService(Intent(it, CallForegroundService::class.java).apply {
                action = "com.vexa.meet.action.SET_MUTED"
                putExtra("extra_muted", true)
            })
            CallForegroundService.setMuted(it, true)
        }
        assertNotificationStaysGone()
    }

    @Test
    fun oldEndActionCannotEndTheNextCall() {
        startCall()
        val oldEnd = callNotification()!!.notification.actions[1].actionIntent
        scenario.onActivity { invokeActivity(it, "endCall") }
        await { callNotification() == null }
        startCall()
        var newRoom = ""
        scenario.onActivity { newRoom = ActiveCallSession.roomId }
        oldEnd.send()
        SystemClock.sleep(1500)
        scenario.onActivity {
            assertTrue(ActiveCallSession.isActive)
            assertEquals(newRoom, ActiveCallSession.roomId)
        }
        assertEquals(newRoom, callNotification()!!.notification.extras.getString("android.title"))
        // The new call's own mute action still works.
        callNotification()!!.notification.actions[0].actionIntent.send()
        await {
            var muted = false
            scenario.onActivity { muted = !ActiveCallSession.micEnabled }
            muted
        }
    }

    @Test
    fun rapidStartStopStartKeepsOnlyTheNewCallNotification() {
        scenario.onActivity {
            invokeActivity(it, "startCallAsCaller")
            invokeActivity(it, "endCall")
            invokeActivity(it, "startCallAsCaller")
        }
        await { callNotification() != null }
        SystemClock.sleep(1500)
        scenario.onActivity {
            assertTrue(ActiveCallSession.isActive)
            assertEquals(ActiveCallSession.roomId, callNotification()!!.notification.extras.getString("android.title"))
        }
    }

    @Test
    fun remoteRoomEndWithoutActivityListenerClearsSessionAndNotification() {
        startCall()
        scenario.onActivity {
            val manager = ActiveCallSession.manager!!
            manager.setListener(null)
            ActiveCallActions.listener = null
            // Exercise the terminal callback used by the Firestore room listener.
            WebRTCManager::class.java.getDeclaredMethod("handleRoomEnded", String::class.java)
                .apply { isAccessible = true }.invoke(manager, "Room ended")
            assertFalse(ActiveCallSession.isActive)
        }
        assertNotificationStaysGone()
    }

    @Test
    fun endingFromNotificationWithoutActivityListenerCleansUp() {
        startCall()
        val endAction = callNotification()!!.notification.actions[1].actionIntent
        scenario.onActivity {
            ActiveCallActions.listener = null
            ActiveCallSession.manager?.setListener(null)
        }
        endAction.send()
        assertNotificationStaysGone()
        scenario.onActivity { assertFalse(ActiveCallSession.isActive) }
    }

    private fun startCall() {
        scenario.onActivity { invokeActivity(it, "startCallAsCaller") }
        await { callNotification() != null }
    }

    private fun invokeActivity(activity: MeetingActivity, method: String) {
        MeetingActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
    }

    private fun callNotification() = notifications.activeNotifications.firstOrNull { it.id == 1001 }

    private fun assertNotificationStaysGone() {
        await { callNotification() == null }
        val deadline = SystemClock.elapsedRealtime() + 2200
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue("Ended call notification was reposted", callNotification() == null)
            SystemClock.sleep(100)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!condition()) {
            assertTrue("Timed out waiting for call notification state", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }
}
