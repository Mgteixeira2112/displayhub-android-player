# Android video playback acceptance

This change avoids swapping a video element to a local URL until its cached file exists. A remote video that has not yet been cached must be served by the normal WebView network stack instead of receiving a synthetic HTTP 503.

Before treating playback as fixed, publish a signed APK with a higher versionCode, update an existing Android TV installation without clearing its data, then verify preparation feedback, playback, a second complete playlist cycle and two offline cycles on the actual device. Check video download failures separately from unsupported codecs. The frontend preparation gate is a separate known issue; this change alone does not guarantee it appears.
