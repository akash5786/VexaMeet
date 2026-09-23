package com.vexa.meet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class MeetingEntryNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MeetingActivity>()

    @Test
    fun launcherShowsWelcomeWithoutStartingWebRtc() {
        compose.onNodeWithText("Secure. Simple. Together.").assertIsDisplayed()
        compose.onNodeWithText("Start a Meeting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("I already have a room ID").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertFalse(ActiveCallSession.isActive) }
    }

    @Test
    fun joinFormValidatesBeforeRequestingCallPermissions() {
        openJoin()
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("Join Meeting").performScrollTo().performClick()
        compose.onNodeWithText("Enter a room ID to join the meeting.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("invalid/room")
        compose.onNodeWithText("Join Meeting").performScrollTo().performClick()
        compose.onNodeWithText("Use 3–64 letters, numbers, underscores or hyphens.").assertIsDisplayed()
        compose.runOnIdle { assertFalse(ActiveCallSession.isActive) }
    }

    @Test
    fun joinFormSurvivesActivityRecreation() {
        openJoin()
        compose.onNode(hasSetTextAction()).performTextReplacement("room_recreation_test")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Join a Meeting").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("room_recreation_test")
        compose.runOnIdle { assertFalse(ActiveCallSession.isActive) }
    }

    @Test
    fun bothBackActionsReturnToWelcome() {
        openJoin()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithText("Secure. Simple. Together.").assertIsDisplayed()
        openJoin()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Secure. Simple. Together.").assertIsDisplayed()
    }

    private fun openJoin() {
        compose.onNodeWithText("I already have a room ID").performScrollTo().performClick()
        compose.onNodeWithText("Join a Meeting").assertIsDisplayed()
    }
}
