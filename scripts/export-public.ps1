# Export ONLY files accepted by the privacy gate, not the full workspace.
. (Join-Path $PSScriptRoot 'env.ps1')
& node (Join-Path $PSScriptRoot 'privacy-check.cjs')
if($LASTEXITCODE -ne 0){throw 'Refusing export: privacy gate failed'}
$names=& git -C $RepoRoot -c core.quotepath=false ls-files --cached --others --exclude-standard
if($LASTEXITCODE -ne 0){throw 'Cannot list public Git candidates'}
$destination=Join-Path $RepoRoot ('dist/public-source-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $destination -Force | Out-Null
foreach($name in ($names | Sort-Object -Unique)){
    $file=Join-Path $RepoRoot $name
    if(-not(Test-Path -LiteralPath $file -PathType Leaf)){throw 'Public file disappeared during export'}
    $target=Join-Path $destination $name
    New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
    Copy-Item -LiteralPath $file -Destination $target
}
$archive=Join-Path $RepoRoot 'dist/AudioHub-0.9.6-public-source.zip'
# Include dot-files explicitly; some archive helpers omit .github/.gitignore.
if(Test-Path -LiteralPath $archive){[IO.File]::Delete($archive)}
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($destination,$archive,[IO.Compression.CompressionLevel]::Optimal,$false)
Write-Output 'Public source ZIP written to dist/AudioHub-0.9.6-public-source.zip (no .env, .git, private keys, caches or release binaries).'
