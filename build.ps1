param(
    [string]$VersionName = "1.0",
    [int]$VersionCode = 1,
    [string]$OutFile = ""
)

$ErrorActionPreference = "Stop"

# SDK 位置：优先环境变量（CI 上用），否则用本机默认路径
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$bt   = Join-Path $sdk "build-tools\35.0.0"
$ajar = Join-Path $sdk "platforms\android-31\android.jar"
$jdk  = $env:JAVA_HOME.TrimEnd('\')
$env:PATH = "$jdk\bin;$env:PATH"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$b    = "$root\build"
if (-not $OutFile) { $OutFile = Join-Path $root "ProxySwitch.apk" }

Remove-Item "$b\gen", "$b\classes", "$b\dex", "$b\res.zip" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$b\gen", "$b\classes", "$b\dex" | Out-Null

# 版本号注入：aapt2 的 --version-code / --version-name 只在清单未声明时生效，
# 这里生成一份改过版本号的清单副本再喂给 aapt2
$manifest = Join-Path $b "AndroidManifest.xml"
$raw = Get-Content -Raw -LiteralPath "$root\AndroidManifest.xml"
$raw = $raw -replace 'android:versionCode="[^"]*"', ('android:versionCode="' + $VersionCode + '"')
$raw = $raw -replace 'android:versionName="[^"]*"', ('android:versionName="' + $VersionName + '"')
Set-Content -LiteralPath $manifest -Value $raw -Encoding utf8

& "$bt\aapt2.exe" compile --dir "$root\res" -o "$b\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

& "$bt\aapt2.exe" link -o "$b\base.apk" -I "$ajar" --manifest $manifest `
    --java "$b\gen" --min-sdk-version 21 --target-sdk-version 22 `
    --version-code $VersionCode --version-name $VersionName "$b\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

$srcs = @(Get-ChildItem "$root\src" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName) +
        "$b\gen\com\robin\proxyswitch\R.java"
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -bootclasspath $ajar -d "$b\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& "$jdk\bin\jar.exe" cf "$b\classes.jar" -C "$b\classes" .
& "$bt\d8.bat" --release --min-api 21 --lib $ajar --output "$b\dex" "$b\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Copy-Item "$b\base.apk" "$b\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$b\unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$b\dex\classes.dex", "classes.dex",
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

& "$bt\zipalign.exe" -f 4 "$b\unsigned.apk" "$b\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& "$bt\apksigner.bat" sign --key "$root\platform-keys\testkey.pk8" --cert "$root\platform-keys\testkey.x509.pem" `
    --min-sdk-version 21 --out $OutFile "$b\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Output ("BUILD OK  " + (Get-Item -LiteralPath $OutFile).Length + " bytes  ->  " + $OutFile)
