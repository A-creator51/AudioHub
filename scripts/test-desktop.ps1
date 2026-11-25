# No room creation or actual audio; log output is not published.
. (Join-Path $PSScriptRoot 'env.ps1')
$delivery=Join-Path $RepoRoot 'dist/Windows/AudioHub-0.9.6'
& (Join-Path $RepoRoot 'tests/window-fullscreen.ps1') -Exe (Join-Path $delivery 'AudioHub-0.9.6.exe')
$logPath=Join-Path $delivery 'startup.log'
$offset=if(Test-Path -LiteralPath $logPath){(Get-Item -LiteralPath $logPath).Length}else{0}
$start=New-Object Diagnostics.ProcessStartInfo
$start.FileName=Join-Path $delivery 'AudioHub-0.9.6.exe'
$start.Arguments='--ui-smoke'
$start.UseShellExecute=$false
$start.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
$probe=New-Object Diagnostics.Process
$probe.StartInfo=$start
try{
    if(-not $probe.Start()){throw 'Unable to start smoke probe'}
    if(-not $probe.WaitForExit(30000)){$probe.Kill();throw 'UI smoke timeout'}
    if($probe.ExitCode -ne 0){throw 'UI smoke exit code failed'}
}finally{$probe.Dispose()}
$bytes=[IO.File]::ReadAllBytes($logPath)
$currentLog=[Text.Encoding]::UTF8.GetString($bytes,[int]$offset,$bytes.Length-[int]$offset)
if($currentLog -notmatch 'webview controller ready' -or $currentLog -notmatch 'host shutdown' -or $currentLog -match 'FAILED|jvm load failed'){throw 'Current startup log failed validation'}
Write-Output 'PASS installed JVM / WebView2 / clean exit; only current-run log checked'
