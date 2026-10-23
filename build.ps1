param([string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest'))

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$portableJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
$previousJavaHome = $env:JAVA_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
$previousAndroidHome = $env:ANDROID_USER_HOME
try {
    if ($portableJdk) { $env:JAVA_HOME = $portableJdk.FullName }
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 or 21, then run again. See README.md.' }
    # Keep build caches and signing files in the project when no explicit override is set.
    # This configures Gradle/SDK Java tools; native Windows ADB still requires a real profile.
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home' }
    if (-not $env:ANDROID_USER_HOME) {
        $env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-home'
        New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
        $debugKey = Join-Path $env:ANDROID_USER_HOME 'debug.keystore'
        if (-not (Test-Path -LiteralPath $debugKey) -and $env:USERPROFILE) {
            $existingDebugKey = Join-Path $env:USERPROFILE '.android/debug.keystore'
            if (Test-Path -LiteralPath $existingDebugKey) {
                Copy-Item -LiteralPath $existingDebugKey -Destination $debugKey
            }
        }
    }
    if (-not ($Tasks | Where-Object { $_ -like '-Pdinoblog.debugKeystore=*' })) {
        $existingKey = Join-Path $env:ANDROID_USER_HOME 'debug.keystore'
        if (Test-Path -LiteralPath $existingKey) {
            # Reuse exactly the same certificate; never replace the identity used by
            # already installed APKs. An explicit key avoids AGP's default-key lock.
            $buildKey = Join-Path $env:ANDROID_USER_HOME 'dinoblog-debug.keystore'
            if (-not (Test-Path -LiteralPath $buildKey)) {
                Copy-Item -LiteralPath $existingKey -Destination $buildKey
            } elseif ((Get-FileHash -LiteralPath $existingKey).Hash -ne (Get-FileHash -LiteralPath $buildKey).Hash) {
                throw 'Workspace debug keystores differ. Preserve the installed app signing key before building.'
            }
            $Tasks += "-Pdinoblog.debugKeystore=$buildKey"
        }
    }
    if (-not ($Tasks | Where-Object { $_ -like '-Pkotlin.compiler.execution.strategy=*' })) {
        $Tasks += '-Pkotlin.compiler.execution.strategy=in-process'
    }
    Push-Location $projectRoot
    try {
        & .\gradlew.bat @Tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:ANDROID_USER_HOME = $previousAndroidHome
}
