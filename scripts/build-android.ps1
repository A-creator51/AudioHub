param([switch]$Release,[switch]$Offline)
. (Join-Path $PSScriptRoot 'env.ps1')
$gradle=Find-AudioHubTool 'gradle.bat' 'GRADLE_HOME'
$project=Join-Path $RepoRoot 'src/android'
$mode=if($Release){'Release'}else{'Debug'}
$argsList=@('-p',$project,'--project-cache-dir',(Join-Path $RepoRoot 'build/android-cache'),'--no-daemon','--console=plain',('assemble'+$mode),('lint'+$mode))
if($Offline){$argsList+='--offline'}
if($Release){
    foreach($key in @('AUDIOHUB_STORE_PASSWORD','AUDIOHUB_KEY_PASSWORD','AUDIOHUB_KEY_ALIAS','AUDIOHUB_KEYSTORE')){if(-not [Environment]::GetEnvironmentVariable($key)){throw "Missing signing configuration: $key"}}
    if(-not [IO.Path]::IsPathRooted($env:AUDIOHUB_KEYSTORE)){$env:AUDIOHUB_KEYSTORE=Join-Path $RepoRoot $env:AUDIOHUB_KEYSTORE}
}
$beforePreference=$ErrorActionPreference
$ErrorActionPreference='Continue'
try{& $gradle @argsList;$buildExit=$LASTEXITCODE}finally{$ErrorActionPreference=$beforePreference}
if($buildExit -ne 0){throw 'Android build/lint failed'}
$flavor=$mode.ToLowerInvariant();$apk=Join-Path $RepoRoot "build/android/app/outputs/apk/$flavor/app-$flavor.apk"
$dest=Join-Path $RepoRoot "dist/Android/AudioHub-0.9.6-$flavor.apk"
New-Item -ItemType Directory -Path (Split-Path $dest) -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination $dest -Force
Write-Output "Built $flavor APK in dist/Android. Never publish build/, .env or .signing/."
