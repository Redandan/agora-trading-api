Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    $pomPath = "pom.xml"
    [xml]$pom = Get-Content -Raw $pomPath
    $ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
    $ns.AddNamespace("m", "http://maven.apache.org/POM/4.0.0")

    $projectArtifactId = $pom.SelectSingleNode("/m:project/m:artifactId", $ns).InnerText
    if ($projectArtifactId -ne "agora-trading-api") {
        throw "Unexpected project artifactId: $projectArtifactId"
    }

    $allowedAgoraDependency = "com.agora:agora-market-internal-client"
    $dependencies = $pom.SelectNodes("/m:project/m:dependencies/m:dependency", $ns)
    foreach ($dependency in $dependencies) {
        $groupId = $dependency.SelectSingleNode("m:groupId", $ns).InnerText
        $artifactId = $dependency.SelectSingleNode("m:artifactId", $ns).InnerText
        $coordinate = "${groupId}:${artifactId}"

        if ($groupId -eq "com.agora" -and $coordinate -ne $allowedAgoraDependency) {
            throw "Forbidden Agora dependency in trading split: $coordinate. Use the thin internal-client SDK only."
        }

        $systemPath = $dependency.SelectSingleNode("m:systemPath", $ns)
        if ($null -ne $systemPath) {
            throw "Forbidden systemPath dependency in trading split: $coordinate -> $($systemPath.InnerText)"
        }
    }

    $pomText = Get-Content -Raw $pomPath
    foreach ($forbidden in @("AgoraMarketAPI", "agora-market-api", "../AgoraMarketAPI", "..\AgoraMarketAPI")) {
        if ($pomText.Contains($forbidden)) {
            throw "Forbidden marketplace application reference in pom.xml: $forbidden"
        }
    }

    if (-not $pomText.Contains("agora-market-internal-client")) {
        throw "Missing allowed AgoraMarket internal-client dependency"
    }

    Write-Host "[pom-boundary] OK only allowed Agora dependency is $allowedAgoraDependency"
} finally {
    Pop-Location
}
