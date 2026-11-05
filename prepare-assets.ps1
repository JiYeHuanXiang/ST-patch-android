# prepare-assets.ps1 - Prepare Android assets directory
# Copies SillyTavern source + node_modules to Android/app/src/main/assets/sillytavern/
#
# Usage:  powershell -ExecutionPolicy Bypass -File Android\prepare-assets.ps1
#
# Node.js runtime components (per ABI) must be manually placed at:
#   Android\app\src\main\jniLibs\<abi>\libnode.so                  <- Termux node binary, renamed
#   Android\app\src\main\assets\sillytavern\node-bin\<abi>\*.so    <- Termux shared libraries
#   (abi = arm64-v8a / x86_64; sources & rename rules see README)

param(
    [string]$SourceRoot
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# 默认项目根 = 脚本上一级目录；可用 -SourceRoot 显式指定 SillyTavern 源码目录
if (-not $SourceRoot) {
    $ProjectRoot = Resolve-Path (Join-Path $ScriptDir "..")
} else {
    $ProjectRoot = Resolve-Path $SourceRoot
}
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

# 补丁版自描述标记：原版与补丁版酒馆的特征文件完全相同（server.js + package.json），
# Termux 侧脚本（apply.sh 等）靠 .st-patch-build 区分：无标记=原版；有标记=补丁版，
# 版本与当前补丁包不一致=旧补丁（可覆盖升级），一致则跳过。与 prepare-assets.sh 保持一致。
$buildGradle = Get-Content (Join-Path $ScriptDir "app\build.gradle") -Raw
if ($buildGradle -match 'versionName\s+"([^"]+)"') {
    $MarkerVersion = $Matches[1]
} else {
    $MarkerVersion = "unknown"
}
Set-Content -Path (Join-Path $AssetsDir "app\.st-patch-build") -Value $MarkerVersion -NoNewline
Write-Host "  + .st-patch-build ($MarkerVersion)"

# 3. Copy node_modules
Write-Host ""
Write-Host "[3/4] Copying node_modules (this may take a few minutes)..."

$nmSrc = Join-Path $ProjectRoot "node_modules"
$nmDest = Join-Path $AssetsDir "app\node_modules"

if (Test-Path $nmSrc) {
    if (Test-Path $nmDest) { Remove-Item $nmDest -Recurse -Force }

    Copy-Item $nmSrc $nmDest -Recurse -Force
    Write-Host "  Copy done, cleaning up..."

    # Keep @types/ — webpack's filesystem cache snapshots module dependencies at boot
    # and must resolve each package's declared @types/*. Deleting it makes the cache write
    # fail ("Caching failed for pack"), forcing a full ~20s recompile every launch.
    # Keeping it costs ~1MB compressed and lets warm boots compile in ~2s.

    # Remove unneeded files by extension (skip @types/, which is almost entirely .d.ts)
    foreach ($ext in @("*.d.ts", "*.map")) {
        Get-ChildItem $nmDest -Recurse -File -Filter $ext -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -notmatch "[\\/]@types[\\/]" } |
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

# 4. Check Node.js runtime components (per-ABI: arm64-v8a / x86_64)
Write-Host ""
Write-Host "[4/4] Checking Node.js runtime components (per ABI)..."

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

foreach ($abi in @("arm64-v8a", "x86_64")) {
    $nodeSo = Join-Path $ScriptDir "app\src\main\jniLibs\$abi\libnode.so"
    if (Test-Path $nodeSo) {
        $nodeSize = [math]::Round((Get-Item $nodeSo).Length / 1MB, 1)
        Write-Host "  Found: jniLibs/$abi/libnode.so ($nodeSize MB)" -ForegroundColor Green
    }
    else {
        Write-Host "  MISSING: jniLibs/$abi/libnode.so" -ForegroundColor Red
        $missingLibs += "jniLibs/$abi/libnode.so"
    }

    foreach ($lib in $requiredLibs) {
        $libPath = Join-Path $AssetsDir "node-bin\$abi\$lib"
        if (Test-Path $libPath) {
            $libSize = [math]::Round((Get-Item $libPath).Length / 1KB, 0)
            Write-Host "  Found: node-bin/$abi/$lib ($libSize KB)" -ForegroundColor Green
        }
        else {
            Write-Host "  MISSING: node-bin/$abi/$lib" -ForegroundColor Red
            $missingLibs += "node-bin/$abi/$lib"
        }
    }
}

if ($missingLibs.Count -gt 0) {
    Write-Host ""
    Write-Host "  === HOW TO GET MISSING FILES ===" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  Download the matching-arch debs from the Termux pool and unpack them:"
    Write-Host ""
    Write-Host "    https://packages.termux.dev/apt/termux-main/pool/main/"
    Write-Host ""
    Write-Host "  - nodejs: usr/bin/node -> rename to libnode.so -> jniLibs\<abi>\"
    Write-Host "  - libc++, c-ares, libffi, openssl, libicu, libsqlite, zlib:"
    Write-Host "      usr/lib/*.so* -> assets\sillytavern\node-bin\<abi>\"
    Write-Host "      (icu/sqlite/zlib filenames must match the sonames the node binary needs;"
    Write-Host "       e.g. libicudata.so.78.3 -> libicudata.so.78, libsqlite3.so.3.x -> libsqlite3.so)"
}

# 5. Archive app/ into app.zip (single-stream extraction at runtime avoids 14000+ AssetManager lookups)
Write-Host ""
Write-Host "[5/5] Archiving app/ into app.zip (runtime extraction speedup)..."

$AppDir = Join-Path $AssetsDir "app"
$AppZip = Join-Path $AssetsDir "app.zip"

if (-not (Test-Path $AppDir)) {
    Write-Host "  ERROR: $AppDir not found, cannot archive." -ForegroundColor Red
    exit 1
}

# .NET ZipFile API — 手动遍历写入，强制正斜杠条目名（Java ZipInputStream 标准）
# CreateFromDirectory 在 Windows 会用反斜杠，导致 Android 端路径解析异常
# 子进程里 Add-Type -AssemblyName 偶发无法解析枚举类型，用 LoadWithPartialName 兜底强制加载
[void][System.Reflection.Assembly]::LoadWithPartialName("System.IO.Compression")
[void][System.Reflection.Assembly]::LoadWithPartialName("System.IO.Compression.FileSystem")

if (Test-Path $AppZip) { Remove-Item $AppZip -Force }

# Create=1, CompressionLevel.Optimal=0；用数值字面量避开枚举类型解析失败
$fs = [System.IO.File]::Open($AppZip, [System.IO.FileMode]::Create)
$zip = New-Object System.IO.Compression.ZipArchive($fs, 1)
try {
    Get-ChildItem $AppDir -Recurse -File -Force | ForEach-Object {
        # 相对 app/ 的路径，统一成正斜杠（不加 app/ 前缀）
        # 解压器把条目解到 sillytavern/.app_tmp/，再 rename 为 app/，即得 sillytavern/app/<rel>
        $rel = $_.FullName.Substring($AppDir.Length + 1).Replace('\', '/')
        $entry = $zip.CreateEntry($rel, 0)
        $es = $entry.Open()
        $is = [System.IO.File]::OpenRead($_.FullName)
        try { $is.CopyTo($es) } finally { $is.Dispose(); $es.Dispose() }
    }
} finally {
    $zip.Dispose()
    $fs.Dispose()
}

$zipSize = [math]::Round((Get-Item $AppZip).Length / 1MB, 1)
$zipInfo = [System.IO.Compression.ZipFile]::OpenRead($AppZip)
try {
    $entryCount = $zipInfo.Entries.Count
} finally {
    $zipInfo.Dispose()
}
Write-Host "  + app.zip ($zipSize MB, $entryCount entries)"

# Delete scattered app/ now that it's archived (node-bin/ stays scattered)
Remove-Item $AppDir -Recurse -Force
Write-Host "  Removed scattered app/ directory"

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
Write-Host "  app/src/main/"
Write-Host "    jniLibs/arm64-v8a/libnode.so   <-- arm64 Node.js binary (node renamed)"
Write-Host "    jniLibs/x86_64/libnode.so      <-- x86_64 Node.js binary (emulator debugging)"
Write-Host "  assets/sillytavern/"
Write-Host "    node-bin/arm64-v8a/*.so        <-- arm64 Termux shared libraries"
Write-Host "    node-bin/x86_64/*.so           <-- x86_64 Termux shared libraries"
Write-Host "    app.zip                        <-- archived SillyTavern (arch-independent)"
Write-Host ""
Write-Host "Build APK with:"
Write-Host "  cd Android; .\gradlew.bat assembleRelease"
