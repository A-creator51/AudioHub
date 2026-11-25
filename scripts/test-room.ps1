param([string]$JavaRoot='')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'env.ps1')
if(-not $JavaRoot){$JavaRoot=Split-Path (Split-Path (Find-AudioHubTool 'javac.exe' 'JAVA_HOME'))}
$taskProject=$RepoRoot
$taskClasses=Join-Path $RepoRoot 'build/test-classes'
New-Item -ItemType Directory -Path $taskClasses -Force | Out-Null
$taskSources=@(Get-ChildItem (Join-Path $taskProject 'src/shared/src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$taskSources+=(Join-Path $taskProject 'tests\DirectConnectionTests.java')
& (Join-Path $JavaRoot 'bin\javac.exe') -encoding UTF-8 --release 17 -d $taskClasses @taskSources (Join-Path $taskProject 'tests\RoomTests.java') (Join-Path $taskProject 'tests\AudioFlowTests.java') (Join-Path $taskProject 'tests\AudioTimingTests.java') (Join-Path $taskProject 'tests\IndependentAudioTests.java') (Join-Path $taskProject 'tests\NetworkTimingTests.java') (Join-Path $taskProject 'tests\VolumeRangeTests.java') (Join-Path $taskProject 'tests\BluetoothTests.java')
if($LASTEXITCODE -ne 0){throw 'Test compilation failed'}
& (Join-Path $JavaRoot 'bin\java.exe') -ea -cp $taskClasses RoomTests
if($LASTEXITCODE -ne 0){throw 'Room tests failed'}
& (Join-Path $JavaRoot 'bin\java.exe') -ea -cp $taskClasses AudioFlowTests
if($LASTEXITCODE -ne 0){throw 'Remote-only audio flow tests failed'}
& (Join-Path $JavaRoot 'bin\java.exe') -ea -cp $taskClasses AudioTimingTests
if($LASTEXITCODE -ne 0){throw 'Sustained audio timing failed'}
& (Join-Path $JavaRoot 'bin\java.exe') -ea -cp $taskClasses IndependentAudioTests
if($LASTEXITCODE -ne 0){throw 'Independent input/output control failed'}
& (Join-Path $JavaRoot 'bin\java.exe') -ea -cp $taskClasses NetworkTimingTests
if($LASTEXITCODE -ne 0){throw 'TLS sustained audio timing failed'}
& (Join-Path $JavaRoot 'bin\java.exe') -ea -cp $taskClasses VolumeRangeTests
if($LASTEXITCODE -ne 0){throw 'Volume range regression failed'}
& (Join-Path $JavaRoot 'bin\java.exe') -ea -cp $taskClasses BluetoothTests
if($LASTEXITCODE -ne 0){throw 'Bluetooth stream regression failed'}
& (Join-Path $JavaRoot 'bin\java.exe') -ea -cp $taskClasses DirectConnectionTests
if($LASTEXITCODE -ne 0){throw 'Direct connection regression failed'}
