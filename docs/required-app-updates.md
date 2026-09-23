# Required Android updates

The starting screen checks the existing Firestore document `appConfig/android`.
Set these fields after the new version is available to users on Google Play:

```json
{
  "latestVersionCode": 5,
  "latestVersionName": "1.5",
  "updateMessage": "Please update VexaMeet to continue."
}
```

The values above are examples. Use the published build's actual version code and
name. The current source build uses version code 4. The document must be readable
by the app under your Firestore security rules.

When the installed version code is lower, a non-dismissible Update dialog blocks
meeting entry and opens `com.vexa.meet` on Google Play. Returning without updating
keeps the dialog visible. Pending invites and permission results cannot start a
meeting while the update is required. Existing active calls are preserved.

If the configuration is missing or cannot be fetched, the existing fallback is
preserved: meeting entry remains available after the check completes. Firestore
may supply cached configuration while offline. This is a configuration-driven
update requirement, not automatic Play Store version detection; publishing a
release also requires updating this document.
