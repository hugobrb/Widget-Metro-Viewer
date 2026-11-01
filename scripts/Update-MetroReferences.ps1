[CmdletBinding()]
param([string]$PerimeterFile)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$assetPath = Join-Path $projectRoot 'app/src/main/assets/idfm_metro_stops.json'
$downloadPath = $null
$outputPath = Join-Path (Split-Path -Parent $assetPath) ([IO.Path]::GetRandomFileName())
try {
    if (-not $PerimeterFile) {
        $catalogPath = Join-Path $projectRoot 'app/src/main/assets/metro_catalog.json'
        $rows = ConvertFrom-Json ([IO.File]::ReadAllText($catalogPath, [Text.Encoding]::UTF8))
        $ids = @($rows.id | Sort-Object -Unique)
        $clauses = $ids | ForEach-Object { 'line="STIF:Line::' + $_.Replace('IDFM:', '') + ':"' }
        $query = [Uri]::EscapeDataString(($clauses -join ' OR '))
        $endpoint = 'https://data.iledefrance.fr/api/explore/v2.1/catalog/datasets/perimetre-des-donnees-temps-reel-disponibles-sur-la-plateforme-dechanges-stif/exports/json?where=' + $query
        $downloadPath = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
        Invoke-WebRequest -Uri $endpoint -OutFile $downloadPath -UseBasicParsing -TimeoutSec 90
        $PerimeterFile = $downloadPath
    }
    & node (Join-Path $PSScriptRoot 'Build-MetroReferences.cjs') $PerimeterFile $outputPath
    if ($LASTEXITCODE -ne 0) { throw 'Validation du perimetre en echec.' }
    if (Test-Path -LiteralPath $assetPath) {
        $backupPath = $outputPath + '.bak'
        [IO.File]::Replace($outputPath, $assetPath, $backupPath)
        Remove-Item -LiteralPath $backupPath -Force
    }
    else { [IO.File]::Move($outputPath, $assetPath) }
    Write-Host 'References mises a jour. Recompiler puis reinstaller l application.'
} finally {
    if ($downloadPath -and (Test-Path -LiteralPath $downloadPath)) { Remove-Item -LiteralPath $downloadPath -Force }
    if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath -Force }
}
