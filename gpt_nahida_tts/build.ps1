param(
    [string]$UnityAndroidPlayer = 'C:\Program Files\Unity\Hub\Editor\2021.3.34f1\Editor\Data\PlaybackEngines\AndroidPlayer',
    [string]$TokenFile = ''
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Sdk = Join-Path $UnityAndroidPlayer 'SDK'
$Jdk = Join-Path $UnityAndroidPlayer 'OpenJDK'
$GradleLib = Join-Path $UnityAndroidPlayer 'Tools\gradle\lib'
$BuildTools = Join-Path $Sdk 'build-tools\30.0.2'
$AndroidJar = Join-Path $Sdk 'platforms\android-30\android.jar'
$Java = Join-Path $Jdk 'bin\java.exe'
$Javac = Join-Path $Jdk 'bin\javac.exe'
$Jar = Join-Path $Jdk 'bin\jar.exe'
$Keytool = Join-Path $Jdk 'bin\keytool.exe'
$Aapt = Join-Path $BuildTools 'aapt.exe'
$D8 = Join-Path $BuildTools 'd8.bat'
$Zipalign = Join-Path $BuildTools 'zipalign.exe'
$Apksigner = Join-Path $BuildTools 'apksigner.bat'

$Required = @($Java, $Javac, $Jar, $Keytool, $Aapt, $D8, $Zipalign, $Apksigner, $AndroidJar)
foreach ($Item in $Required) {
    if (-not (Test-Path -LiteralPath $Item)) {
        throw "Required Android build tool not found: $Item"
    }
}

$KotlinStdlib = Join-Path $GradleLib 'kotlin-stdlib-1.3.61.jar'
$KotlinStdlibJdk7 = Join-Path $GradleLib 'kotlin-stdlib-jdk7-1.3.61.jar'
$KotlinStdlibJdk8 = Join-Path $GradleLib 'kotlin-stdlib-jdk8-1.3.61.jar'
foreach ($Item in @($KotlinStdlib, $KotlinStdlibJdk7, $KotlinStdlibJdk8)) {
    if (-not (Test-Path -LiteralPath $Item)) {
        throw "Required Kotlin library not found: $Item"
    }
}

$Build = Join-Path $ProjectRoot 'build'
$Generated = Join-Path $Build 'generated'
$Classes = Join-Path $Build 'classes'
$Dex = Join-Path $Build 'dex'
$Outputs = Join-Path $Build 'outputs'
$Manifest = Join-Path $ProjectRoot 'app\src\main\AndroidManifest.xml'
$Resources = Join-Path $ProjectRoot 'app\src\main\res'
$Sources = Join-Path $ProjectRoot 'app\src\main\java'
$Unsigned = Join-Path $Build 'probe-unsigned.apk'
$Aligned = Join-Path $Build 'probe-aligned.apk'
$FinalApk = Join-Path $Outputs 'gpt-nahida-accessibility-probe-debug.apk'
$Keystore = Join-Path $ProjectRoot 'debug.keystore'
$ClassesJar = Join-Path $Build 'app-classes.jar'
if (-not $TokenFile) { $TokenFile = Join-Path $ProjectRoot 'nahida_tts_token.txt' }

if (Test-Path -LiteralPath $Build) {
    Remove-Item -LiteralPath $Build -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $Generated, $Classes, $Dex, $Outputs | Out-Null

# A source build works without embedded credentials; users can enter the token in the app.
$DefaultApiToken = ''
if (Test-Path -LiteralPath $TokenFile) {
    $DefaultApiToken = (Get-Content -LiteralPath $TokenFile -Raw).Trim()
}
# Quote and escape the optional token as a Kotlin string literal.
$TokenLiteral = '"' + $DefaultApiToken.Replace('\', '\\').Replace('"', '\"').Replace("`r", '\r').Replace("`n", '\n').Replace('$', '\$') + '"'
$GeneratedSecrets = Join-Path $Generated 'BuildSecrets.kt'
@"
package com.nahida.gptprobe

object BuildSecrets {
    const val DEFAULT_API_TOKEN = $TokenLiteral
}
"@ | Set-Content -LiteralPath $GeneratedSecrets -Encoding UTF8

Write-Host '[1/7] Generating Android resources...'
& $Aapt package -f -m -J $Generated -M $Manifest -S $Resources -I $AndroidJar
if ($LASTEXITCODE -ne 0) { throw 'aapt resource generation failed.' }

$RJava = Join-Path $Generated 'com\nahida\gptprobe\R.java'
Write-Host '[2/7] Compiling generated R.java...'
& $Javac -source 1.8 -target 1.8 -encoding UTF-8 -classpath $AndroidJar -d $Classes $RJava
if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }

$KotlinSources = @(
    Get-ChildItem -LiteralPath $Sources -Filter '*.kt' -File -Recurse | ForEach-Object { $_.FullName }
) + $GeneratedSecrets
if (-not $KotlinSources) { throw 'No Kotlin source files found.' }
$CompileClasspath = "$AndroidJar;$Classes;$KotlinStdlib;$KotlinStdlibJdk7;$KotlinStdlibJdk8"

Write-Host '[3/7] Compiling Kotlin sources...'
& $Java -cp "$GradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -jvm-target 1.8 -no-reflect -no-stdlib -classpath $CompileClasspath -d $Classes $KotlinSources
if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed.' }

Write-Host '[4/7] Converting bytecode to DEX...'
& $Jar cf $ClassesJar -C $Classes .
if ($LASTEXITCODE -ne 0) { throw 'Failed to package compiled classes.' }
& $D8 --min-api 23 --lib $AndroidJar --output $Dex $ClassesJar $KotlinStdlib $KotlinStdlibJdk7 $KotlinStdlibJdk8
if ($LASTEXITCODE -ne 0) { throw 'D8 failed.' }

Write-Host '[5/7] Packaging APK...'
& $Aapt package -f -M $Manifest -S $Resources -I $AndroidJar -F $Unsigned
if ($LASTEXITCODE -ne 0) { throw 'aapt packaging failed.' }
Push-Location $Dex
try {
    & $Aapt add $Unsigned 'classes.dex'
    if ($LASTEXITCODE -ne 0) { throw 'aapt failed to add classes.dex.' }
} finally {
    Pop-Location
}
& $Zipalign -f 4 $Unsigned $Aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }

if (-not (Test-Path -LiteralPath $Keystore)) {
    Write-Host '[6/7] Creating project-local debug signing key...'
    & $Keytool -genkeypair -v -keystore $Keystore -storepass android -alias androiddebugkey `
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }
} else {
    Write-Host '[6/7] Reusing project-local debug signing key...'
}

Write-Host '[7/7] Signing APK...'
& $Apksigner sign --ks $Keystore --ks-key-alias androiddebugkey --ks-pass pass:android `
    --key-pass pass:android --out $FinalApk $Aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed.' }
& $Apksigner verify --verbose $FinalApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

Write-Host ''
Write-Host "APK ready: $FinalApk" -ForegroundColor Green
