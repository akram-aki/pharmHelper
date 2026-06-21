# Setup — one-time steps before your first build

The Android project is scaffolded. Three one-time setup blocks must happen before
the cloud build pipeline can deliver an APK to your phone:

1. **GitHub** — create a repo and push this code.
2. **Firebase** — create a project, enable App Distribution, generate a service account.
3. **Your phone** — install the Firebase App Tester app, set up wireless ADB.

Plan on ~30 minutes total. After this, the loop is push → wait ~4 min → install
notification on phone.

---

## 1. GitHub

1. Create a new GitHub repo (private is fine), do not initialise with a README.
2. From this directory on Windows:
   ```powershell
   git init
   git add .
   git commit -m "Initial scaffold"
   git branch -M main
   git remote add origin https://github.com/akram-aki/pharmHelper.git
   git push -u origin main
   ```
3. The first push triggers the GitHub Action — it will fail on the Firebase
   distribute step because secrets are not set yet. That's expected. The APK
   will still build and you can download it from the Actions tab → run →
   Artifacts → `app-debug`.

---

## 2. Firebase

### 2a. Create the project

1. Go to https://console.firebase.google.com → **Add project** → name it
   `pharmaApp` (or whatever). Free Spark plan is enough.
2. Once the project exists, click **Add app** → Android.
3. Android package name: `fr.fbing.boxdetector` (must match exactly — it's the
   `applicationId` in `app/build.gradle.kts`).
4. App nickname: anything.
5. Download `google-services.json` when prompted.

### 2b. Enable App Distribution

1. In the Firebase console left sidebar → **Release & Monitor → App Distribution**.
2. Click **Get started**.
3. Go to the **Testers & Groups** tab → create a group named exactly `devs`
   (lowercase, matches `groups = "devs"` in `app/build.gradle.kts`).
4. Add yourself as a tester (your email).

### 2c. Generate a service account for CI

1. Firebase console → ⚙ **Project settings** → **Service accounts** tab.
2. Click **Generate new private key** → confirm → a JSON file downloads.
3. Keep this file safe; you'll paste it into GitHub Secrets in the next step.
   It grants full Firebase admin to your project — never commit it.

### 2d. Add GitHub Secrets

In your GitHub repo → **Settings → Secrets and variables → Actions →
New repository secret**. Create three secrets:

| Name                       | Value                                                                                                                                                                                 |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GOOGLE_SERVICES_JSON`     | Paste the entire contents of `google-services.json` (the file you downloaded in step 2a).                                                                                             |
| `FIREBASE_SERVICE_ACCOUNT` | Paste the entire contents of the service account JSON from step 2c.                                                                                                                   |
| `FIREBASE_APP_ID`          | From Firebase → Project settings → General → your apps → "App ID" (format `1:1234567890:android:abcdef`). Not currently used by the workflow but useful if you switch upload methods. |

Push a commit (or click **Re-run all jobs** on the failed run) — the workflow
should now go green and the APK should appear in App Distribution.

## test test

## 3. Your phone

### 3a. Install Firebase App Tester

1. On the phone, open Play Store → install **App Tester** by Google LLC.
2. Open it → sign in with the same Google account you added as a tester in 2b.
3. The `pharmaApp` debug app should show up. When CI completes a build you'll
   get a push notification; tap → **Install**.
4. First install will prompt to allow installs from unknown sources for App
   Tester. Allow it once.

### 3b. Wireless ADB (Android 11+) — for live logs

You only need this when you want to see Logcat output (inference time,
output tensor shape, GPU delegate status). Not required just to run the app.

**Install adb on Windows** (one time):

1. Download platform-tools from
   https://developer.android.com/tools/releases/platform-tools (~10 MB).
2. Unzip somewhere stable, e.g. `C:\platform-tools`.
3. Add that folder to your PATH (Windows Settings → search "environment
   variables" → Edit Path → Add `C:\platform-tools`).
4. Open a new PowerShell window → `adb version` should print something.

**Pair the phone** (one time):

1. Phone: Settings → **Developer options** (enable by tapping Build number 7
   times under About phone if not visible) → **Wireless debugging** → On →
   tap "Pair device with pairing code".
2. Phone shows an IP:port (call it `PAIR_HOST:PAIR_PORT`) and a 6-digit code.
3. Laptop: `adb pair PAIR_HOST:PAIR_PORT` → paste code when asked.

**Connect** (every time you want to debug):

1. Phone: Wireless debugging screen shows a _different_ IP:port at the top
   (call it `CONN_HOST:CONN_PORT`).
2. Laptop: `adb connect CONN_HOST:CONN_PORT`.
3. Stream logs: `adb logcat -s BoxDetector` (filters to just our app's logs).
   Tail `:V` for verbose if needed.

---

## 4. Drop in the model

Export your trained YOLO11 checkpoint to TFLite (in Colab or wherever you trained):

```python
from ultralytics import YOLO
model = YOLO("runs/detect/train/weights/best.pt")
model.export(format="tflite", int8=True)   # produces best_saved_model/best_int8.tflite
```

Download `best_int8.tflite`, rename it to `box_detector.tflite`, and put it at:

```
app/src/main/assets/box_detector.tflite
```

Then `git add` it, commit, push. The next CI build will bundle it into the APK.
First run on the phone will print the model's input/output tensor shapes and
dtypes to Logcat (look for `BoxDetector` tag) — share that line with me and
I'll confirm the auto-detect picked the right decode path. For an INT8
YOLO11n the line should look roughly like:

```
Input [1, 640, 640, 3] UINT8  Output [1, 5, 8400] INT8
Format: YOLOv8/v11  candidates=8400  row=5
```

---

## 5. Quick reference — daily loop

```powershell
# Edit code in VS Code, then:
git add -A
git commit -m "tweak threshold"
git push

# Wait ~4 min for GitHub Actions to go green.
# Phone gets a notification — tap App Tester → Install.

# Watch logs while testing:
adb connect <CONN_HOST:CONN_PORT>
adb logcat -s BoxDetector
```

---

## Troubleshooting

**"Default FirebaseApp is not initialized"** at app startup — `google-services.json`
isn't being injected into the APK. Verify the `GOOGLE_SERVICES_JSON` secret is
set in GitHub repo settings and that the CI step "Write google-services.json"
ran without error.

**Build fails at `appDistributionUploadDebug`** — service account JSON is wrong
or the App Distribution API isn't enabled in the linked Google Cloud project.
Re-download the service account from Firebase, paste again into
`FIREBASE_SERVICE_ACCOUNT` secret.

**App crashes at startup with "Cannot find tflite file"** — you haven't dropped
the model into `app/src/main/assets/box_detector.tflite` yet.

**Boxes appear in the wrong place** — coordinate mapping bug. The most likely
culprits are (a) `PreviewView` scale type ≠ `FILL_CENTER`, or (b) the model
outputs normalized [0,1] coords but `BoxDetector` decided they were pixels (or
vice versa). Check the raw output magnitudes in Logcat and adjust the
`isNormalized` heuristic in `BoxDetector.kt`.

**Inference > 200 ms per frame** — GPU delegate fell back to CPU. Check Logcat
for "GPU delegate unavailable" or "Interpreter failed with delegate" messages.
Consider quantizing the model to INT8 or reducing input resolution.
