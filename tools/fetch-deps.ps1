<#
.SYNOPSIS
  第三方依赖获取（Windows PowerShell 版）—— 高德导航 SDK / 小米互联 SDK / R8

.DESCRIPTION
  对应 tools/fetch-deps.sh 的 Windows 版本，产物路径完全一致：
    third_party/AMapNavi/libs/<合并 jar>
    third_party/AMapNavi/assets/
    third_party/AMapNavi/jni/arm64-v8a/*.so
    third_party/r8/r8.jar                      （-WithR8）
    android-app/libs/xms-wearable-classes.jar  （从 BandQQ 的 AAR 解出）

  依赖文件不进仓库：高德 SDK 与小米互联 SDK 都不是本项目的代码，许可证不允许转分发。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\fetch-deps.ps1
  powershell -ExecutionPolicy Bypass -File tools\fetch-deps.ps1 -Check
  powershell -ExecutionPolicy Bypass -File tools\fetch-deps.ps1 -Offline D:\downloads\AMap_Android_Navi_SDK_All.zip
#>
[CmdletBinding()]
param(
    [string]$Dir = "",
    [string]$Cache = "",
    [switch]$Force,
    [switch]$SkipAmap,
    [switch]$SkipXms,
    [switch]$WithR8,
    [string]$Offline = "",
    [switch]$Check
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$Repo = Split-Path -Parent $PSScriptRoot
if (-not $Dir) { $Dir = Join-Path $Repo "third_party" }
if (-not $Cache) { $Cache = Join-Path $Repo ".cache\deps" }
$AmapDest = Join-Path $Dir "AMapNavi"
$XmsJar = Join-Path $Repo "android-app\libs\xms-wearable-classes.jar"

$AmapUrl = "https://amappc.cn-hangzhou.oss-pub.aliyun-inc.com/lbs/static/zip/AMap_Android_Navi_SDK_All.zip"
$XmsUrl = "https://raw.githubusercontent.com/Gsjsjzhznsz/BandQQ/main/android-sync/app/libs/xms-wearable-lib_1.4_release.aar"
$XmsProxy = "https://ghfast.top/"
$R8MetadataUrl = "https://maven.aliyun.com/repository/google/com/android/tools/r8/maven-metadata.xml"
$R8BaseUrl = "https://maven.aliyun.com/repository/google/com/android/tools/r8"

function Say($msg) { Write-Host $msg }
function Step($msg) { Write-Host ""; Write-Host "===== $msg =====" }

function Get-Url($url, $out) {
    $parent = Split-Path -Parent $out
    if ($parent -and -not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $tmp = "$out.part"
    Say "  GET $url"
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o $tmp $url
        if ($LASTEXITCODE -ne 0) { Remove-Item -Force $tmp -ErrorAction SilentlyContinue; return $false }
    } else {
        try {
            Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
        } catch {
            Remove-Item -Force $tmp -ErrorAction SilentlyContinue
            return $false
        }
    }
    if (-not (Test-Path $tmp) -or (Get-Item $tmp).Length -eq 0) { return $false }
    Move-Item -Force $tmp $out
    return $true
}

function Expand-ZipTo($zip, $dest) {
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest)
}

function Get-FileSizeText($path) {
    if (-not (Test-Path $path)) { return "?" }
    $item = Get-Item $path
    if ($item.PSIsContainer) {
        $sum = (Get-ChildItem -Recurse -File $path | Measure-Object -Property Length -Sum).Sum
        return ("{0:N1} MB" -f ($sum / 1MB))
    }
    return ("{0:N1} MB" -f ($item.Length / 1MB))
}

function Get-AmqpJar {
    if (-not (Test-Path (Join-Path $AmapDest "libs"))) { return $null }
    $j = Get-ChildItem (Join-Path $AmapDest "libs") -Filter *.jar -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($j) { return $j.FullName }
    return $null
}

function Test-AmapReady {
    $jar = Get-AmqpJar
    if (-not $jar) { return $false }
    $assets = Join-Path $AmapDest "assets"
    $soDir = Join-Path $AmapDest "jni\arm64-v8a"
    if (-not (Test-Path $assets)) { return $false }
    if (-not (Test-Path $soDir)) { return $false }
    $assetCount = (Get-ChildItem -Recurse -File $assets | Measure-Object).Count
    $soCount = (Get-ChildItem $soDir -Filter *.so | Measure-Object).Count
    return ($assetCount -ge 500 -and $soCount -ge 5)
}

function Test-XmsReady {
    if (-not (Test-Path $XmsJar)) { return $false }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($XmsJar)
    try {
        return ($null -ne ($zip.Entries | Where-Object { $_.FullName -eq "com/xiaomi/xms/wearable/Wearable.class" } | Select-Object -First 1))
    } finally { $zip.Dispose() }
}

function Show-Status {
    Step "依赖状态（$Dir）"
    if (Test-AmapReady) {
        $jar = Get-AmqpJar
        $assetCount = (Get-ChildItem -Recurse -File (Join-Path $AmapDest "assets") | Measure-Object).Count
        $soCount = (Get-ChildItem (Join-Path $AmapDest "jni\arm64-v8a") -Filter *.so | Measure-Object).Count
        Say ("  [OK]   高德导航 SDK")
        Say ("         jar   : $jar ({0})" -f (Get-FileSizeText $jar))
        Say ("         assets: $assetCount 个文件")
        Say ("         so    : $soCount 个 arm64")
    } else {
        Say "  [缺失] 高德导航 SDK —— 运行 tools\fetch-deps.ps1"
    }
    if (Test-XmsReady) {
        Say ("  [OK]   小米互联 SDK：$XmsJar ({0})" -f (Get-FileSizeText $XmsJar))
    } else {
        Say "  [缺失] 小米互联 SDK —— 运行 tools\fetch-deps.ps1"
    }
    $r8 = Join-Path $Dir "r8\r8.jar"
    if (Test-Path $r8) { Say ("  [OK]   R8：$r8 ({0})" -f (Get-FileSizeText $r8)) }
    else { Say "  [--]   R8 未下载（只有没有 d8 时才需要 -WithR8）" }
}

function Get-Sha256($path) {
    return (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLower()
}

if ($Check) {
    Show-Status
    if ((Test-AmapReady) -and ($SkipXms -or (Test-XmsReady))) { Say ""; Say "依赖齐全 OK"; exit 0 }
    Say ""; Say "依赖不齐，运行：powershell -ExecutionPolicy Bypass -File tools\fetch-deps.ps1"
    exit 1
}

New-Item -ItemType Directory -Force -Path $Dir, $Cache, (Split-Path -Parent $XmsJar) | Out-Null
Say "仓库目录：$Repo"
Say "依赖目录：$Dir"
Say "缓存目录：$Cache"

# ---------------------------------------------------------------- 高德 SDK
if (-not $SkipAmap) {
    Step "1/3 高德 Android 导航 SDK"
    if ((Test-AmapReady) -and (-not $Force)) {
        Say "  已就绪，跳过（-Force 强制重下）"
    } else {
        $zip = Join-Path $Cache "AMap_Android_Navi_SDK_All.zip"
        if ($Offline) {
            if (-not (Test-Path $Offline)) { throw "-Offline 指定的文件不存在：$Offline" }
            Say "  使用本地包：$Offline"
            Copy-Item -Force $Offline $zip
        } elseif ((-not (Test-Path $zip)) -or $Force) {
            if (-not (Get-Url $AmapUrl $zip)) { throw "高德 SDK 下载失败，可手动下载后用 -Offline 指定" }
        } else {
            Say "  使用缓存：$zip ({0})" -f (Get-FileSizeText $zip)
        }

        Say "  校验 zip 完整性…"
        try {
            $probe = [System.IO.Compression.ZipFile]::OpenRead($zip)
            $innerName = ($probe.Entries | Where-Object { $_.FullName -like "AMap3DMap*.zip" } | Select-Object -First 1)
            $probe.Dispose()
        } catch { throw "下载的 zip 已损坏：$zip（删掉后重试）" }
        if (-not $innerName) { throw "高德包结构异常：外层 zip 里没找到 AMap3DMap*.zip" }

        $work = Join-Path $Cache "amap-work"
        if (Test-Path $work) { Remove-Item -Recurse -Force $work }
        Say "  解压外层包…"
        Expand-ZipTo $zip (Join-Path $work "outer")

        $innerZip = Get-ChildItem (Join-Path $work "outer") -Filter "AMap3DMap*.zip" -File | Select-Object -First 1
        if (-not $innerZip) { throw "高德包结构异常：外层 zip 里没找到 AMap3DMap*.zip" }
        Say ("  解压内层包：{0}" -f $innerZip.Name)
        Expand-ZipTo $innerZip.FullName (Join-Path $work "inner")

        $jar = Get-ChildItem (Join-Path $work "inner") -Filter *.jar -File -Recurse | Select-Object -First 1
        if (-not $jar) { throw "高德包结构异常：内层 zip 里没找到合并 jar" }
        $soDir = Get-ChildItem (Join-Path $work "inner") -Directory -Recurse | Where-Object { $_.Name -eq "arm64-v8a" } | Select-Object -First 1
        if (-not $soDir) { throw "高德包结构异常：内层 zip 里没找到 arm64-v8a" }

        Say "  组装到 $AmapDest …"
        if (Test-Path $AmapDest) { Remove-Item -Recurse -Force $AmapDest }
        New-Item -ItemType Directory -Force -Path (Join-Path $AmapDest "libs"), (Join-Path $AmapDest "assets"), (Join-Path $AmapDest "jni\arm64-v8a") | Out-Null
        Copy-Item -Force $jar.FullName (Join-Path $AmapDest "libs")
        Copy-Item -Force (Join-Path $soDir.FullName "*.so") (Join-Path $AmapDest "jni\arm64-v8a")

        Say "  从 jar 里抽出 assets（地图/导航资源）…"
        $zipObj = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
        $n = 0
        try {
            foreach ($entry in $zipObj.Entries) {
                if (-not $entry.FullName.StartsWith("assets/")) { continue }
                if ([string]::IsNullOrEmpty($entry.Name)) { continue }
                $rel = $entry.FullName.Substring("assets/".Length)
                $dst = Join-Path (Join-Path $AmapDest "assets") $rel
                $dd = Split-Path -Parent $dst
                if ($dd -and -not (Test-Path $dd)) { New-Item -ItemType Directory -Force -Path $dd | Out-Null }
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dst, $true)
                $n++
            }
        } finally { $zipObj.Dispose() }
        Say "  assets 抽取：$n 个文件"

        if (-not (Test-AmapReady)) { throw "组装后自检失败（jar/assets/so 数量不对）" }
        Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
        Say "  完成：$(Get-FileSizeText $AmapDest)"
        @(
            "source: $AmapUrl"
            "downloaded: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
            "jar: $($jar.Name)"
            "jar_sha256: $(Get-Sha256 (Join-Path $AmapDest ('libs\' + $jar.Name)))"
        ) | Set-Content -Encoding UTF8 (Join-Path $AmapDest "SDK-VERSION.txt")
    }
}

# ---------------------------------------------------------------- 小米互联
if (-not $SkipXms) {
    Step "2/3 小米互联 SDK（BandQQ 的 xms-wearable-lib）"
    if ((Test-XmsReady) -and (-not $Force)) {
        Say "  已就绪，跳过（-Force 强制重下）"
    } else {
        $aar = Join-Path $Cache "xms-wearable-lib_1.4_release.aar"
        if ((-not (Test-Path $aar)) -or $Force) {
            if (-not (Get-Url $XmsUrl $aar)) {
                Say "  直连失败，改用镜像：$XmsProxy"
                if (-not (Get-Url ($XmsProxy + $XmsUrl) $aar)) { throw "小米互联 AAR 下载失败（可手动下载后放到 $aar）" }
            }
        } else {
            Say "  使用缓存：$aar"
        }
        $tmpJar = Join-Path $Cache "xms-classes.jar"
        $zipObj = [System.IO.Compression.ZipFile]::OpenRead($aar)
        try {
            $entry = $zipObj.Entries | Where-Object { $_.FullName -eq "classes.jar" } | Select-Object -First 1
            if (-not $entry) { throw "AAR 里没有 classes.jar：$aar" }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $tmpJar, $true)
        } finally { $zipObj.Dispose() }
        if (-not (Test-Path $tmpJar) -or (Get-Item $tmpJar).Length -eq 0) { throw "AAR 里的 classes.jar 是空的" }
        $c = [System.IO.Compression.ZipFile]::OpenRead($tmpJar)
        try {
            $hit = $c.Entries | Where-Object { $_.FullName -eq "com/xiaomi/xms/wearable/Wearable.class" } | Select-Object -First 1
        } finally { $c.Dispose() }
        if (-not $hit) { throw "classes.jar 里没有 com.xiaomi.xms.wearable.Wearable —— AAR 版本可能变了" }
        Move-Item -Force $tmpJar $XmsJar
        Say ("  完成：$XmsJar ({0})" -f (Get-FileSizeText $XmsJar))
    }
}

# ---------------------------------------------------------------- R8
if ($WithR8) {
    Step "3/3 R8（可选的 dexer）"
    $out = Join-Path $Dir "r8\r8.jar"
    if ((Test-Path $out) -and (-not $Force)) {
        Say "  已就绪，跳过：$out"
    } else {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $out) | Out-Null
        $meta = Join-Path $Cache "r8-metadata.xml"
        if (-not (Get-Url $R8MetadataUrl $meta)) { throw "取不到 R8 版本列表" }
        $text = Get-Content -Raw $meta
        $m = [regex]::Match($text, "<latest>([^<]+)</latest>")
        if (-not $m.Success) { $m = [regex]::Match($text, "<version>([^<]+)</version>", "RightToLeft") }
        if (-not $m.Success) { throw "解析 R8 版本号失败" }
        $ver = $m.Groups[1].Value
        Say "  版本：$ver"
        if (-not (Get-Url ("$R8BaseUrl/$ver/r8-$ver.jar") $out)) { throw "R8 下载失败" }
        Say ("  完成：$out ({0})" -f (Get-FileSizeText $out))
    }
}

Show-Status
Say ""
Say "依赖就绪 OK  下一步："
Say "  1) powershell -ExecutionPolicy Bypass -File tools\gen-signing.ps1 -Pass '你的密码'"
Say "  2) 用 WSL / Linux / macOS 跑 bash android-app/build.sh（APK 打包依赖 aapt/apksigner 等命令行工具）"
Say "  3) 在 band-app / band-app-pro 里 npm install 后 npx aiot release 打 rpk"
