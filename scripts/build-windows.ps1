. (Join-Path $PSScriptRoot 'env.ps1')
$javac=Find-AudioHubTool 'javac.exe' 'JAVA_HOME'
$jar=Find-AudioHubTool 'jar.exe' 'JAVA_HOME'
if(-not $env:JAVA_HOME){$env:JAVA_HOME=Split-Path (Split-Path $javac)}
$compiler=if($env:MINGW_CXX){$env:MINGW_CXX}else{Find-AudioHubTool 'g++.exe'}
$delivery=Join-Path $RepoRoot 'dist/Windows/AudioHub-0.9.6'
$classes=Join-Path $RepoRoot ('build/windows/classes-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $delivery,$classes -Force | Out-Null
if(Test-Path -LiteralPath (Join-Path $delivery 'runtime')){throw 'This public build must not bundle a JVM; use a fresh delivery directory.'}
$sources=@(Get-ChildItem (Join-Path $RepoRoot 'src/shared/src'),(Join-Path $RepoRoot 'src/windows/src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& $javac -encoding UTF-8 --release 17 -d $classes @sources
if($LASTEXITCODE -ne 0){throw 'Java compile failed'}
& $jar --create --file (Join-Path $delivery 'engine.jar') -C $classes .
if($LASTEXITCODE -ne 0){throw 'Jar failed'}
$native='src/windows/native'
$includes=@(('-I'+(Join-Path $env:JAVA_HOME 'include')),('-I'+(Join-Path $env:JAVA_HOME 'include/win32')))
$nativeSources=@('audio_jni.cpp','bluetooth_jni.cpp','windows/a2dp_native.cpp','windows/capture.cpp','windows/output.cpp') | ForEach-Object {Join-Path $native $_}
Push-Location $RepoRoot
try {
    & $compiler -std=c++17 -O2 -Wall -Wextra -pthread '-ffile-prefix-map=.=AudioHub' -shared -static-libgcc -static-libstdc++ @includes @nativeSources -o 'dist/Windows/AudioHub-0.9.6/audiohub_native.dll' -lole32 -luuid -lwinpthread -ladvapi32 -lws2_32 -lbthprops -lruntimeobject
    if($LASTEXITCODE -ne 0){throw 'Native audio compile failed'}
    & $compiler -std=c++17 -O2 -Wall -Wextra -Wno-unknown-pragmas '-ffile-prefix-map=.=AudioHub' -municode -mwindows -static -static-libgcc -static-libstdc++ @includes '-Ithird_party/webview2/include' (Join-Path $native 'launcher.cpp') -o 'dist/Windows/AudioHub-0.9.6/AudioHub-0.9.6.exe' -lole32 -luuid -lshlwapi
    if($LASTEXITCODE -ne 0){throw 'Launcher compile failed'}
} finally {Pop-Location}
$web=Join-Path $RepoRoot 'third_party/webview2'
Copy-Item -LiteralPath (Join-Path $web 'x64/WebView2Loader.dll') -Destination $delivery -Force
Copy-Item -LiteralPath (Join-Path $RepoRoot 'src/ui') -Destination $delivery -Recurse -Force
Copy-Item -LiteralPath (Join-Path (Split-Path $compiler) 'libwinpthread-1.dll') -Destination $delivery -Force
foreach($name in @('LICENSE','THIRD_PARTY_NOTICES.md','README.md','DISCLAIMER.md')){Copy-Item -LiteralPath (Join-Path $RepoRoot $name) -Destination $delivery -Force}
Copy-Item -LiteralPath (Join-Path $RepoRoot 'third_party/licenses') -Destination $delivery -Recurse -Force
Copy-Item -LiteralPath (Join-Path $RepoRoot 'docs') -Destination $delivery -Recurse -Force
Copy-Item -LiteralPath (Join-Path $web 'LICENSE.txt'),(Join-Path $web 'NOTICE.txt') -Destination (Join-Path $delivery 'licenses') -Force
Write-Output 'Built dist/Windows/AudioHub-0.9.6. Install x64 Java 21 and set JAVA_HOME; JVM not bundled.'
