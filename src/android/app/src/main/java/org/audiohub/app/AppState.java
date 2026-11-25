package org.audiohub.app;

import android.content.Context;
import android.os.Build;
import org.audiohub.room.*;
import java.util.UUID;

final class AppState {
    static RoomEngine room;
    static AndroidAudio audio;
    static synchronized void init(Context context){if(room!=null)return;Context app=context.getApplicationContext();
        android.content.SharedPreferences prefs=app.getSharedPreferences("identity",Context.MODE_PRIVATE);String id=prefs.getString("deviceId",null);
        if(id==null){id=UUID.randomUUID().toString();prefs.edit().putString("deviceId",id).apply();}
        audio=new AndroidAudio(app);room=new RoomEngine(id,Build.MANUFACTURER+" "+Build.MODEL,audio,new AndroidBluetooth(app));
        try{room.loadSettings(new java.io.File(app.getFilesDir(),"settings.properties"));}catch(Exception e){room.report("设置加载失败，使用默认参数："+e.getMessage());}
    }
}
