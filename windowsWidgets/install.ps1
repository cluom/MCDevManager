param([switch]$Development, [string]$Package = '')
$ErrorActionPreference = 'Stop'
if ($Development) {
    # 开发者模式下从本地布局注册，不导入信任证书；稳定发布应使用已签名 MSIX。
    $manifest = Join-Path $PSScriptRoot 'payload\AppxManifest.xml'
    if (!(Test-Path -LiteralPath $manifest)) { throw 'Development payload not found' }
    Add-AppxPackage -Register $manifest
} else {
    if (!$Package) { $Package = (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.msix' | Select-Object -First 1).FullName }
    if (!$Package) { throw 'MSIX package not found' }
    Add-AppxPackage -Path $Package
}
Write-Host '安装完成：打开更新后的 MCDevManager 登录一次，再到 Win+W 添加数据小组件。'
