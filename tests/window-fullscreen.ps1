param([Parameter(Mandatory=$true)][string]$Exe)
$ErrorActionPreference='Stop'
Add-Type @'
using System;using System.Runtime.InteropServices;
public static class WindowProbe {
 public delegate bool EnumProc(IntPtr w,IntPtr arg);
 [DllImport("user32.dll")]public static extern bool EnumWindows(EnumProc p,IntPtr a);
 [DllImport("user32.dll")]public static extern uint GetWindowThreadProcessId(IntPtr w,out uint pid);
 public static IntPtr Find(int process){IntPtr result=IntPtr.Zero;EnumWindows((w,a)=>{uint pid;GetWindowThreadProcessId(w,out pid);if(pid==process){result=w;return false;}return true;},IntPtr.Zero);return result;}
 [DllImport("user32.dll")]public static extern long GetWindowLongPtr(IntPtr w,int index);
 [DllImport("user32.dll")]public static extern IntPtr SendMessage(IntPtr w,uint m,IntPtr a,IntPtr b);
}
'@
$taskProcess=Start-Process -FilePath $Exe -WindowStyle Hidden -PassThru
try{
 for($i=0;$i -lt 100;$i++){Start-Sleep -Milliseconds 100;$taskWindow=[WindowProbe]::Find($taskProcess.Id);if($taskWindow -ne 0){break}}
 if($taskWindow -eq 0){throw 'No test window'}
 [WindowProbe]::SendMessage($taskWindow,0x100,[IntPtr]0x7A,[IntPtr]0)|Out-Null
 $taskStyle=[WindowProbe]::GetWindowLongPtr($taskWindow,-16)
 if(($taskStyle -band 0x00CF0000) -ne 0){throw 'F11 full screen retains caption/frame (expected borderless)'}
 [WindowProbe]::SendMessage($taskWindow,0x100,[IntPtr]0x1B,[IntPtr]0)|Out-Null
 $taskRestored=[WindowProbe]::GetWindowLongPtr($taskWindow,-16)
 if(($taskRestored -band 0x00CF0000) -eq 0){throw 'Escape did not restore window frame'}
 Write-Output 'PASS F11 borderless fullscreen / Escape restores frame'
}finally{$taskWindow=[WindowProbe]::Find($taskProcess.Id);if($taskWindow -ne 0){[WindowProbe]::SendMessage($taskWindow,0x10,[IntPtr]0,[IntPtr]0)|Out-Null};$taskProcess.WaitForExit(10000)|Out-Null}
