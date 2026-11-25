$ErrorActionPreference='Stop'
$RepoRoot=Split-Path $PSScriptRoot
function Import-AudioHubEnvironment {
    $envFile=Join-Path $RepoRoot '.env'
    if(Test-Path -LiteralPath $envFile){
        foreach($line in [IO.File]::ReadAllLines($envFile)){
            $value=$line.Trim()
            if(-not $value -or $value.StartsWith('#')){continue}
            if($value -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$'){throw 'Invalid .env syntax (values are never executed)'}
            $key=$Matches[1];$setting=$Matches[2].Trim()
            if(($setting.StartsWith('"') -and $setting.EndsWith('"')) -or ($setting.StartsWith("'") -and $setting.EndsWith("'"))){$setting=$setting.Substring(1,$setting.Length-2)}
            if($setting -and -not [Environment]::GetEnvironmentVariable($key,'Process')){[Environment]::SetEnvironmentVariable($key,$setting,'Process')}
        }
    }
}
function Find-AudioHubTool([string]$Name,[string]$RootVariable='') {
    $location=if($RootVariable){[Environment]::GetEnvironmentVariable($RootVariable,'Process')}else{''}
    if($location){$candidate=Join-Path $location ('bin/'+$Name);if(Test-Path -LiteralPath $candidate){return (Resolve-Path -LiteralPath $candidate).Path}}
    $found=Get-Command $Name -ErrorAction SilentlyContinue
    if($found){return $found.Source}
    throw "Missing tool $Name. Configure $RootVariable in local .env or add it to PATH."
}
Import-AudioHubEnvironment
