# Jeep AA Diagnostic 2.0

Android diagnostic tool for intermittent **Android Auto communication error 8** on Jeep Cherokee KL / Uconnect VP2 7. This is an investigation tool, not an Android Auto security bypass.

## Build
GitHub Actions -> **Build Android APK** -> successful run -> **Artifacts** -> debug APK ZIP. The APK is a debug build and is not published to Play Store.

## Workflow on the phone
1. Update the app by installing the new APK over v1.0 (same package and debug signing key within one workflow environment is not guaranteed; if signature mismatch, export old data first and uninstall v1).
2. Allow precise location and notifications. Exempt from battery restrictions if needed.
3. Tap **Start monitoring** before connecting to the car.
4. Tap **Beginning connection attempt**, then connect by cable or wireless adapter.
5. Tap **Android Auto connected**, **Error 8**, or **Other error** promptly. Mark Uconnect clock changes separately and add a note including exact displayed time.
6. Repeat with success and failure cases. Tap **Export full ZIP** and share privately.

## Optional ADB permission for maximum available logs
Android apps cannot normally read other apps' logcat. On a trusted computer with Android platform-tools and USB debugging authorized:
```bash
adb devices
adb shell pm grant com.jeep.aadiagnostic android.permission.READ_LOGS
```
Then stop and restart monitoring. The UI must show READ_LOGS=true and Logcat active=true. This permission may be restricted by Android firmware, and even when granted not all protected Android Auto messages are visible. Revoke with:
```bash
adb shell pm revoke com.jeep.aadiagnostic android.permission.READ_LOGS
```
For a **complete Android bugreport** when the error occurs, use Android Developer options -> Take bug report -> Full report, then save and share the ZIP privately. This contains more information than the app can collect.

## Export content
- `events.jsonl`: timestamped user markers, time changes, GNSS, power, and network events.
- `snapshots.jsonl`: device build, auto time/zone setting, network state, GPS state, package versions, and monotonic/wall time.
- `logcat-current.txt`: continuous accessible main/system/events logs if READ_LOGS granted (24 MiB session limit).
- `logcat-matches.txt`: lines with relevant keywords (heuristic, not a diagnosis).
- `logcat-snapshot-*.txt`: recent accessible log lines around manually marked events.

## Important limitations
- **The app does not read Uconnect's internal clock or ECU logs**. Vehicle clock changes are user observations.
- USB host device counts do not reliably indicate USB accessory data connectivity.
- Location timestamps are not an independent trusted GPS clock. Lack of a phone GPS anomaly does not rule out spoofing affecting the head unit.
- User markers are not automatically detected errors; keyword matches are only leads, not proof of a cause.
- **Sensitive data:** raw logs and GNSS events may contain coordinates, network identifiers, tokens, messages, or personal data. Review before sharing, and **never upload diagnostic ZIP files to a public GitHub repository**.
- Logs remain on the phone unless explicitly exported. Old logs remain until app data is cleared; a new continuous session overwrites `logcat-current.txt`, so export before starting another session if you need the old capture.

## In-app updater (v3+)

The **Check GitHub Updates** button fetches the latest **public GitHub Release**, not Actions artifacts. It requires an APK asset named `Jeep-AA-Diagnostic.apk` and checksum asset `Jeep-AA-Diagnostic.apk.sha256`. It verifies SHA-256, then asks Android to install; it never installs silently.

**Release setup (one-time):**
1. Generate and securely back up a dedicated Android release keystore offline. Do not commit it to Git.
2. In GitHub repository Settings -> Secrets and variables -> Actions, set four repository secrets:
   - `JEEP_KEYSTORE_BASE64`: base64 of the entire keystore file (single line).
   - `JEEP_KEYSTORE_PASSWORD`: keystore password.
   - `JEEP_KEY_ALIAS`: signing alias.
   - `JEEP_KEY_PASSWORD`: key password.
3. Push a tag `v3` on the intended commit to trigger the signed release build; subsequent updates use `v4`, `v5`, etc. The workflow deliberately refuses to publish without all signing secrets.
4. Install the first **release-signed** APK manually from Releases. **It cannot install over the earlier debug-signed APK** because the signing certificate differs. Export logs before uninstalling the debug version. After the one-time transition, updates can install over prior release-signed versions.

**Do not lose the signing keystore:** replacing it will break upgrades over already installed releases. The public repository is needed for unauthenticated update checks. For a private repository, the current updater will not work without a different authenticated distribution method.
