# Inspect nested ZIP/JAR/APK entries without printing sensitive values.
. (Join-Path $PSScriptRoot 'env.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$privateValues=New-Object 'System.Collections.Generic.List[string]'
foreach($line in [IO.File]::ReadAllLines((Join-Path $RepoRoot '.env'))){
    if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){
        $key=$Matches[1];$value=$Matches[2]
        if($value.Length -ge 12 -and ($key -match 'PASSWORD|SECRET|TOKEN' -or $value -match '^[A-Za-z]:[\\/]')){
            $privateValues.Add($value);$privateValues.Add($value.Replace('/','\'));$privateValues.Add($value.Replace('\','/'))
        }
    }
}
function Test-PublicBytes([byte[]]$Bytes,[string]$Label,[int]$Depth=0){
    if($Depth -gt 4){throw 'Unexpected nested archive depth'}
    $text=[Text.Encoding]::UTF8.GetString($Bytes)+[Text.Encoding]::Unicode.GetString($Bytes)
    foreach($value in $privateValues){if($text.Contains($value)){throw "Private configuration value in $Label (value suppressed)"}}
    if($text -match '[A-Za-z]:[\\/]Users[\\/]' -or $text -match '-----BEGIN (RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'){throw "Private path/key signature in $Label"}
    if($Bytes.Length -gt 4 -and $Bytes[0] -eq 80 -and $Bytes[1] -eq 75){
        $stream=New-Object IO.MemoryStream(,$Bytes)
        $archive=New-Object IO.Compression.ZipArchive($stream,[IO.Compression.ZipArchiveMode]::Read)
        try{foreach($entry in $archive.Entries){
            if($entry.FullName -match '(^|/)(\.env($|/)|\.signing/|\.git/|local.properties$)|\.(p12|pfx|jks|keystore|log)$'){throw "Private/generated entry in $Label"}
            if($entry.Length -eq 0){continue}
            $entryStream=$entry.Open();$buffer=New-Object IO.MemoryStream
            try{$entryStream.CopyTo($buffer);Test-PublicBytes ($buffer.ToArray()) ($Label+'!'+$entry.FullName) ($Depth+1)}finally{$entryStream.Dispose();$buffer.Dispose()}
        }}finally{$archive.Dispose();$stream.Dispose()}
    }
}
foreach($name in @('AudioHub-0.9.6-Windows.zip','AudioHub-0.9.6-release.apk','AudioHub-0.9.6-public-source.zip')){
    Test-PublicBytes ([IO.File]::ReadAllBytes((Join-Path $RepoRoot ('dist/releases/'+$name)))) $name
}
$apk=[IO.Compression.ZipFile]::OpenRead((Join-Path $RepoRoot 'dist/releases/AudioHub-0.9.6-release.apk'))
try{
    foreach($name in @('index.html','app.js','style.css')){
        $expected=[IO.File]::ReadAllBytes((Join-Path $RepoRoot ('src/ui/'+$name)))
        $windows=[IO.File]::ReadAllBytes((Join-Path $RepoRoot ('dist/Windows/AudioHub-0.9.6/ui/'+$name)))
        $entry=$apk.GetEntry('assets/'+$name);if(-not $entry){throw 'Missing shared APK UI'}
        $entryStream=$entry.Open();$buffer=New-Object IO.MemoryStream
        try{$entryStream.CopyTo($buffer);$android=$buffer.ToArray()}finally{$entryStream.Dispose();$buffer.Dispose()}
        $sha=[Security.Cryptography.SHA256]::Create()
        try{$hash=[Convert]::ToBase64String($sha.ComputeHash($expected));if([Convert]::ToBase64String($sha.ComputeHash($windows)) -ne $hash -or [Convert]::ToBase64String($sha.ComputeHash($android)) -ne $hash){throw 'Shared UI delivery mismatch'}}finally{$sha.Dispose()}
    }
    foreach($name in @('LICENSE','DISCLAIMER.md','THIRD_PARTY_NOTICES.md')){if(-not $apk.GetEntry('assets/notices/'+$name)){throw 'Missing APK legal notices'}}
}finally{$apk.Dispose()}
Write-Output 'PASS source / Windows / Android shared UI hashes match; APK legal notices present.'
Write-Output 'PASS release privacy scan including nested archives: no configured secrets/local paths or private key files found.'
