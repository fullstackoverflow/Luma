param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) {
    $lumaJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $lumaJbr 'bin\java.exe')) { $env:JAVA_HOME = $lumaJbr }
}
if (-not $env:ANDROID_HOME) {
    $lumaSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path -LiteralPath $lumaSdk) { $env:ANDROID_HOME = $lumaSdk }
}
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed ($LASTEXITCODE)" }
} finally { Pop-Location }
