<#
.SYNOPSIS
  Installs FFmpeg prebuilt shared libraries + headers into the two locations
  the Audiophile native build expects.

.DESCRIPTION
  The CMake build under app/src/main/cpp/ links against FFmpeg .so files at
  app/src/main/cpp/prebuilt/<abi>/lib/, and AGP packages them from
  app/src/main/jniLibs/<abi>/. This script copies an ffmpeg-android-maker
  output tree into both places for every ABI shipped by the app.

.PARAMETER FfmpegAndroidMakerOutput
  Path to the root of an ffmpeg-android-maker output directory. The script
  expects <root>/lib/<abi>/*.so and <root>/include/<abi>/<libname>/*.h.

.PARAMETER Abis
  ABIs to install. Defaults to the three shipped by app/build.gradle.kts.

.EXAMPLE
  ./scripts/install-ffmpeg-prebuilt.ps1 -FfmpegAndroidMakerOutput C:\src\ffmpeg-android-maker\output
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$FfmpegAndroidMakerOutput,

    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64')
)

$ErrorActionPreference = 'Stop'

$repoRoot   = Resolve-Path (Join-Path $PSScriptRoot '..')
$prebuiltRoot = Join-Path $repoRoot 'app\src\main\cpp\prebuilt'
$jniLibsRoot  = Join-Path $repoRoot 'app\src\main\jniLibs'
$libs = @('avcodec', 'avformat', 'avutil', 'swresample')

if (-not (Test-Path $FfmpegAndroidMakerOutput)) {
    throw "FFmpeg output directory not found: $FfmpegAndroidMakerOutput"
}

foreach ($abi in $Abis) {
    $srcLibDir     = Join-Path $FfmpegAndroidMakerOutput "lib\$abi"
    $srcIncludeDir = Join-Path $FfmpegAndroidMakerOutput "include\$abi"

    if (-not (Test-Path $srcLibDir)) {
        Write-Warning "Skipping $abi — no lib dir at $srcLibDir"
        continue
    }

    $dstPrebuiltLib     = Join-Path $prebuiltRoot "$abi\lib"
    $dstPrebuiltInclude = Join-Path $prebuiltRoot "$abi\include"
    $dstJniLibs         = Join-Path $jniLibsRoot "$abi"

    New-Item -ItemType Directory -Force -Path $dstPrebuiltLib, $dstPrebuiltInclude, $dstJniLibs | Out-Null

    foreach ($lib in $libs) {
        $so = Join-Path $srcLibDir "lib$lib.so"
        if (-not (Test-Path $so)) {
            throw "Missing $so — rebuild ffmpeg-android-maker with decoder enabled."
        }
        Copy-Item $so $dstPrebuiltLib -Force
        Copy-Item $so $dstJniLibs     -Force
    }

    if (Test-Path $srcIncludeDir) {
        Copy-Item -Recurse -Force "$srcIncludeDir\*" $dstPrebuiltInclude
    } else {
        Write-Warning "No include dir for $abi at $srcIncludeDir — CMake will fail to compile."
    }

    Write-Host "[$abi] installed into prebuilt/ and jniLibs/" -ForegroundColor Green
}

Write-Host "`nDone. Now run:  .\gradlew :app:clean :app:assembleDebug" -ForegroundColor Cyan

