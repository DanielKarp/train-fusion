# The Android recorder (`android/`)

A port of the iPhone recorder (`../ios`, [docs/recorder.md](../docs/recorder.md)) to Android:
the same three screens, the same recordings (file for file, column for column), the same
online model, the same consent questions, accounts, uploads and settings, against the same
backend ([docs/api.md](../docs/api.md)). Where Android can't do what iOS does, or does it
differently, it's listed under [Differences from the iOS app](#differences-from-the-ios-app).

**Status:** it builds, its tests pass, and its screens render as intended (below), but it
hasn't been run on a phone yet. [First things to check on a phone](#first-things-to-check-on-a-phone)
lists what only a phone can tell.

## Layout

| | |
|---|---|
| `core/` | Plain Kotlin (JVM), no Android: the port of the model (`inference/`: model.json, the DSP, the features, the HMM and the station layer, replay), the recording format (`recording/`: the .m4a writer, gzip CSV, NTP, rides), and the backend client (`backend/`: the API, uploads, Identity Platform's REST API). Runs and is tested on a laptop. |
| `app/` | The Android app: capture (`recording/`), rides, uploads and the account (`store/`), replay (`replay/`), and the screens in Jetpack Compose (`ui/`). |
| `tools/` | Checking the port and the backend client without a phone (below). |
| `bin/android-config` | Writes `config.properties` (gitignored) from terraform, like `just ios-config`. |

How the iOS files map:

| iOS (`ios/TrainRecorder/`) | Android |
|---|---|
| `Inference/Model, DSP, Features, Engine` | `core/…/inference/Model, Dsp, Features, Engine` |
| `Inference/Drivers` (replay) | `core/…/inference/Replay` + `app/…/replay/ReplayPlayer` |
| `Inference/Drivers` (live) | `app/…/recording/LiveInference` |
| `Recorder, AudioCapture, MotionCapture, Clock, GzipWriter` | `app/…/recording/Recorder, AudioCapture (+ AacEncoder), MotionCapture, ClockLog`; `core/…/recording/Files, Ntp, M4aWriter` |
| `RecordingStore` | `core/…/recording/Ride` + `app/…/store/RideStore` |
| `Uploader, Backend` | `core/…/backend/Uploads, Api` + `app/…/store/Uploader` (WorkManager) |
| `Account, Keychain, AppSettings` | `core/…/backend/Identity` + `app/…/store/Account`, `app/…/platform/Platform` |
| `ContentView, BeliefView, HistoryScreen, SettingsScreen, UploadConsent` | `app/…/ui/MainScreen, BeliefView, HistoryScreen, SettingsScreen, Consent` |

As on iOS, the model's port is a re-implementation and shares nothing with the Python code
but `model.json` ([AGENTS.md](../AGENTS.md)): the build copies `../ios/TrainRecorder/model.json`
(written by `just ios-model`) into the app, so both apps always carry the same model.

## Building

Needs a JDK (17 or newer) and the Android SDK (platform 36): Android Studio has both, or
`sdkmanager "platforms;android-36" "build-tools;36.0.0"`.

```sh
bin/android-config           # from android/, once the backend is deployed: config.properties
./gradlew :app:assembleDebug # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug  # onto the phone adb sees
```

Without `config.properties` the app records and replays as usual but can't upload or sign in.
With only the API's host in it (`API_HOST=…`), it uploads and shares Train Measurements
without accounts; signing in also needs the settings below.

### Setting up the backend for Android

Done once, by whoever runs the backend ([docs/deploy.md](../docs/deploy.md), "Android"). The
app's package is `io.github.danielkarp.trainfusion`; each signing key (the debug key on a
laptop, the release key) has a SHA-1 fingerprint (`./gradlew :app:signingReport`).

