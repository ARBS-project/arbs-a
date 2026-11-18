# ARBS Moonlight 빌드 — 포터블 툴체인(D:\AI\toolchain)만 사용. 환경변수는 이 세션에만 적용.
# 사용: powershell -NoProfile -ExecutionPolicy Bypass -File android\arbs-moonlight\build.ps1 [-Tasks "assembleNonRootDebug"]
param([string[]]$Tasks = @('testNonRootDebugUnitTest', 'lintNonRootDebug', 'assembleNonRootDebug'))
$ErrorActionPreference = 'Stop'
$tc  = 'D:\AI\toolchain'
$jdk = (Get-ChildItem "$tc\jdk17" -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk
$env:ANDROID_HOME = "$tc\android-sdk"; $env:ANDROID_SDK_ROOT = "$tc\android-sdk"
$env:ANDROID_USER_HOME = "$tc\android-home"
$env:GRADLE_USER_HOME  = "$tc\gradle-home"
$env:PATH = "$jdk\bin;$env:PATH"
Set-Location $PSScriptRoot
& "$PSScriptRoot\gradlew.bat" --no-daemon --console=plain @Tasks
exit $LASTEXITCODE
