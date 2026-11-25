# Explicit release allowlists; never archive the complete workspace or build reports.
. (Join-Path $PSScriptRoot 'env.ps1')
& node (Join-Path $PSScriptRoot 'privacy-check.cjs')
if($LASTEXITCODE -ne 0){throw 'Privacy gate failed'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$release=Join-Path $RepoRoot 'dist/releases'
New-Item -ItemType Directory -Path $release -Force | Out-Null
$stage=Join-Path $RepoRoot ('dist/release-stage-'+[Guid]::NewGuid().ToString('N'))
$windows=Join-Path $stage 'AudioHub-0.9.6-Windows'
New-Item -ItemType Directory -Path $windows -Force | Out-Null
$built=Join-Path $RepoRoot 'dist/Windows/AudioHub-0.9.6'
foreach($name in @('AudioHub-0.9.6.exe','audiohub_native.dll','engine.jar','libwinpthread-1.dll','WebView2Loader.dll','ui','licenses')){
    Copy-Item -LiteralPath (Join-Path $built $name) -Destination $windows -Recurse -Force
}
foreach($name in @('LICENSE','README.md','DISCLAIMER.md','THIRD_PARTY_NOTICES.md','docs')){Copy-Item -LiteralPath (Join-Path $RepoRoot $name) -Destination $windows -Recurse -Force}
$zip=Join-Path $release 'AudioHub-0.9.6-Windows.zip'
if(Test-Path -LiteralPath $zip){[IO.File]::Delete($zip)}
[IO.Compression.ZipFile]::CreateFromDirectory($windows,$zip,[IO.Compression.CompressionLevel]::Optimal,$true)
Copy-Item -LiteralPath (Join-Path $RepoRoot 'dist/Android/AudioHub-0.9.6-release.apk') -Destination $release -Force
foreach($name in @('LICENSE','DISCLAIMER.md','THIRD_PARTY_NOTICES.md')){Copy-Item -LiteralPath (Join-Path $RepoRoot $name) -Destination $release -Force}
& (Join-Path $PSScriptRoot 'export-public.ps1')
Copy-Item -LiteralPath (Join-Path $RepoRoot 'dist/AudioHub-0.9.6-public-source.zip') -Destination $release -Force
& (Join-Path $PSScriptRoot 'verify-release.ps1')
$sums=Get-ChildItem -LiteralPath $release -File | Where-Object Name -ne 'SHA256SUMS.txt' | Sort-Object Name | ForEach-Object {((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()+'  '+$_.Name)}
[IO.File]::WriteAllLines((Join-Path $release 'SHA256SUMS.txt'),$sums,(New-Object Text.UTF8Encoding($false)))
Write-Output 'PASS release archives and SHA256SUMS written to dist/releases; signing secrets remain private.'
