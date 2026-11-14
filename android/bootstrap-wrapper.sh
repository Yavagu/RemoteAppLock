#!/usr/bin/env sh
# Downloads the official Gradle 8.13 wrapper JAR and checks its SHA-256.
set -eu
cd "$(dirname "$0")"
url="https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
checksum_url="https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256"
jar="gradle/wrapper/gradle-wrapper.jar"
checksum="gradle/wrapper/gradle-wrapper.jar.sha256"
if command -v curl >/dev/null 2>&1; then
  curl -fLsS "$url" -o "$jar"
  curl -fLsS "$checksum_url" -o "$checksum"
elif command -v wget >/dev/null 2>&1; then
  wget -q "$url" -O "$jar"
  wget -q "$checksum_url" -O "$checksum"
else
  echo 'Install curl or wget.' >&2; exit 1
fi
expected=$(awk '{print $1}' "$checksum")
if command -v sha256sum >/dev/null 2>&1; then
  actual=$(sha256sum "$jar" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  actual=$(shasum -a 256 "$jar" | awk '{print $1}')
else
  echo 'Install sha256sum or shasum.' >&2; rm -f "$jar" "$checksum"; exit 1
fi
rm -f "$checksum"
if [ "$expected" != "$actual" ]; then
  echo 'Wrapper checksum mismatch.' >&2; rm -f "$jar"; exit 1
fi
echo 'Verified official Gradle 8.13 wrapper JAR. Open android/ in Android Studio.'
