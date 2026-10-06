param(
    [string]$Dotnet = 'dotnet',
    [string]$Version = '1.0.0.0',
    [string]$BuildId = ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)),
    [string]$CertificateThumbprint = ''
)
$ErrorActionPreference = 'Stop'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
if ($BuildId -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid build id' }
if ($Version -notmatch '^\d+\.\d+\.\d+\.\d+$') { throw 'Version must have four numeric parts' }
$sdkRoot = 'C:\Program Files (x86)\Windows Kits\10\bin'
$tools = Get-ChildItem -LiteralPath $sdkRoot -Directory | Sort-Object Name -Descending |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'x64\makeappx.exe') } | Select-Object -First 1
if (!$tools) { throw 'Windows SDK MakeAppx.exe not found' }
$makeappx = Join-Path $tools.FullName 'x64\makeappx.exe'
$signtool = Join-Path $tools.FullName 'x64\signtool.exe'
$destination = Join-Path $PSScriptRoot "dist\$BuildId"
$payload = Join-Path $destination 'payload'
$provider = Join-Path $payload 'Provider'
New-Item -ItemType Directory -Path $provider -Force | Out-Null
& $Dotnet run --project (Join-Path $PSScriptRoot 'Tests\Tests.csproj') -c Release
if ($LASTEXITCODE) { throw 'Core verification failed' }
& $Dotnet publish (Join-Path $PSScriptRoot 'Provider\Provider.csproj') -c Release -o $provider
if ($LASTEXITCODE) { throw 'Provider publish failed' }
New-Item -ItemType Directory -Path (Join-Path $payload 'Public') -Force | Out-Null
Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'Packaging\Public') | Copy-Item -Destination (Join-Path $payload 'Public') -Recurse -Force
$assets = Join-Path $payload 'Assets'
$process = Start-Process -FilePath (Join-Path $provider 'MCDevManager.Widgets.exe') -ArgumentList @('--generate-assets', ('"' + $assets + '"')) -WindowStyle Hidden -PassThru -Wait
if ($process.ExitCode) { throw "Preview generation failed: $($process.ExitCode)" }
$manifest = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Packaging\AppxManifest.xml') -Raw -Encoding UTF8)
$manifest.Package.Identity.Version = $Version
$manifest.Save((Join-Path $payload 'AppxManifest.xml'))
$package = Join-Path $destination "MCDevManager-Widgets-$Version-$BuildId.msix"
$packing = & $makeappx pack /d $payload /p $package /o 2>&1
if ($LASTEXITCODE) { $packing | Select-Object -Last 20 | Write-Host; throw 'MSIX manifest validation or packing failed' }
Write-Host 'MSIX manifest validated and packed.'
if ($CertificateThumbprint) {
    & $signtool sign /sha1 $CertificateThumbprint /fd SHA256 $package
    if ($LASTEXITCODE) { throw 'MSIX signing failed' }
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install.ps1') -Destination $destination
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\docs\WINDOWS_WIDGETS.md') -Destination $destination
$zip = Join-Path (Split-Path $destination -Parent) "MCDevManager-Widgets-$Version-$BuildId.zip"
Compress-Archive -LiteralPath $package,(Join-Path $destination 'install.ps1'),(Join-Path $destination 'WINDOWS_WIDGETS.md') -DestinationPath $zip -Force
Get-FileHash -LiteralPath $package,$zip -Algorithm SHA256 | Format-Table Path,Hash -AutoSize
Write-Host "MSIX: $package"
Write-Host "ZIP: $zip"
