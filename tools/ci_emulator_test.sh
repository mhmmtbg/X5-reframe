#!/bin/bash
# Emülatörde uçtan uca test: örnek klibi yükle, testleri çalıştır, sonuçları topla.
set -x
adb wait-for-device
adb push media/clip.insv /data/local/tmp/clip.insv
adb push media/clip.lrv /data/local/tmp/clip.lrv
adb shell chmod 644 /data/local/tmp/clip.insv /data/local/tmp/clip.lrv
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace > out/test.log 2>&1
echo "exit=$?" >> out/test.log
adb logcat -d -s ExportTest:* PlaybackTest:* StitchTest:* ScreenshotTest:* FlatMediaTest:* PerfTest:* OscClientTest:* CameraLister:* X5Player:* TrackDecoder:* MediaCodec:E CCodec:E AndroidRuntime:E > out/logcat.txt || true
latest=$(adb shell ls -t /sdcard/Movies/X5Reframe/ 2>/dev/null | head -1 | tr -d '\r')
[ -n "$latest" ] && adb pull "/sdcard/Movies/X5Reframe/$latest" out/export.mp4 || true
adb pull /sdcard/Download/x5test out/files || true
find out/files -name "*.mp4" -delete 2>/dev/null || true