1. **An API key for the Android app.** The iOS key (`google_apikeys_key.ios`) only works
   from the iOS app. A second one, for Identity Platform from the Android app, in
   `infra/api.tf`:

   ```hcl
   resource "google_apikeys_key" "android" {
     name         = "recorder-android"
     display_name = "Recorder app (Android): Identity Platform"
     restrictions {
       android_key_restrictions {
         dynamic "allowed_applications" {
           for_each = var.android_cert_sha1s
           content {
             package_name     = "io.github.danielkarp.trainfusion"
             sha1_fingerprint = allowed_applications.value
           }
         }
       }
       api_targets { service = "identitytoolkit.googleapis.com" }
       api_targets { service = "securetoken.googleapis.com" }
     }
   }
   output "identity_api_key_android" {
     value     = google_apikeys_key.android.key_string
     sensitive = true
   }
   ```

   with `variable "android_cert_sha1s" { type = list(string) }` and the fingerprints in
   `terraform.tfvars`. The app sends its package and certificate with every Identity
   Platform request (`X-Android-Package`, `X-Android-Cert`), so the key works only from
   builds signed with those keys.
2. **Google sign-in.** In the Google Cloud console (*Google Auth Platform → Clients*), an
   **Android** OAuth client with the package and each SHA-1. The app signs in with the
   system's account chooser (Credential Manager) and asks for an ID token for the **web**
   client that Identity Platform's Google provider already uses: put that client's id in
   `terraform.tfvars` as `google_web_client_id` (or straight into `config.properties` as
   `GOOGLE_WEB_CLIENT_ID`).
3. **Email links** need nothing new: the link lands on the backend's `/auth/email-link`
   page, whose button opens `trainfusion://auth/email-link?…`, which the Android app
   handles as the iOS app does.

Then `bin/android-config` and rebuild.

### Release builds

