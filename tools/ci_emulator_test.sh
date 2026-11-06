#!/bin/bash
# Emülatörde uçtan uca test: örnek klibi yükle, testleri çalıştır, sonuçları topla.
set -x
adb wait-for-device
adb shell getprop ro.build.version.release
adb push media/clip.insv /data/local/tmp/clip.insv
adb shell chmod 644 /data/local/tmp/clip.insv
gradle :app:connectedDebugAndroidTest --no-daemon --stacktrace > out/test.log 2>&1
echo "exit=$?" >> out/test.log
adb pull /sdcard/Android/data/com.mhmmtbg.x5reframe/files/report.txt out/report.txt || true
adb pull /sdcard/Android/data/com.mhmmtbg.x5reframe/files/export.mp4 out/export.mp4 || true
adb logcat -d -s ExportTest:* MediaCodec:E CCodec:E AndroidRuntime:E > out/logcat.txt || true
