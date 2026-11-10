<#
.SYNOPSIS
  生成 / 导出 APK 与 RPK 的统一签名（Windows PowerShell 版）

.DESCRIPTION
  对应 tools/gen-signing.sh：
    keys/release.jks -> keys/release.p12 -> keys/private.pem + keys/certificate.pem
    并复制 PEM 到 band-app、band-app-pro 的 sign/debug 与 sign/release。

  需要：keytool.exe（JDK）与 openssl.exe（Git for Windows 自带）。
  密码只做参数传入，不写进任何文件。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\gen-signing.ps1 -Pass "你的密码"
  powershell -ExecutionPolicy Bypass -File tools\gen-signing.ps1 -Pass "你的密码" -From D:\my\release.jks
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Pass,
    [string]$OutDir = "",
    [string]$Alias = "gaodenav",
    [string]$From = "",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $Repo "keys" }

function Say($m) { Write-Host $m }
function Step($m) { Write-Host ""; Write-Host "===== $m =====" }
function Need($name) {
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $c) { throw "缺少命令 $name" }
    return $c.Source
}

$keytool = Need "keytool.exe"
$openssl = Get-Command openssl.exe -ErrorAction SilentlyContinue
if (-not $openssl) {
    $gitOpenSsl = Join-Path $env:ProgramFiles "Git\usr\bin\openssl.exe"
    if (Test-Path $gitOpenSsl) { $openssl = Get-Item $gitOpenSsl }
}
if (-not $openssl) { throw "缺少 openssl.exe（装 Git for Windows 或单独装 openssl）" }
Say "  keytool : $keytool"
Say "  openssl : $($openssl.FullName)"

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
$Ks = Join-Path $OutDir "release.jks"
$P12 = Join-Path $OutDir "release.p12"
$Priv = Join-Path $OutDir "private.pem"
$Cert = Join-Path $OutDir "certificate.pem"

if ($From) {
    if (-not (Test-Path $From)) { throw "找不到 -From 指定的 keystore：$From" }
    Copy-Item -Force $From $Ks
    Say "  使用已有 keystore：$From"
}

Step "1/5 keystore"
if ((Test-Path $Ks) -and (-not $Force)) {
    Say "  已存在，复用：$Ks（-Force 可重新生成）"
} else {
    if (Test-Path $Ks) { Remove-Item -Force $Ks }
    & $keytool -genkeypair -v -keystore $Ks -storetype JKS -alias $Alias -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=BandNav, OU=Dev, O=YourOrg, L=Beijing, ST=Beijing, C=CN" -storepass $Pass -keypass $Pass | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool 生成 keystore 失败（密码至少 6 位）" }
    Say "  已生成：$Ks（alias=$Alias）"
}

Step "2/5 jks -> p12"
if (Test-Path $P12) { Remove-Item -Force $P12 }
& $keytool -importkeystore -noprompt -srckeystore $Ks -srcstoretype JKS -srcalias $Alias -srcstorepass $Pass -destkeystore $P12 -deststoretype PKCS12 -deststorepass $Pass -destkeypass $Pass | Out-Null
if ($LASTEXITCODE -ne 0) { throw "jks -> p12 转换失败（别名或密码不对？）" }
Say "  已生成：$P12"

Step "3/5 p12 -> PEM"
if (Test-Path $Priv) { Remove-Item -Force $Priv }
if (Test-Path $Cert) { Remove-Item -Force $Cert }
& $openssl.FullName pkcs12 -in $P12 -nocerts -nodes -passin "pass:$Pass" -out $Priv 2>$null
if ($LASTEXITCODE -ne 0) { throw "导出 private.pem 失败" }
& $openssl.FullName pkcs12 -in $P12 -nokeys -clcerts -passin "pass:$Pass" -out $Cert 2>$null
if ($LASTEXITCODE -ne 0) { throw "导出 certificate.pem 失败" }
Say "  已生成：$Priv"
Say "  已生成：$Cert"

Step "4/5 指纹"
$list = & $keytool -list -v -keystore $Ks -alias $Alias -storepass $Pass 2>$null
$sha1 = ($list | Select-String -Pattern "^\s*SHA1:" | Select-Object -First 1).Line.Trim()
$sha256 = ($list | Select-String -Pattern "^\s*SHA256:" | Select-Object -First 1).Line.Trim()
$md5 = ($list | Select-String -Pattern "^\s*MD5:" | Select-Object -First 1).Line.Trim()
Say "  $sha1"
Say "  $sha256"
Say "  $md5"
Say "  （高德开放平台 Key 要填上面这串带冒号大写的 SHA1）"
$sha256Plain = ($sha256 -replace "^SHA256:\s*", "" -replace ":", "" -replace "\s", "").ToLower()
Set-Content -Encoding ASCII -NoNewline (Join-Path $OutDir "cert-sha256.txt") $sha256Plain
@($sha1, $sha256, $md5) | Set-Content -Encoding UTF8 (Join-Path $OutDir "fingerprints.txt")

Step "5/5 分发到两个手环工程"
foreach ($app in @("band-app", "band-app-pro")) {
    foreach ($mode in @("debug", "release")) {
        $dst = Join-Path $Repo "$app\sign\$mode"
        New-Item -ItemType Directory -Force -Path $dst | Out-Null
        Copy-Item -Force $Priv (Join-Path $dst "private.pem")
        Copy-Item -Force $Cert (Join-Path $dst "certificate.pem")
    }
    Say "  $app/sign/{debug,release}/private.pem + certificate.pem"
}

Write-Host ""
Say "完成 OK"
Say "  keystore : $Ks"
Say "  下一步：用 bash android-app/build.sh 打 APK；(cd band-app; npm install; npx aiot release) 打 rpk"
Say "  注意：keys 与 *.pem 已写进 .gitignore，绝不提交；换签名会让已安装的包无法覆盖升级。"
