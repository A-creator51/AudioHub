. (Join-Path $PSScriptRoot 'env.ps1')
& node (Join-Path $RepoRoot 'tests/run-ui.cjs')
if($LASTEXITCODE -ne 0){throw 'UI regression failed'}
