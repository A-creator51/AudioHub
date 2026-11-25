package org.audiohub.app;

import org.audiohub.room.*;
import android.content.Context;
import android.media.*;
import android.media.projection.MediaProjection;
import android.os.*;
import android.os.Process;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Playback capture excludes this UID. No microphone fallback, no root or drivers. */
final class AndroidAudio implements AudioPlatform {
    private final Context context;
    private final AudioManager manager;
    private MediaProjection permission;
    private Supplier<MediaProjection> pendingProjection;
    private AudioRecord recorder;
    private final List<AudioTrack> tracks=new ArrayList<>();
    private final List<Float> gains=new ArrayList<>();
    private volatile ArrayBlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(40);
    private volatile long captureGeneration,outputGeneration;
    private volatile boolean capturing,playing;
    private String captureSource="";
    private List<String> selectedOutputs=new ArrayList<>();
    private volatile String status="未开始音频";
    private volatile float outputGain=.25f;
    private int outputMs=160,reserveMs=100;
    private volatile long writtenPackets,recordedPackets,outputDrops,outputGaps;
    private volatile String routedOutput="尚无实际播放路由";
    AndroidAudio(Context c){context=c;manager=c.getSystemService(AudioManager.class);}
    public synchronized void configure(RoomSettings settings){outputMs=settings.outputMs;reserveMs=settings.reserveMs;}
    // Do not materialize a system projection until shared role/preflight checks have passed.
    synchronized void projection(Supplier<MediaProjection> factory){pendingProjection=factory;}
    public List<Map<String,Object>> devices(){List<Map<String,Object>> result=new ArrayList<>();
        result.add(Json.object("id","media","name","本机媒体声音（排除 AudioHub）","input",true,"output",false));
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))result.add(Json.object("id",Integer.toString(d.getId()),"name",d.getProductName()+" · "+d.getId(),"input",false,"output",true));
        return result;
    }
    private final MediaProjection.Callback revoked=new MediaProjection.Callback(){@Override public void onStop(){Consumer<String> f=failure;if(capturing&&f!=null)f.accept("系统已撤销媒体捕获授权，音频安全停止");}};
    private Consumer<String> failure;
    public synchronized void start(boolean send,String source,List<String> outputs,Consumer<byte[]> captured,Consumer<String> failed)throws Exception{
        MediaProjection consent=permission;Supplier<MediaProjection> factory=pendingProjection;pendingProjection=null;failure=failed;
        try{
            // Android public preferred-device routing is not an independent multi-output guarantee.
            if(outputs.size()>1)throw new IllegalArgumentException("Android 本机仅支持一个播放路由；全房间仍支持5路输出");
            boolean changeOutputs=!outputs.equals(selectedOutputs),changeCapture=send!=capturing||(send&&!source.equals(captureSource));
            for(String key:outputs){boolean found=false;for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(Integer.toString(d.getId()).equals(key))found=true;if(!found)throw new IllegalArgumentException("输出设备已移除，请重新选择");}
            if(changeOutputs)stopOutput();
            for(String key:changeOutputs?outputs:Collections.<String>emptyList()){AudioDeviceInfo selected=null;for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(Integer.toString(d.getId()).equals(key))selected=d;
                if(selected==null)throw new IllegalArgumentException("输出设备已移除，请重新选择");
                AudioFormat format=new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
                int minimum=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack track=new AudioTrack.Builder().setAudioFormat(format).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build()).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum*2,192*outputMs)).build();
                tracks.add(track);gains.add(.25f);if(track.getState()!=AudioTrack.STATE_INITIALIZED||!track.setPreferredDevice(selected))throw new IllegalStateException("系统拒绝所选播放路由");
                track.setVolume(1f);outputGain=.25f;
            }
            if(changeCapture&&!send)stopCapture();
            if(changeCapture&&send){if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("媒体捕获权限已被撤销");if(consent==null&&factory!=null)consent=factory.get();if(consent==null)throw new IllegalStateException("请重新授权媒体捕获");permission=consent;consent.registerCallback(revoked,new Handler(Looper.getMainLooper()));
                AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(consent).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build();
                AudioFormat format=new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
                int minimum=AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT);
                recorder=new AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config).setBufferSizeInBytes(Math.max(minimum*2,19200)).build();
                if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("48kHz媒体捕获初始化失败");recorder.startRecording();
            }
            status="音频路由运行中 · 输入发送/本机播放独立 · 排除本应用UID";
            if(changeCapture&&send){capturing=true;captureSource=source;long token=++captureGeneration;AudioRecord current=recorder;Thread thread=new Thread(()->{try{Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);while(capturing&&token==captureGeneration){byte[] b=new byte[Pcm.BYTES];int offset=0;while(offset<b.length&&capturing&&token==captureGeneration){int n=current.read(b,offset,b.length-offset,AudioRecord.READ_BLOCKING);if(n<=0)throw new IllegalStateException("媒体捕获失败 "+n);offset+=n;}if(offset==b.length&&token==captureGeneration){recordedPackets++;captured.accept(b);}}}catch(Exception e){if(capturing&&token==captureGeneration)failed.accept(e.getMessage());}finally{current.release();}},"android-capture");thread.setDaemon(true);thread.start();}
            if(changeOutputs&&!tracks.isEmpty()){
                playing=true;selectedOutputs=new ArrayList<>(outputs);routedOutput="等待系统实际路由";
                long token=++outputGeneration;List<AudioTrack> current=new ArrayList<>(tracks);
                ArrayBlockingQueue<byte[]> pending=queue;int reserve=reserveMs/10;
                Thread thread=new Thread(()->{try{
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);boolean primed=false;
                    for(AudioTrack t:current)t.play();
                    while(playing&&token==outputGeneration){
                        if(!primed){if(pending.size()<reserve){Thread.sleep(5);continue;}primed=true;}
                        byte[] b=pending.poll(200,TimeUnit.MILLISECONDS);
                        if(token!=outputGeneration)break;
                        if(b==null){primed=false;outputGaps++;continue;}
                        b=Pcm.gain(b,outputGain);
                        for(AudioTrack t:current){int offset=0;
                            while(offset<b.length&&playing&&token==outputGeneration){
                                int n=t.write(b,offset,b.length-offset,AudioTrack.WRITE_BLOCKING);
                                if(n<=0)throw new IllegalStateException("播放设备写入失败 "+n);offset+=n;
                            }
                            if(offset==b.length){writtenPackets++;AudioDeviceInfo actual=t.getRoutedDevice();routedOutput=actual==null?"系统尚未报告实际路由":actual.getProductName()+" · "+actual.getId();}
                        }
                    }
                }catch(Exception e){if(playing&&token==outputGeneration)failed.accept(e.getMessage());}
                finally{for(AudioTrack t:current){try{t.stop();}catch(Exception ignored){}t.release();}}},"android-output");
                thread.setDaemon(true);thread.start();
            }
        }catch(Exception e){if(consent!=null&&permission!=consent)consent.stop();stop();throw e;}
    }
    public void play(byte[] pcm){if(playing&&!queue.offer(pcm)){queue.poll();queue.offer(pcm);outputDrops++;}}
    public synchronized void outputGain(int index,float gain){if(index<0||index>=tracks.size())throw new IllegalArgumentException("输出不存在");if(!Float.isFinite(gain)||gain<0||gain>8)throw new IllegalArgumentException("输出增益范围为0–800%");gains.set(index,gain);outputGain=gain;}
    private void stopCapture(){capturing=false;captureGeneration++;captureSource="";if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder=null;}if(permission!=null){permission.unregisterCallback(revoked);permission.stop();permission=null;}}
    private void stopOutput(){playing=false;outputGeneration++;queue=new ArrayBlockingQueue<>(40);for(AudioTrack t:tracks)try{t.pause();t.flush();}catch(Exception ignored){}tracks.clear();gains.clear();selectedOutputs.clear();routedOutput="本机播放未启用";}
    public synchronized void stop(){pendingProjection=null;stopCapture();stopOutput();status="音频已停止";}
    public String status(){return status+"\n输入发送="+capturing+" / 本机播放="+playing+"\nAndroid 采集读取="+recordedPackets+" 包 / AudioTrack 已写="+writtenPackets+" 包 / 播放排队="+queue.size()+" 包 / 丢旧帧="+outputDrops+" / 断流="+outputGaps+"\n实际播放路由："+routedOutput+"\n系统媒体音量="+manager.getStreamVolume(AudioManager.STREAM_MUSIC)+"/"+manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);}
}
