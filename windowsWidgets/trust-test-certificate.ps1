param(
    [Parameter(Mandatory = $true)][string]$CertificateFile,
    [Parameter(Mandatory = $true)][string]$ExpectedThumbprint
)
$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this certificate trust step in an elevated PowerShell session.'
}
$path = (Resolve-Path -LiteralPath $CertificateFile).Path
$certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($path)
try {
    if ($ExpectedThumbprint -notmatch '^[A-Fa-f0-9]{40}$' -or $certificate.Thumbprint -ne $ExpectedThumbprint) {
        throw 'Certificate thumbprint does not match the explicitly approved certificate.'
    }
    if ($certificate.Subject -ne 'CN=cluom.MCDevManager') { throw 'Unexpected certificate publisher.' }
    if ($certificate.NotBefore -gt (Get-Date) -or $certificate.NotAfter -le (Get-Date)) { throw 'Certificate is not currently valid.' }
    $usage = @($certificate.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.37' })
    if ($usage.Count -ne 1 -or $usage[0].EnhancedKeyUsages.Count -ne 1 -or $usage[0].EnhancedKeyUsages[0].Value -ne '1.3.6.1.5.5.7.3.3') {
        throw 'Only a code-signing certificate may be trusted by this helper.'
    }
    $constraints = @($certificate.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.19' })
    if ($constraints.Count -ne 1 -or $constraints[0].CertificateAuthority) { throw 'Certificate must be an end entity, not a CA.' }
    # TrustedPeople trusts this exact signing leaf; never import it into Root.
    $target = Join-Path 'Cert:\LocalMachine\TrustedPeople' $certificate.Thumbprint
    if (!(Test-Path -LiteralPath $target)) {
        Import-Certificate -FilePath $path -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
    }
    if (!(Test-Path -LiteralPath $target)) { throw 'Certificate trust verification failed.' }
    Write-Host 'MCDevManager test signing certificate trusted; Developer Mode remains unchanged.'
} finally {
    $certificate.Dispose()
}
