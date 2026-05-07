#!/usr/bin/env bash
set -e
ASR="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BT=$ASR/build-tools/34.0.0
PLATFORM=$ASR/platforms/android-29/android.jar
PROJ="$(cd "$(dirname "$0")" && pwd)"
NANO=$PROJ/lib/nanohttpd-2.3.1.jar
JMDNS=$PROJ/lib/jmdns-3.4.1.jar
OUT=$PROJ/build

rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex

echo "[1/5] javac"
javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath $PLATFORM \
    -cp $NANO:$JMDNS \
    -d $OUT/classes $(find $PROJ/src -name "*.java")

echo "[2/5] d8 (classes + libs -> classes.dex)"
$BT/d8 --release --output $OUT/dex --lib $PLATFORM \
    $(find $OUT/classes -name "*.class") $NANO $JMDNS

echo "[3/5] aapt2 link (manifest, no resources)"
$BT/aapt2 link \
    --manifest $PROJ/AndroidManifest.xml \
    -I $PLATFORM \
    --min-sdk-version 29 --target-sdk-version 29 \
    -o $OUT/base.apk

echo "[4/5] inject classes.dex + assets/"
cp $OUT/base.apk $OUT/unsigned.apk
( cd $OUT/dex && zip -j $OUT/unsigned.apk classes.dex )
( cd $PROJ && zip -r $OUT/unsigned.apk assets/ -x "*.DS_Store" )

echo "[5/5] zipalign + apksigner"
$BT/zipalign -p -f 4 $OUT/unsigned.apk $OUT/aligned.apk

KS=$PROJ/debug.keystore
if [ ! -f $KS ]; then
    keytool -genkeypair -v -keystore $KS -storepass android \
        -alias androiddebugkey -keypass android \
        -dname "CN=Bridge,O=Test,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
$BT/apksigner sign --ks $KS --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android \
    --out $OUT/bridge.apk $OUT/aligned.apk

ls -la $OUT/bridge.apk
echo "DONE"
