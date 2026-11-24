$ErrorActionPreference = "Stop"

$sdk  = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$bt   = "$sdk\build-tools\35.0.0"
$ajar = "$sdk\platforms\android-31\android.jar"
$jdk  = $env:JAVA_HOME.TrimEnd('\')
$env:PATH = "$jdk\bin;$env:PATH"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$b    = "$root\build"

Remove-Item "$b\gen", "$b\classes", "$b\dex", "$b\res.zip" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$b\gen", "$b\classes", "$b\dex" | Out-Null

& "$bt\aapt2.exe" compile --dir "$root\res" -o "$b\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

& "$bt\aapt2.exe" link -o "$b\base.apk" -I "$ajar" --manifest "$root\AndroidManifest.xml" `
    --java "$b\gen" --min-sdk-version 21 --target-sdk-version 22 `
    --version-code 1 --version-name "1.0" "$b\res.zip"
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
    --min-sdk-version 21 --out "$root\ProxySwitch.apk" "$b\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Output ("BUILD OK  " + (Get-Item "$root\ProxySwitch.apk").Length + " bytes")
