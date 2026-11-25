# Generates private LOCAL files. Never print passwords or put them on a command line.
param([string]$Developer='A-creator51')
. (Join-Path $PSScriptRoot 'env.ps1')
$envFile=Join-Path $RepoRoot '.env'
$keyFile=Join-Path $RepoRoot '.signing/audiohub-release.p12'
if(Test-Path -LiteralPath $keyFile){throw 'Signing key already exists; never overwrite it.'}
if(Test-Path -LiteralPath $envFile){throw '.env already exists. Preserve it and configure signing manually instead of overwriting.'}
if($Developer -notmatch '^[A-Za-z0-9_-]{1,64}$'){throw 'Use a public ASCII developer handle, not a real name or address.'}
New-Item -ItemType Directory -Path (Split-Path $keyFile) -Force | Out-Null
$keytool=Find-AudioHubTool 'keytool.exe' 'JAVA_HOME'
$bytes=New-Object byte[] 48
$rng=[Security.Cryptography.RandomNumberGenerator]::Create();$rng.GetBytes($bytes);$rng.Dispose()
$secret=[Convert]::ToBase64String($bytes);[Array]::Clear($bytes,0,$bytes.Length)
# PKCS12 uses one password; 48 random bytes supply 384 bits of generated entropy.
$env:AUDIOHUB_STORE_PASSWORD=$secret;$env:AUDIOHUB_KEY_PASSWORD=$secret
try {
    $beforePreference=$ErrorActionPreference
    $ErrorActionPreference='Continue'
    try{$output=& $keytool -genkeypair -noprompt -storetype PKCS12 -keystore $keyFile -alias audiohub-release -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 -dname ('CN='+$Developer) -storepass:env AUDIOHUB_STORE_PASSWORD -keypass:env AUDIOHUB_KEY_PASSWORD 2>&1;$keyExit=$LASTEXITCODE}finally{$ErrorActionPreference=$beforePreference}
    if($keyExit -ne 0){throw 'Key generation failed. Output is suppressed because paths may be private.'}
    $settings=@('# LOCAL PRIVATE CONFIGURATION - never upload this file.',('JAVA_HOME='+$env:JAVA_HOME),('ANDROID_HOME='+$env:ANDROID_HOME),('GRADLE_HOME='+$env:GRADLE_HOME),('GRADLE_USER_HOME='+$env:GRADLE_USER_HOME),('ANDROID_USER_HOME='+$env:ANDROID_USER_HOME),('MINGW_CXX='+$env:MINGW_CXX),('AUDIOHUB_PLAYWRIGHT_MODULE='+$env:AUDIOHUB_PLAYWRIGHT_MODULE),('AUDIOHUB_BROWSER_EXECUTABLE='+$env:AUDIOHUB_BROWSER_EXECUTABLE),'AUDIOHUB_KEYSTORE=.signing/audiohub-release.p12','AUDIOHUB_KEY_ALIAS=audiohub-release',('AUDIOHUB_STORE_PASSWORD='+$secret),('AUDIOHUB_KEY_PASSWORD='+$secret))
    [IO.File]::WriteAllLines($envFile,$settings,(New-Object Text.UTF8Encoding($false)))
    if($env:OS -eq 'Windows_NT'){
        $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
        foreach($file in @($envFile,$keyFile)){$acl=New-Object Security.AccessControl.FileSecurity;$acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false);$rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','Allow');$acl.AddAccessRule($rule);Set-Acl -LiteralPath $file -AclObject $acl}
    }
    Write-Output 'Created ignored .env and .signing/audiohub-release.p12 with owner-only permissions. Back up both privately; neither belongs in Git or release archives.'
} finally {$secret=$null;$output=$null}
