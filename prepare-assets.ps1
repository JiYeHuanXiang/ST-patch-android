# prepare-assets.ps1 - Prepare Android assets directory
# Copies SillyTavern source + node_modules to Android/app/src/main/assets/sillytavern/
#
# Usage:  powershell -ExecutionPolicy Bypass -File Android\prepare-assets.ps1
#
# Node.js arm64 binary must be manually placed at:
#   Android\app\src\main\assets\sillytavern\node-bin\node

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..")
$AssetsDir = Join-Path $ScriptDir "app\src\main\assets\sillytavern"

Write-Host "============================================================"
Write-Host " Prepare Android assets directory"
Write-Host " Project root : $ProjectRoot"
Write-Host " Assets dir   : $AssetsDir"
Write-Host "============================================================"

# 1. Create directory structure
Write-Host ""
Write-Host "[1/4] Creating directory structure..."
New-Item -ItemType Directory -Path "$AssetsDir\node-bin" -Force | Out-Null
New-Item -ItemType Directory -Path "$AssetsDir\app" -Force | Out-Null
Write-Host "  Done"

# 2. Copy SillyTavern core files
Write-Host ""
Write-Host "[2/4] Copying SillyTavern source..."

$coreFiles = @(
    "server.js",
    "package.json",
    "package-lock.json",
    "webpack.config.js",
    "plugins.js",
    "post-install.js",
    "jsconfig.json",
    "index.d.ts",
    "LICENSE"
)
# NOT included: config.yaml (user config with local paths), log.txt (runtime log)

foreach ($f in $coreFiles) {
    $src = Join-Path $ProjectRoot $f
    if (Test-Path $src) {
        Copy-Item $src "$AssetsDir\app\" -Force
        Write-Host "  + $f"
    }
}

$coreDirs = @("src", "public", "default")
# NOT included: data/ (user data - chars, chats, 12GB+), backups/, log.txt

