Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$workDir = $null
Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    $javacCommand = Get-Command javac -ErrorAction SilentlyContinue
    if ($env:JAVA_HOME) {
        $javaHomeJavac = Join-Path $env:JAVA_HOME "bin\javac.exe"
        if (Test-Path -LiteralPath $javaHomeJavac) {
            $javacCommand = Get-Item -LiteralPath $javaHomeJavac
        }
    }
    if ($null -eq $javacCommand) {
        throw "javac is required for local verification; set JAVA_HOME to a JDK or put javac on PATH"
    }

    $workDir = Join-Path $PWD ("target\java-directory-classpath-self-check-{0}-{1}" -f $PID, ([guid]::NewGuid().ToString("N")))
    New-Item -ItemType Directory -Force -Path (Join-Path $workDir "classes\codexclasspath") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $workDir "consumer") | Out-Null

    $producerSource = Join-Path $workDir "classes\codexclasspath\ClasspathProbe.java"
    $consumerSource = Join-Path $workDir "consumer\ClasspathConsumer.java"
    Set-Content -LiteralPath $producerSource -Encoding ASCII -Value "package codexclasspath; public final class ClasspathProbe {}"
    Set-Content -LiteralPath $consumerSource -Encoding ASCII -Value "import codexclasspath.ClasspathProbe; final class ClasspathConsumer { ClasspathProbe probe; }"

    & $javacCommand.FullName -d (Join-Path $workDir "classes") $producerSource
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed to compile the local classpath producer with exit code $LASTEXITCODE"
    }
    Remove-Item -LiteralPath $producerSource -Force

    $consumerOutput = & $javacCommand.FullName -classpath (Join-Path $workDir "classes") -d (Join-Path $workDir "consumer") $consumerSource 2>&1
    if ($LASTEXITCODE -ne 0) {
        $diagnostic = ($consumerOutput | Out-String).Trim()
        throw @"
Java directory classpath self-check failed.
javac=$($javacCommand.FullName)
JAVA_HOME=$env:JAVA_HOME
classpath=$(Join-Path $workDir "classes")
diagnostic=$diagnostic

This local environment cannot compile against classes from a directory classpath. Maven test compilation depends on target\classes, so running mvn test now would produce misleading package-not-found errors. Fix the local JDK/sandbox/toolchain first, then rerun scripts\verify_local.ps1.
"@
    }

    Write-Host "[java-directory-classpath-self-check] OK"
} finally {
    if (($null -ne $workDir) -and (Test-Path -LiteralPath $workDir)) {
        Remove-Item -LiteralPath $workDir -Recurse -Force
    }
    Pop-Location
}
