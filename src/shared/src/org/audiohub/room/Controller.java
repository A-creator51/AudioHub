package org.audiohub.room;

import java.net.URLDecoder;
import java.util.*;

/** Same command interface on both platforms. State reads do not mutate sessions. */
public class Controller {
    public final RoomEngine room;
    public Controller(RoomEngine room){this.room=room;}
    public synchronized String command(String form){
        try {
            Map<String,String> p=new HashMap<>();for(String pair:form.split("&")){String[] kv=pair.split("=",2);if(kv.length==2)p.put(URLDecoder.decode(kv[0],"UTF-8"),URLDecoder.decode(kv[1],"UTF-8"));}
            switch(p.getOrDefault("action","")){
                case "host":room.host(p.getOrDefault("address",""),Integer.parseInt(p.getOrDefault("port","47162")));break;
                case "join":room.join(p.getOrDefault("code",""));break;
                case "btHost":room.hostBluetooth();break;
                case "btJoin":room.joinBluetooth(p.getOrDefault("code",""),p.getOrDefault("device",""));break;
                case "btRefresh":room.refreshBluetooth();break;
                case "a2dp":room.a2dp(p.getOrDefault("operation","refresh"),p.getOrDefault("device",""));break;
                case "direct":room.direct(p.getOrDefault("operation","refresh"),p.getOrDefault("device",""),Boolean.parseBoolean(p.getOrDefault("confirmMode","false")));break;
                case "directCapture":room.directCapture(p.getOrDefault("operation","refresh"),p.getOrDefault("source",""));break;
                case "leave":room.leave();break;
                case "start":start(Boolean.parseBoolean(p.getOrDefault("send","false")),p.getOrDefault("source",""),p.getOrDefault("outputs","").isEmpty()?Collections.emptyList():Arrays.asList(p.get("outputs").split("\n")));break;
                case "stop":room.stopAudio();break;
                case "input":start(Boolean.parseBoolean(p.getOrDefault("send","false")),p.getOrDefault("source",""),room.localOutputs());break;
                case "outputs":start(room.sending(),room.localSource(),p.getOrDefault("outputs","").isEmpty()?Collections.emptyList():Arrays.asList(p.get("outputs").split("\n")));break;
                case "inputGain":room.inputGain(p.get("id"),Float.parseFloat(p.get("gain")));break;
                case "outputGain":room.outputGain(Integer.parseInt(p.get("index")),Float.parseFloat(p.get("gain")));break;
                case "clearLogs":room.clearLogs();break;
                case "refreshDevices":room.refreshDevices();break;
                case "saveSettings":room.saveSettings(p);break;
                case "volumeRange":room.volumeRange(p.getOrDefault("path",""),Integer.parseInt(p.getOrDefault("range","0")));break;
                case "selfTest":room.selfTest();break;
                default:throw new IllegalArgumentException("未知操作");
            }
            return "{\"ok\":true}";
        }catch(Exception e){room.report(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());return Json.encode(Json.object("ok",false,"error",e.getMessage()));}
    }
    protected void start(boolean send,String source,List<String> outputs)throws Exception{room.startAudio(send,source,outputs);}
}
