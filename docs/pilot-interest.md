# Optional paid-pilot interest — Android demo notebook

## What is implemented

Home → **Interested in a paid pilot? (optional)** opens a separate native Android notebook. Scanning, saved rooms, catalog placement and planning do not require an opt-in. No iOS implementation is claimed by this change.

1. **Identify / change demo host**: enter the actual host/organization name and an actual email for follow-up/withdrawal. The app does not invent a CineX recipient or verify ownership of the address. Active permissions cannot be transferred to a different host: revoke/delete them first.
2. **Record an opt-in on this device**: email, next real project/use case and current problem are required. Name, company, role and involvement in tool/budget selection are optional. Budget involvement defaults to **Not stated** and is always self-reported, never verified decision-making authority.
3. Read the full host-specific consent notice; its checkbox starts **unchecked**, including on Edit. Save without checking it cannot create a follow-up contact record. Cancel discards the unsaved form.
4. **Demo host: local contacts** → reopen, Edit (fresh opt-in required), Revoke or Delete. Revoke redacts local contact, company, role, authority, project/problem fields and retains only a minimal event tombstone with the original host and consent metadata. Delete removes the local record. Neither operation recalls copies already exported or promises physical secure erasure of flash storage.
5. **Explicit export** → CSV or JSON → confirm the personal-data warning → choose a destination in Android's document picker. Only currently active consents are exported; the notebook is reloaded after choosing a destination. Cancelling sends nothing. If Android destroys/recreates the Activity during the picker, the pending format is deliberately discarded: retry export rather than retaining a contact payload in Activity state.

## What this does NOT establish

A contact is `UNVERIFIED_INTEREST_NOT_A_COMMITMENT_OR_PAYMENT`, not a customer, verified email, qualified budget owner, validated willingness-to-pay result, purchase, subscription or commitment. There are no card/bank fields, payment requests, fees or outgoing emails/messages. Ask about a real current project/problem rather than treating hypothetical willingness to pay as evidence.

**This is an on-device team-demo notebook, not an online signup.** A named host can use it during an in-person demo and manually transfer consented contacts. Other end-user installs do not automatically deliver leads to CineX. There is no central backend/account, CRM integration, automatic sending, hidden analytics or tracking SDK added by this feature. Central collection would require an explicitly chosen backend, recipient/access controls, retention/privacy policy and additional approval/work; none was provisioned.

## Consent and stored data

Consent version: `cinex-paid-pilot-followup-v1`. The exact rendered notice (including the named host/contact), version, device-clock consent/created/updated/revoked timestamps and random record ID are saved. Times are not server-verified or cryptographic proof of consent. Human-facing timestamps use **Europe/Tallinn**; JSON uses epoch milliseconds.

Exact notice template:

> I agree that {host name} (contact: {host email}) may use the contact details and project information I provide to follow up with me about a PAID CineX pilot. This is interest only, not a purchase, subscription or payment commitment. My details are stored on this device; the CineX team does NOT automatically receive them. The demo host may explicitly export this record for that follow-up. I can ask that host to revoke permission or delete this local record. Exported copies must be handled separately.

Data location: Android `Context.getNoBackupFilesDir()/pilot-interest-v1.enc`, app-private, encrypted with AES-256-GCM using an Android Keystore key under `cinex.pilot.notebook.v1`. The key is not exported by the code. The no-backup directory excludes standard Android Auto Backup, not every possible device/vendor backup or a compromised/unlocked device. Atomic writes preserve the previous file on ordinary write failure. Missing key, malformed data or authentication failure produces a generic error; the app does not silently reset/overwrite a notebook that cannot be opened.

Limits: 100 records including revoked tombstones; 256 KiB serialized notebook; name/role/host name 120 characters, company 160, email 254, project and problem 1,000 each. Ordinary ASCII email syntax is checked, not deliverability. Invalid controls/invisible characters, broken Unicode, duplicate active addresses/IDs, bad consent metadata, unknown formats and excessive/deep input are rejected. Edits/revocation reject a device clock earlier than the record. There is no notebook import/recovery UI. Key loss, clearing app data or uninstalling can lose the notebook.

The pilot module has no networking/logging calls. Existing optional catalog/Claude/network functionality elsewhere in CineX is unchanged. Pilot data never joins room/scan storage or export: no changes to `ScanStore`, `ScanExport` or room codecs. CSV cells are quoted and formula-trigger prefixes are neutralized; JSON includes the exact consent metadata. Exports are **unencrypted personal-data copies** at the user-selected destination. A cloud document provider may upload a chosen file. Restrict access, honor withdrawal/retention requests in all copies, and do not commit real leads to GitHub or a test kit.

