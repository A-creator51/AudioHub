package org.audiohub.desktop;

import org.audiohub.room.*;
import java.util.*;
import java.util.function.Consumer;

public final class WindowsAudio implements AudioPlatform {
    private volatile long generation;
    private volatile boolean active;
    public List<Map<String,Object>> devices(){
        List<Map<String,Object>> out=new ArrayList<>();String[] pairs=NativeAudio.devices();Set<String> render=new HashSet<>();
        for(int i=0;i+1<pairs.length;i+=2){out.add(Json.object("id",pairs[i],"name",pairs[i+1],"input",true,"output",true));render.add(pairs[i]);}
        pairs=NativeAudio.inputDevices();for(int i=0;i+1<pairs.length;i+=2)if(!render.contains(pairs[i]))out.add(Json.object("id",pairs[i],"name",pairs[i+1]+"（A2DP采集候选，隔离性未验证）","input",true,"output",false));
        return out;
    }
    public synchronized void start(boolean send,String source,List<String> outputs,Consumer<byte[]> captured,Consumer<String> failure){
        if(send&&source.isEmpty())throw new IllegalArgumentException("请选择输入声音所在的播放端点");
        if(send&&outputs.contains(source))throw new IllegalArgumentException("回绕保护：回采源端点不能同时作为 AudioHub 输出；请选择另一播放端点");
        NativeAudio.start(send,source,outputs.toArray(new String[0]));active=true;long token=++generation;
        Thread worker=new Thread(()->{long count=0;long next=System.nanoTime();while(active&&token==generation){try{
            if(send){byte[] b=NativeAudio.capture();if(b!=null&&b.length==Pcm.BYTES)captured.accept(b);}
            if(++count%100==0){String status=NativeAudio.status();if(status.contains("Failed")||status.contains("失败")){failure.accept("音频设备失效，已安全停止："+status);break;}}
            next+=10_000_000L;long now=System.nanoTime();if(now-next>30_000_000L)next=now;long remaining=next-now;if(remaining>0)java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        }catch(Exception e){failure.accept("音频异常："+e.getMessage());break;}}},"wasapi-capture-drain");worker.setDaemon(true);worker.start();
    }
    public void play(byte[] pcm){if(active)NativeAudio.play(pcm);}
    public void outputGain(int index,float gain){NativeAudio.gain(index,gain);}
    public synchronized void stop(){active=false;generation++;NativeAudio.stop();}
    public String status(){return NativeAudio.status();}
    public void configure(RoomSettings settings){NativeAudio.configure(settings.outputMs,settings.reserveMs,settings.inputMs,settings.mmcss);}
    public void diagnostics(RoomSettings settings){NativeAudio.diagnostics(settings.captureDetail,Arrays.asList("DEBUG","INFO","WARN","ERROR","OFF").indexOf(settings.logLevel));}
    public String nativeLogs(){return NativeAudio.logs();}
    public void clearNativeLogs(){NativeAudio.clearLogs();}
    public boolean supportsAutostart(){return true;}
    public boolean autostart(){return NativeAudio.autostart();}
    public void autostart(boolean enabled){NativeAudio.setAutostart(enabled);}
}
