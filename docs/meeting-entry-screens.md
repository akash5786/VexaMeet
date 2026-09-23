# Meeting entry screens

The welcome and room-entry screens follow the supplied `ChatGPT Image Sep 23, 2026, 12_53_29 AM.png` reference and its embedded Codex prompt. Both use Jetpack Compose and Material 3 inside `MeetingActivity`. The WebRTC call view continues to use XML (`activity_meeting.xml`) and its existing controls, so this layout is still required. The former `MainActivity` launcher name is retained only as a manifest alias for existing installations and pinned shortcuts; the Kotlin class, view binding, notification destination, and tests use the new name.

Both entry and call headers use white “Vexa” and the shared `brand_wordmark_blue` (`#639FFF`) for “Meet”.

## Navigation

- Welcome → **Start a Meeting** → permissions if needed → existing host-call flow.
- Welcome → **I already have a room ID** → room-entry screen.
- Room entry → **Join Meeting** → input validation → permissions if needed → existing Firestore room validation → existing participant-call flow.
- Room entry → **Start a New Meeting** → existing host-call flow.
- Room entry → Back → welcome. Entered room ID is retained.
- Call ends → welcome. Existing invite intents and active-call restoration still enter the call flow directly.

Entry screens are scrollable on short displays and when the keyboard is open. Room-entry state survives activity recreation. Camera and microphone permissions are requested only after a meeting action. A denied permission leaves the entry UI available. Repeated taps are disabled during permission and join requests; backing out invalidates an outstanding join.

## Illustration

Asset: `app/src/main/res/drawable-nodpi/meeting_hero.png`

The laptop illustration was replaced with a phone at the user's request using the built-in imagegen tool. Final edit prompt:

> Edit the provided VexaMeet welcome illustration: replace the central laptop with an upright portrait smartphone showing a video call. Keep the same polished 3D style, dark navy and blue beveled device materials, blue avatar on the phone screen and small video-call controls including a red hang-up circle. The smartphone is centered and large, fully visible, with no laptop, keyboard or laptop base remaining. Preserve the four surrounding floating rounded tiles (blue camera upper left, green person-plus upper right, purple chat left, amber calendar right), thin blue orbital arcs, soft lighting, dark background, and wide 3:2 canvas. Adjust tile spacing only as necessary around the tall phone. No text, no logo, no watermark. Keep the outer background a uniform #080D15 to blend into the app.

## Verification

`MeetingEntryNavigationTest` covers welcome navigation, local room-ID validation without starting WebRTC, both back actions, and preserving the join form through activity recreation.

The renamed `MeetingActivity`, shared header colors, and phone illustration passed the debug build and all five connected instrumentation tests on the API 37 emulator (four navigation tests plus the existing application-context test). The earlier lint check reported one existing error: `PermissionImpliesUnsupportedChromeOsHardware`, because the camera permission has no corresponding optional camera feature declaration. That distribution-related declaration was left unchanged.

The initial entry-screen implementation was visually checked with the keyboard open, and a manual emulator smoke check granted the camera, microphone, and notification permissions, started a host call, and ended it back at the welcome screen without an AndroidRuntime crash. This did not test a two-device media connection. The emulator disconnected after the latest instrumentation tests, before fresh screenshots of the phone artwork and call title could be captured.

Build with JDK 21 (available locally at `C:/Users/Akash/.jdks/jbr-21.0.11`). The Android Studio JDK 25 installation is incompatible with the project's Gradle 8.13 configuration.
