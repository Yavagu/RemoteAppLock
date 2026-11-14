# Downloads the official Gradle 8.13 wrapper JAR and verifies the publisher's SHA-256.
# This is necessary because the source ZIP does not bundle third-party binaries.
$ErrorActionPreference = 'Stop'
$base = Split-Path -Parent $MyInvocation.MyCommand.Path
$dest = Join-Path $base 'gradle\wrapper\gradle-wrapper.jar'
$url = 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar'
$checksumUrl = 'https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256'
Write-Host 'Downloading official Gradle 8.13 wrapper JAR...'
Invoke-WebRequest -Uri $url -OutFile $dest
try {
    $checksumText = (New-Object Net.WebClient).DownloadString($checksumUrl).Trim()
    $expected = ($checksumText -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -Path $dest -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "Checksum mismatch. Expected $expected; got $actual" }
} catch {
    Remove-Item -Path $dest -ErrorAction SilentlyContinue
    throw
}
Write-Host 'Verified. You can now open the android folder in Android Studio.'