Forms disable view-state persistence/autofill and request non-personalized IME learning; dialogs request `FLAG_SECURE`. These are platform requests, not a guarantee against every keyboard, screenshot mechanism or compromised device. There is **no app PIN or per-host authentication**: anyone who can open the app on the unlocked phone can manage/export the notebook. Use a locked, controlled demo device. The APK is a hackathon **debug-signed** build using the repository's shared debug key; it is not a production-secure CRM or a legal/privacy-compliance certification. Wide distribution needs proper release signing and a reviewed data-handling/retention process.

## Build and portable checks

Use JDK 17, Android SDK platform 35/build-tools 35.0.0, then:

```sh
BUILD_NUMBER=83 OUT_DIR="$PWD/out/pilot" scripts/build-android-test.sh
# Or: cd android && ./gradlew assembleDebug testDebugUnitTest lintDebug -PbuildNumber=83
```

The script verifies the APK signature, package/native contents, bundled depth model/catalog, packaged pilot classes and Android JVM test results. `PilotIntakeTest` covers consent gating, save/reopen model round-trip, renewed edit permission, recipient binding, duplicate/bounded/malformed inputs, revocation/redaction/deletion, active-only exports, CSV injection safety, room-codec separation and authenticated encryption tamper/wrong-key rejection. JVM crypto tests use a synthetic JCE key: they **do not validate Android Keystore, AtomicFile, UI lifecycle or document-provider behavior**.

`swift test` still exercises the existing portable iOS planning core; it does not compile this Android feature or validate RoomPlan/SwiftUI. No physical phone or iOS SDK validation was performed for this feature. See the accompanying build log/test reports for actual results and existing lint/dependency warnings.

## Install and phone acceptance gate

Use a controlled **ARM64 Android 7.0/API 24+ ARCore-supported phone**, preferably ARCore Depth-capable for existing room scans. This APK declares ARCore required. Pilot capture itself does not use AR/depth, but the existing app still requests camera permission/initializes AR. A pilot-only unsupported-device bypass has not been added. iOS RoomPlan hardware requirements do not apply to this Android APK.

```sh
sha256sum -c CineX-android-local-0.1.83.apk.sha256
adb install -r CineX-android-local-0.1.83.apk
adb shell am start -n com.hackathon.setscan/.MainActivity
```

Or copy the APK to the phone and explicitly allow installation from that file source. Repository debug-key updates normally preserve existing rooms. **Do not uninstall/clear data just to resolve a signing/version mismatch if rooms/leads matter**: first protect existing data and investigate the actual mismatch. A lower versionCode may be refused after a newer team build; rebuild with a higher version instead of deleting app data.

Use only synthetic `example.test` contacts until the device gate is passed:

- [ ] Open app; cancel optional pilot entry; scan/catalog/room planning still usable.
- [ ] Set an actual demo host. Check the form identifies that host and says local-only/no automatic team receipt/no payment commitment.
- [ ] Fill a synthetic email, real-looking test project/problem. Leave consent unchecked → Save rejected; no record appears. Cancel also creates none.
- [ ] Check consent → Save → list/reopen. Kill/restart app and verify persistence and exact notice/version/time.
- [ ] Edit: checkbox is unchecked again; refusing it cannot save. Renew it and verify updates survive restart, with original creation time retained.
- [ ] Duplicate active email, invalid email and overlong fields rejected without replacing existing data. Try changing host with active permissions: rejected.
- [ ] Create two contacts; revoke one. Its local personal/project fields disappear, tombstone remains, restart preserves that state. Export JSON and CSV: only the active contact remains. Delete the tombstone and verify it disappears.
- [ ] Use synthetic name `=1+1` and problem containing comma/quotes/newlines: inspect CSV quoting and formula-neutralizing apostrophe. Do not use an actual formula-linked URL.
- [ ] Cancel export; no contacts sent. Save explicitly to a controlled local folder; read JSON/CSV and verify correct notice/host/interest-only status. Revoke remaining contact → export unavailable. Check document-picker cancellation/recreation/storage-denial paths show safe behavior, not silent success.
- [ ] Create a synthetic pilot contact, then export a room/scan ZIP: search all entries/JSON for its unique email/project and `consentNotice`; none should exist. Pilot export must be a separate deliberate action.
- [ ] Airplane-mode capture/list/edit/revoke should work; monitor with approved device tooling if verifying no pilot network traffic. Existing app network functions are separate. Do not put personal data in logs/screenshots.
- [ ] Check screenshots/recents protection and keyboard/autofill behavior on the actual device. Confirm exports remain plaintext and revocation does not pretend to delete existing copies.

Only a real device can close these runtime/Keystore/document-provider gates. This guide is a test plan, not a claim that those checks have passed.
