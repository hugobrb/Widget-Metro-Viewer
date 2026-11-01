[CmdletBinding()]
param(
    # Valide le JSON existant sans telechargement ni modification.
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$catalogPath = Join-Path $projectRoot 'app/src/main/assets/metro_catalog.json'
$downloadPath = $null
$endpoint = 'https://data.iledefrance-mobilites.fr/api/explore/v2.1/catalog/datasets/arrets-lignes/exports/json?where=mode%3D%22Metro%22'

try {
    if ($ValidateOnly) {
        $candidatePath = $catalogPath
    } else {
        Write-Host 'Telechargement du catalogue officiel IDFM...'
        $downloadPath = Join-Path (Split-Path -Parent $catalogPath) ([IO.Path]::GetRandomFileName())
        Invoke-WebRequest -Uri $endpoint -OutFile $downloadPath -UseBasicParsing -TimeoutSec 60
        $candidatePath = $downloadPath
    }

    $json = [IO.File]::ReadAllText($candidatePath, [Text.Encoding]::UTF8)
    if (-not $json.TrimStart().StartsWith('[')) { throw 'La reponse doit etre un tableau JSON.' }
    $rows = ConvertFrom-Json -InputObject $json
    if ($rows.Count -eq 0) { throw 'Le catalogue est vide.' }
    foreach ($row in $rows) {
        if ($row.mode -ne 'Metro' -or
            $row.id -notmatch '^IDFM:.+' -or
            $row.stop_id -notmatch '^IDFM:.+' -or
            [string]::IsNullOrWhiteSpace($row.shortname) -or
            [string]::IsNullOrWhiteSpace($row.stop_name)) {
            throw 'Une entree est invalide : mode, identifiants ou noms manquants.'
        }
    }
    $lineCount = @($rows | Select-Object -ExpandProperty id -Unique).Count
    if ($ValidateOnly) {
        Write-Host "JSON valide : $lineCount lignes, $($rows.Count) enregistrements de quais."
    } else {
        # Le remplacement ne se produit qu'apres validation, dans le meme dossier.
        if (Test-Path -LiteralPath $catalogPath) {
            $backupPath = $downloadPath + '.bak'
            [IO.File]::Replace($downloadPath, $catalogPath, $backupPath)
            Remove-Item -LiteralPath $backupPath -Force
        } else {
            [IO.File]::Move($downloadPath, $catalogPath)
        }
        $downloadPath = $null
        Write-Host "Catalogue mis a jour : $lineCount lignes, $($rows.Count) enregistrements de quais."
        Write-Host "Fichier : $catalogPath"
        Write-Host 'Recompile puis reinstalle l application pour diffuser cette version du catalogue.'
    }
} catch {
    Write-Host "Echec : $($_.Exception.Message)" -ForegroundColor Red
    Write-Host 'Le catalogue du projet a ete conserve.'
    exit 1
} finally {
    if ($downloadPath -and (Test-Path -LiteralPath $downloadPath)) {
        Remove-Item -LiteralPath $downloadPath -Force
    }
}