`./gradlew :app:bundleRelease` (minified). Signed with the key that
`keystore.properties` (gitignored) names (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`); without one, with the debug key, which is for testing only. The release
key's SHA-1 goes into the API key and the Android OAuth client (above). For Google Play,
outside the code: the Data safety form (audio, other sensor data and precise location via
Train Measurements, email and user id when signed in, device identifier; Train
Measurements are *shared* with third parties, recordings only with the developers;
encrypted in transit; deletion in the app), the privacy policy URL, and a web page or
address for account deletion requests (Play requires one besides the in-app deletion).

## Differences from the iOS app

What can't be replicated cleanly, and what the Android app does instead:

- **No Sign in with Apple.** Android has no system support for it, and the web flow would
  need a backend change. Signing in is Google or an emailed link. Someone who made their
  account with Apple on an iPhone can use an email link to the same address (if it's an
  Apple relay address, the link still arrives, through Apple). Deleting such an account
  from Android can't revoke the app's Apple tokens (Apple wants that done; on iOS it is).
- **Sign in with Google** is the system's account chooser (Credential Manager), not a
  browser page, and needs an Android OAuth client and the web client id (above).
- **The device secret goes when the app is uninstalled.** On iOS it's in the Keychain,
  which outlives the app; on Android it's encrypted with a Keystore key in the app's own
  storage (never backed up, like iOS's "this device only"), and uninstalling erases it.
  After a reinstall, what this phone shared without an account can no longer be deleted
  from the app (nor linked to an account). Google's Block Store could keep it across
  reinstalls; not done, as it can sync the secret off the phone.
- **Recording in the background needs a notification.** Android only lets an app use the
  microphone and the sensors in the background from a foreground service, which must show a
  notification ("Recording a ride", with a timer and a Stop button) for as long as it
  records. The app also holds a wake lock so the sensors keep delivering with the screen
  off. Some phones' battery savers kill apps regardless; such a recording ends as one "not
  stopped properly".
- **Swiping the app away doesn't stop the recording** (on iOS it stops and saves it): the
  notification shows it's still going and stops it. A recording stopped from the
  notification asks about uploading the next time the app is opened.
- **No permission question for the barometer.** iOS asks for Motion & Fitness access;
  Android sensors need none. Android 13+ asks to show notifications (the recording one).
- **The microphone.** iOS's `.measurement` mode turns off gain control and voice
  processing. Android's equivalent is the `UNPROCESSED` source, which not every phone has;
  without it the app uses `VOICE_RECOGNITION`, which should have no gain control or noise
  suppression but isn't guaranteed to. `meta.json` says which (`audio.session_mode`).
  Phones differ in their microphones and filtering anyway: the model, fitted on one
  iPhone, may not hear the motor tone or the chimes the same way.
- **Calls and other apps taking the microphone.** iOS stops the recording's audio and
  starts a new segment afterwards. Android keeps recording and hands the app silence
  instead; the silence is kept (time stays continuous) and listed in `meta.json`
  (`audio.silenced`). A new segment starts only if capture actually fails.
- **The phone's gyroscope may be quieter than an iPhone's.** The model tells a resting
  phone by the gyroscope's level, fitted on an iPhone whose raw gyroscope carries a bias of
  about 0.008 rad/s. The app records Android's *uncalibrated* gyroscope (bias in, the
  nearest to iOS's raw one) where the phone has one, but a quieter gyroscope can still read
  as "held" while lying still, and then the along-track acceleration and the resting phone's
  wheel lines aren't used. Only a recording from an Android phone will tell, and fitting on
  it is the fix (the author expects to retrain per phone anyway).
- **Sample rates.** Android treats the 100 Hz asked for as a hint: phones may deliver
  faster or unevenly. Everything delivered is recorded. The features interpolate onto a
  100 Hz grid as on iOS, without a low-pass first, so a faster stream can alias vibration
  above 50 Hz into the wheel lines and vibration levels; worth a look in the first Android
  recordings (`meta.json`'s `motion.*_samples` against the duration).
- **Device motion is assembled, not fused by the OS in one sample.** iOS's
  `CMDeviceMotion` gives attitude, bias-free rotation, gravity, user acceleration and the
  calibrated field together. Android has a sensor for each; each row of
  `device_motion.csv.gz` is a rotation-vector sample with the latest of the others beside
  it, converted to iOS's frame and signs (below).
- **Uploads** run in WorkManager instead of a background URL session. They also finish
  with the app closed, wait for a network, and retry; an upload over today's limit is
  retried at the time the server gives, without waiting for the app to be opened. Force-
  stopping the app (in Settings) cancels them until it's next opened.
- **Recordings on a computer.** iOS shows them in the Files app and in Finder. On Android
  11+ they're in `Android/data/io.github.danielkarp.trainfusion/files/recordings/`, which
  a computer sees over USB but other apps on the phone can't; on Android 10, where other
  apps could have read that folder, they're in the app's internal storage instead.
- **Nothing is backed up.** iOS includes the app's Documents (the recordings) in iCloud
  backups; the Android app opts out of backups and device transfer entirely, so recordings
  stay on the phone unless uploaded.
- **The clock.** iOS stamps samples with `mach_absolute_time`, which stops while the phone
  sleeps; Android's uptime is `elapsedRealtimeNanos`, which doesn't. Within a recording
  this changes nothing (the phone is kept awake), and `clock.csv` ties either to true time
  the same way. NTP still asks `time.apple.com`, so both apps' recordings say the same.
- **Configuration.** Uploads need only the API's host; signing in also needs the Android
  API key (on iOS one setting covers both). So recordings can be shared before sign-in is
  set up.
- **Smaller things:** the testing launch arguments are intent extras
  (`adb shell am start … --es autoreplay <id> --es replayspeed max --ez dump true`); a
  privacy manifest has no Android equivalent (Play's Data safety form is filled in on the
  console); `app_version` is `0.1-android (1)`, so shared data says which app made it.

## A recording made on Android

The same files and columns as [docs/recorder.md](../docs/recorder.md), converted as they're
written so the Python pipeline reads them unchanged:

- **Acceleration** (`accel`, and `gravity` / `user_accel` in `device_motion`): Android gives
  m/s² with a phone lying face up reading z = +9.81; iOS gives g with z = −1. Written as
  −a / 9.80665.
- **Gyroscope and magnetometer** (`gyro`, `mag`): Android's uncalibrated sensors (bias in),
  as iOS's raw ones; the calibrated ones go into `device_motion` (`rot_*`, `mag_*`).
  `mag_accuracy` maps Android's unreliable/low/medium/high to −1/0/1/2.
- **Barometer**: hPa to kPa; `relative_altitude_m` is the standard atmosphere's, relative to
  the first reading (Android has no such reading of its own). About 1 Hz.
- **Attitude** (`device_motion` `q*`): Android's rotation vector (phone → east, north, up),
  rotated into iOS's `xMagneticNorthZVertical` (x magnetic north, y west, z up); phone →
  world, which the pipeline's "which sense sends gravity down" check accepts either way.
  `heading_deg` is the azimuth of the phone's y axis. Without a magnetometer, the game
  rotation vector and `xArbitraryZVertical`, as iOS.
- **Audio**: AAC-LC in .m4a, 48 kHz mono, 96 kbit/s, written by `core`'s `M4aWriter`
  (Android's MediaMuxer can't record the encoder's priming). Its edit list skips the
  encoder's priming, measured once per phone by putting a noise burst through the encoder
  and decoder, so a decoder that honours it (ffmpeg) yields frame 0 first, as with iOS's
  2112. `audio.segments[].encoder_delay_frames` and `encoder` record it. `audio_timing`'s
  times come from AudioRecord's own timestamps (BOOTTIME, when the frame was captured, so
  `input_latency_s` is 0); `audio.timestamps` counts any buffers whose time had to be taken
  from when they arrived instead.
- **meta.json** has `platform: "android"`, `device_model`, `device_manufacturer`, `device`,
  `android_version` and `android_sdk` instead of `ios_version`; `motion.sensors` (which
  Android sensor each file came from: type, name, vendor, resolution, range, minimum delay)
  and `motion.timestamp_clocks` (each sensor's timestamp clock, should be `boottime`).
  Everything else as on iOS.

## Tests

```sh
./gradlew :core:test :app:testDebugUnitTest
```

- `core`: the features and engine on made-up sensor data; the recording format (gzip
  files cut short, rides from meta.json and NTP, recording ids, sizes); the .m4a writer
  checked with ffmpeg (frame 0 first; skipped without ffmpeg).
- **The backend client against the real backend code**, with its in-memory fakes and a
  fake bucket that takes the signed PUTs as GCS does (`tools/fake_backend.py`; needs
  `pip install fastapi uvicorn`): sharing, an upload cut off half way and resumed, sign-in
  registration, linking, deletion requests, account deletion.

  ```sh
  python android/tools/fake_backend.py 8765 &
  TRAIN_FUSION_TEST_BACKEND=http://127.0.0.1:8765 ./gradlew :core:test
  ```
- `app`: the Measure cards, the consent questions, History and Settings rendered on the JVM
  (Robolectric) from made-up beliefs and rides into `app/build/screenshots/`.

### Checking the model's port

- `tools/check-port-synthetic`: a made-up recording through the Kotlin features and
  phone-sensor-fusion's, compared with `app.compare`'s tolerances. Needs no recordings.
- On real recordings, on a laptop: `./gradlew :core:replay --args="<recording dir>
  <out>/<id>.jsonl"` writes the same dump as the app's replay, then
  `python -m app.compare <out>/*.jsonl` (in `phone-sensor-fusion/`, after
  `python -m app.export`) compares features, speeds, legs and stations as for the iOS app.
- On a phone: `tools/replay-on-device` (the Android `bin/ios-sim-replay`).

## First things to check on a phone

None of this could be tried without one:

1. A recording starts, keeps going with the screen off and the app in the background, and
   stops from the app and from the notification, leaving every file (`gzip -t`, the m4a
   plays, `meta.json` complete).
2. The sensors: `motion.timestamp_clocks` all `boottime`; sample counts / duration near
   100 Hz (or what the phone delivers); `accel` z ≈ −1 lying face up; `device_motion`'s
   heading against a compass app.
3. The audio: `audio.session_mode`; `encoder_delay_frames` measured (not null);
   `audio.timestamps.arrival` 0; and against the IMU, e.g. tapping the phone shows in both
   at the same time.
4. The model while riding (status card, stops, stations), and replay of that recording.
5. Uploads: with a recording, in the background, with the network off and on again.
6. Sign-in, once the backend is set up for Android (Google, email link), linking, deleting
   the account.
