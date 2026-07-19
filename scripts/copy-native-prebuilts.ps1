<#
.SYNOPSIS
    Copies any set of Android native prebuilt libraries (.so + headers) into the
    two locations the Audiophile build system expects, for every ABI in one go.

.DESCRIPTION
    Use this script any time you (re-)build FFmpeg, SoXr, or any other native
    dependency. Point it at the root of the build output and it will:

      1. Detect the source layout (see below).
      2. Copy every *.so it finds into
           app/src/main/cpp/prebuilt/<abi>/lib/      (CMake link-time)
           app/src/main/jniLibs/<abi>/               (APK packaging)
      3. Copy every header file (*.h, *.hpp) it finds into
           app/src/main/cpp/prebuilt/<abi>/include/  (CMake compile-time)

    Supported source layouts (auto-detected):
      A)  <root>/lib/<abi>/lib<name>.so              ffmpeg-android-maker default
          <root>/include/<abi>/<name>/*.h
      B)  <root>/<abi>/lib/lib<name>.so              common cmake-toolchain output
          <root>/<abi>/include/.../*.h
      C)  <root>/<abi>/lib<name>.so                  flat per-ABI layout
          <root>/<abi>/include/.../*.h

    Multiple source roots can be provided so you can install FFmpeg + SoXr
    in a single command:

        .\scripts\copy-native-prebuilts.ps1 `
            -SourceRoots C:\builds\ffmpeg-out, C:\builds\soxr-out

.PARAMETER SourceRoots
    One or more paths to native-library build output directories.
    Each directory is scanned independently.

.PARAMETER Abis
    ABIs to install.  Defaults to the three shipped by app/build.gradle.kts.

.PARAMETER DryRun
    Print what would be copied without actually copying anything.

.EXAMPLE
    # Install FFmpeg only
    .\scripts\copy-native-prebuilts.ps1 -SourceRoots C:\src\ffmpeg-android-maker\output

.EXAMPLE
    # Install FFmpeg + SoXr together
    .\scripts\copy-native-prebuilts.ps1 `
        -SourceRoots C:\src\ffmpeg-android-maker\output, C:\src\soxr-android\output

.EXAMPLE
    # Preview what would happen (no files written)
    .\scripts\copy-native-prebuilts.ps1 -SourceRoots C:\src\ffmpeg-android-maker\output -DryRun
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$SourceRoots,

    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64'),

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# ── Resolve project-relative destination roots ────────────────────────────────
$repoRoot       = Resolve-Path (Join-Path $PSScriptRoot '..')
$prebuiltRoot   = Join-Path $repoRoot 'app\src\main\cpp\prebuilt'
$jniLibsRoot    = Join-Path $repoRoot 'app\src\main\jniLibs'

$dryRunLabel    = if ($DryRun) { ' [DRY-RUN]' } else { '' }
$totalCopied    = 0

# ── Helper: copy one file (or skip in dry-run mode) ──────────────────────────
function Copy-Prebuilt {
    param([string]$Src, [string]$Dst)
    $leafName = Split-Path $Src -Leaf
    if ($DryRun) {
        Write-Host "  WOULD COPY  $leafName  →  $Dst" -ForegroundColor DarkCyan
    } else {
        New-Item -ItemType Directory -Force -Path $Dst | Out-Null
        Copy-Item $Src $Dst -Force
        Write-Host "  COPIED  $leafName  →  $Dst" -ForegroundColor Green
    }
    $script:totalCopied++
}

# ── Helper: recursively copy a headers tree ──────────────────────────────────
function Copy-Headers {
    param([string]$SrcDir, [string]$DstDir)
    $headers = Get-ChildItem -Recurse -File -Include '*.h','*.hpp' $SrcDir -ErrorAction SilentlyContinue
    foreach ($h in $headers) {
        # Preserve sub-directory structure relative to SrcDir
        $rel    = $h.FullName.Substring($SrcDir.Length).TrimStart('\','/')
        $target = Join-Path $DstDir $rel
        $targetDir = Split-Path $target -Parent
        if ($DryRun) {
            Write-Host "  WOULD COPY  $($h.Name)  →  $targetDir" -ForegroundColor DarkCyan
        } else {
            New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
            Copy-Item $h.FullName $target -Force
        }
        $script:totalCopied++
    }
    if ($headers.Count -gt 0) {
        Write-Host "  $($headers.Count) header(s) → $DstDir" -ForegroundColor Green
    }
}

# ── Helper: detect layout and return (libDir, includeDir) for a given ABI ────
function Resolve-AbiPaths {
    param([string]$Root, [string]$Abi)

    # Layout A: <root>/lib/<abi>/  + <root>/include/<abi>/
    $tryLibA = Join-Path $Root "lib\$Abi"
    if (Test-Path $tryLibA) {
        $tryIncA = Join-Path $Root "include\$Abi"
        $incA    = if (Test-Path $tryIncA) { $tryIncA } else { $null }
        # Some ffmpeg-android-maker builds put includes without an ABI sub-dir
        if (-not $incA) {
            $tryIncA2 = Join-Path $Root 'include'
            $incA     = if (Test-Path $tryIncA2) { $tryIncA2 } else { $null }
        }
        return [PSCustomObject]@{ LibDir = $tryLibA; IncludeDir = $incA; Layout = 'A' }
    }

    # Layout B: <root>/<abi>/lib/  + <root>/<abi>/include/
    $tryLibB = Join-Path $Root "$Abi\lib"
    if (Test-Path $tryLibB) {
        $tryIncB = Join-Path $Root "$Abi\include"
        $incB    = if (Test-Path $tryIncB) { $tryIncB } else { $null }
        return [PSCustomObject]@{ LibDir = $tryLibB; IncludeDir = $incB; Layout = 'B' }
    }

    # Layout C: <root>/<abi>/  (flat, .so files directly inside)
    $tryLibC = Join-Path $Root $Abi
    if (Test-Path $tryLibC) {
        $soCount = (Get-ChildItem -File "$tryLibC\*.so" -ErrorAction SilentlyContinue).Count
        if ($soCount -gt 0) {
            $tryIncC = Join-Path $tryLibC 'include'
            $incC    = if (Test-Path $tryIncC) { $tryIncC } else { $null }
            return [PSCustomObject]@{ LibDir = $tryLibC; IncludeDir = $incC; Layout = 'C' }
        }
    }

    return $null   # ABI not found in this source root — will be skipped
}

# ═════════════════════════════════════════════════════════════════════════════
# Main loop — iterate over every source root provided
# ═════════════════════════════════════════════════════════════════════════════
foreach ($sourceRoot in $SourceRoots) {

    if (-not (Test-Path $sourceRoot)) {
        Write-Warning "Source root not found, skipping: $sourceRoot"
        continue
    }

    $sourceRoot = Resolve-Path $sourceRoot
    Write-Host "`n$dryRunLabel Processing source root: $sourceRoot" -ForegroundColor Cyan

    foreach ($abi in $Abis) {

        $paths = Resolve-AbiPaths -Root $sourceRoot -Abi $abi

        if ($null -eq $paths) {
            Write-Host "  [$abi] No libraries found — skipping." -ForegroundColor Yellow
            continue
        }

        Write-Host "  [$abi] Layout $($paths.Layout) detected." -ForegroundColor White

        # Destination directories
        $dstLib     = Join-Path $prebuiltRoot "$abi\lib"
        $dstInclude = Join-Path $prebuiltRoot "$abi\include"
        $dstJni     = Join-Path $jniLibsRoot  $abi

        # ── Copy shared libraries (.so) ───────────────────────────────────────
        $soFiles = Get-ChildItem -File "$($paths.LibDir)\*.so" -ErrorAction SilentlyContinue
        if ($soFiles.Count -eq 0) {
            Write-Host "  [$abi] No .so files found in $($paths.LibDir) — skipping." -ForegroundColor Yellow
            continue
        }

        foreach ($so in $soFiles) {
            Copy-Prebuilt -Src $so.FullName -Dst $dstLib
            Copy-Prebuilt -Src $so.FullName -Dst $dstJni
        }

        # ── Copy headers ──────────────────────────────────────────────────────
        if ($paths.IncludeDir) {
            Copy-Headers -SrcDir $paths.IncludeDir -DstDir $dstInclude
        } else {
            Write-Host "  [$abi] No include directory found — headers skipped." -ForegroundColor Yellow
        }

        Write-Host "  [$abi] Done ($($soFiles.Count) .so file(s))." -ForegroundColor Green
    }
}

# ── Summary ───────────────────────────────────────────────────────────────────
if ($DryRun) {
    Write-Host "`n[DRY-RUN] Would have processed $totalCopied file operation(s)." -ForegroundColor DarkCyan
    Write-Host "Re-run without -DryRun to apply." -ForegroundColor DarkCyan
} else {
    Write-Host "`nAll done — $totalCopied file operation(s) completed." -ForegroundColor Cyan
    Write-Host "Next step:  .\gradlew :app:clean :app:assembleDebug" -ForegroundColor Cyan
}

