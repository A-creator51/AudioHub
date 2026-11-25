package org.audiohub.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.projection.*;
import android.os.*;
import java.util.*;

public final class RoomService extends Service {
    static final String STOP="org.audiohub.peer.STOP",AUDIO="org.audiohub.peer.AUDIO";
    private static final String CHANNEL="room";
    private PowerManager.WakeLock wake;
    @Override public void onCreate(){super.onCreate();AppState.init(this);getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"AudioHub 房间状态",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||STOP.equals(intent.getAction())){AppState.room.leave();stopSelf();return START_NOT_STICKY;}
        try{
            boolean audio=AUDIO.equals(intent.getAction()),send=audio&&intent.getBooleanExtra("send",false);
            final long revision=intent.getLongExtra("audioRevision",-1);
            synchronized(AppState.room){if(audio&&!currentAudio(revision)){AppState.room.report("音频请求已过期；未启动采集/播放");return START_NOT_STICKY;}}
            int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if(audio)types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if(send||AppState.room.sending())types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(90,notification(),types);
            if(wake==null){wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AudioHub:room");wake.acquire(4*60*60*1000L);}
            if(audio){Intent data=intent.getParcelableExtra("projectionData");int result=intent.getIntExtra("projectionResult",0);
                String source=intent.getStringExtra("source");ArrayList<String> outputs=intent.getStringArrayListExtra("outputs");
                new Thread(()->{synchronized(AppState.room){if(!currentAudio(revision)){AppState.room.report("房间或音频状态已改变；未启动过期采集/播放请求");return;}try{if(send&&!AppState.room.sending()){if(data==null||result!=Activity.RESULT_OK)throw new IllegalArgumentException("无有效采集授权");AppState.audio.projection(()->getSystemService(MediaProjectionManager.class).getMediaProjection(result,data));}AppState.room.authorizing(false);AppState.room.startAudio(send,source,outputs==null?Collections.emptyList():outputs);}catch(Exception e){AppState.audio.projection(null);AppState.room.authorizing(false);AppState.room.report(e.getMessage());}}},"start-audio").start();
            }
        }catch(Exception e){AppState.room.authorizing(false);AppState.room.report("前台音频启动失败："+e.getMessage());}
        return START_NOT_STICKY;
    }
    private boolean currentAudio(long revision){return revision==AppState.room.audioRevision()&&(AppState.room.mode().equals("host")||AppState.room.mode().equals("client"));}
    private Notification notification(){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,RoomService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("AudioHub 音频房间").setContentText("运行中 · 点击返回；停止将离开房间").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止并离开",stop).build()).build();
    }
    @Override public void onDestroy(){AppState.room.leave();if(wake!=null&&wake.isHeld())wake.release();wake=null;super.onDestroy();}
    @Override public void onTaskRemoved(Intent intent){stopSelf();}
    @Override public IBinder onBind(Intent intent){return null;}
}
