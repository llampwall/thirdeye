# thirdeye

Standalone Android app for live Ray-Ban Meta POV preview and a single-frame vision request. The Android app uses Meta's published Wearables DAT artifacts from Maven Central. The Meta sample repository is a reference only; it is not a build or runtime dependency.

## Project

- `android/`: native Android app, DAT session and HEVC preview, PixelCopy and HTTP client
- `backend/`: standard-library mock vision endpoint
- `captures/latest.jpg`: newest JPEG received by the backend (generated, gitignored)

## Build and run

Prerequisites: Android SDK 36, JDK 17 or newer, ADB, Meta AI app, and Developer Mode enabled for the linked glasses. On the Z Flip 6, the app's first run requires Bluetooth permission, Meta AI app registration, and DAT camera permission.

From `android/` in PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.thirdeye.app/.MainActivity
```

In the app, tap **Connect my glasses** if registration is needed, then **Start session**, then **Start preview**. Accept the Meta AI camera permission request. A second tap on **Start preview** may be needed after returning from Meta AI. The status should read `Session: started` and `Stream: streaming · frames received` with a live POV image.

The app directly uses `com.meta.wearable:mwdat-core:1.0.0` and `com.meta.wearable:mwdat-camera:1.0.0`. Developer Mode uses application ID and client token placeholders of `0`. The debug build permits local cleartext HTTP for the mock backend.

## Mock vision endpoint

From the project root in PowerShell, start the server and connect the phone over USB debugging:

```powershell
python -B -u backend\mock_vision_server.py
adb reverse tcp:8765 tcp:8765
```

Run `adb reverse tcp:8765 tcp:8765` again if the USB device reconnects. The backend parser check is `python -B backend\test_mock_vision_server.py`.

Tap **Analyze current view** while preview is streaming. The default Android endpoint is `http://127.0.0.1:8765/analyze`; the dialog lets you change the endpoint and prompt. Only one request can run at a time. The app copies the current preview surface, JPEG-encodes it, sends it, and displays the answer plus copy, JPEG, HTTP, backend model, and total timings. The server logs the prompt, byte count, dimensions, and receipt time, then writes `captures/latest.jpg`.

The replaceable backend contract is:

```text
POST /analyze
Content-Type: application/json
{"prompt":"Describe what you see.","image_base64":"<JPEG bytes as base64>","width":1485,"height":2640}

HTTP 200
Content-Type: application/json
{"answer":"MOCK: image received","model_ms":12.0}
```

`answer` is required. `model_ms` is optional. The server parses image dimensions from the JPEG; `width` and `height` in the request describe the preview bitmap. A future VLM server only needs to implement this HTTP contract. No frame queue is retained; the app copies the displayed frame only when the button is pressed.