foreach ($d in $coreDirs) {
    $src = Join-Path $ProjectRoot $d
    if (Test-Path $src) {
        $dest = Join-Path $AssetsDir "app\$d"
        if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
        Copy-Item $src $dest -Recurse -Force
        $sz = [math]::Round((Get-ChildItem $dest -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
        Write-Host "  + $d/ ($sz MB)"
    }
}

# 3. Copy node_modules
Write-Host ""
Write-Host "[3/4] Copying node_modules (this may take a few minutes)..."

$nmSrc = Join-Path $ProjectRoot "node_modules"
$nmDest = Join-Path $AssetsDir "app\node_modules"

if (Test-Path $nmSrc) {
    if (Test-Path $nmDest) { Remove-Item $nmDest -Recurse -Force }

    Copy-Item $nmSrc $nmDest -Recurse -Force
    Write-Host "  Copy done, cleaning up..."

    # Remove @types (not needed at runtime)
    $typesDir = Join-Path $nmDest "@types"
    if (Test-Path $typesDir) { Remove-Item $typesDir -Recurse -Force }

    # Remove unneeded files by extension
    foreach ($ext in @("*.d.ts", "*.map")) {
        Get-ChildItem $nmDest -Recurse -File -Filter $ext -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
    }

    # Remove unneeded directories (but NOT 'doc' — some packages like 'yaml' need it)
    foreach ($dirName in @(".github", "test", "tests", "__tests__", "docs", "benchmark")) {
        Get-ChildItem $nmDest -Recurse -Directory -Filter $dirName -ErrorAction SilentlyContinue |
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    }

    # Remove unneeded files by name pattern
    foreach ($pattern in @("CHANGELOG*", "HISTORY*", "README*", "LICENSE*", ".npmignore", "Makefile", "tsconfig.json", ".eslintrc*")) {
        Get-ChildItem $nmDest -Recurse -File -Filter $pattern -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
    }

    $nmSize = [math]::Round((Get-ChildItem $nmDest -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
    Write-Host "  + node_modules/ ($nmSize MB after cleanup)"
}
else {
    Write-Host "  ERROR: node_modules/ not found! Run npm install first." -ForegroundColor Red
    exit 1
}

# 4. Check Node.js binary and shared libraries
Write-Host ""
Write-Host "[4/4] Checking Node.js binary and Termux shared libraries..."

$nodeBin = Join-Path $AssetsDir "node-bin\node"
$requiredLibs = @(
    "libc++_shared.so",
    "libcares.so",
    "libcrypto.so.3",
    "libffi.so",
    "libicui18n.so.78",
    "libicudata.so.78",
    "libicuuc.so.78",
    "libsqlite3.so",
    "libssl.so.3",
    "libz.so.1"
)

$missingLibs = @()

if (Test-Path $nodeBin) {
    $nodeSize = [math]::Round((Get-Item $nodeBin).Length / 1MB, 1)
    Write-Host "  Found: node binary ($nodeSize MB)" -ForegroundColor Green
}
else {
    Write-Host "  MISSING: node binary" -ForegroundColor Red
    $missingLibs += "node"
}

foreach ($lib in $requiredLibs) {
    $libPath = Join-Path $AssetsDir "node-bin\$lib"
    if (Test-Path $libPath) {
        $libSize = [math]::Round((Get-Item $libPath).Length / 1KB, 0)
        Write-Host "  Found: $lib ($libSize KB)" -ForegroundColor Green
    }
    else {
        Write-Host "  MISSING: $lib" -ForegroundColor Red
        $missingLibs += $lib
    }
}

if ($missingLibs.Count -gt 0) {
    Write-Host ""
    Write-Host "  === HOW TO GET MISSING FILES FROM TERMUX ===" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  On your Termux device, run these commands to copy files to /sdcard/Download/:"
    Write-Host ""
    Write-Host "    mkdir -p /sdcard/Download/node-libs"
    Write-Host "    cp /data/data/com.termux/files/usr/bin/node /sdcard/Download/node-libs/"
    Write-Host "    for lib in libc++_shared.so libcares.so libcrypto.so.3 libffi.so libicui18n.so.78 libicuuc.so.78 libsqlite3.so libssl.so.3 libz.so.1; do"
    Write-Host "      cp /data/data/com.termux/files/usr/lib/`$lib /sdcard/Download/node-libs/"
    Write-Host "    done"
    Write-Host ""
    Write-Host "  Then transfer all files from /sdcard/Download/node-libs/ to:"
    Write-Host "    $AssetsDir\node-bin\"
    Write-Host ""
    Write-Host "  Or use adb:"
    Write-Host "    adb pull /sdcard/Download/node-libs/ ."
    Write-Host "    Copy all files into $AssetsDir\node-bin\"
}

# Summary
Write-Host ""
Write-Host "============================================================"
Write-Host " Assets preparation complete!"
Write-Host "============================================================"

$totalSize = [math]::Round((Get-ChildItem $AssetsDir -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
Write-Host ""
Write-Host "Total size: $totalSize MB"
Write-Host ""
Write-Host "Directory structure:"
Write-Host "  assets/sillytavern/"
Write-Host "    node-bin/"
Write-Host "      node            <-- arm64 Node.js binary"
Write-Host "      libc++_shared.so  <-- Termux shared libraries"
Write-Host "      libcares.so"
Write-Host "      libcrypto.so.3"
Write-Host "      libssl.so.3"
Write-Host "      libicui18n.so.78"
Write-Host "      libicuuc.so.78"
Write-Host "      libsqlite3.so"
Write-Host "      libffi.so"
Write-Host "      libz.so.1"
Write-Host "    app/"
Write-Host "      server.js"
Write-Host "      package.json"
Write-Host "      src/"
Write-Host "      public/"
Write-Host "      default/"
Write-Host "      node_modules/"
Write-Host ""
Write-Host "Build APK with:"
Write-Host "  cd Android; .\gradlew.bat assembleRelease"
