import org.audiohub.room.*;
import java.util.*;
import java.util.function.Consumer;
import java.nio.file.*;

public final class VolumeRangeTests {
    static final class Audio implements AudioPlatform {
        float gain;
        public List<Map<String,Object>> devices(){return Collections.emptyList();}
        public void start(boolean s,String src,List<String> o,Consumer<byte[]> c,Consumer<String> f){}
        public void play(byte[] p){}
        public void outputGain(int i,float g){gain=g;}
        public void stop(){}
        public String status(){return "test";}
    }
    public static void main(String[] args)throws Exception{
        Audio a=new Audio();try(RoomEngine r=new RoomEngine("h","Host",a)){
            r.host("127.0.0.1",0);r.startAudio(true,"source",List.of("out"));
            r.inputGain("h",2);r.outputGain(0,2);
            r.saveSettings(Map.of("lanRange","100"));
            if(a.gain>1)throw new AssertionError("lowering volume range must immediately clamp real output gain; actual="+a.gain);
            if(!r.snapshot().contains("\"gain\":1.0"))throw new AssertionError("input gain must be clamped on range change");
            try{r.outputGain(0,2);throw new AssertionError("backend must reject above-range gain");}catch(IllegalArgumentException expected){}
            System.out.println("PASS live input/output volume limits");
            r.saveSettings(Map.of("outputMs","400","reserveMs","200","inputMs","160","lanRange","400","bluetoothRange","800"));
            r.inputGain("h",3);r.outputGain(0,3);Controller c=new Controller(r);
            if(!c.command("action=volumeRange&path=lan&range=100").contains("\"ok\":true"))throw new AssertionError("live volume range command unavailable");
            if(a.gain!=1||!r.snapshot().contains("\"gain\":1.0"))throw new AssertionError("live range must clamp actual output and input gains");
            if(!r.snapshot().contains("\"outputMs\":400")||!r.snapshot().contains("\"bluetoothRange\":800"))throw new AssertionError("range-only change must preserve unrelated settings");
            r.outputGain(0,.7f);c.command("action=volumeRange&path=lan&range=800");if(a.gain!=.7f)throw new AssertionError("increasing range must not raise actual loudness");
            if(!c.command("action=volumeRange&path=bluetooth&range=100").contains("\"ok\":true")||a.gain!=.7f||!r.snapshot().contains("\"lanRange\":800"))throw new AssertionError("inactive path range must not alter active path gain/limit");
            if(!c.command("action=volumeRange&path=lan&range=300").contains("\"ok\":false")||!r.snapshot().contains("\"lanRange\":800"))throw new AssertionError("invalid range must preserve state");
            System.out.println("PASS dedicated live range clamps real gains, preserves unrelated settings, path isolation and safe increases");
        }
        Path config=Files.createTempDirectory("audiohub-range-test-").resolve("settings.properties");
        try{try(RoomEngine r=new RoomEngine("persist","Persist",new Audio())){r.loadSettings(config.toFile());r.saveSettings(Map.of("outputMs","400","bluetoothRange","800"));r.volumeRange("lan",100);RoomSettings saved=RoomSettings.load(config.toFile());if(saved.lanRange!=100||saved.bluetoothRange!=800||saved.outputMs!=400)throw new AssertionError("live range must persist independently");}
            try(RoomEngine r=new RoomEngine("reload","Reload",new Audio())){r.loadSettings(config.toFile());if(!r.snapshot().contains("\"lanRange\":100"))throw new AssertionError("live range missing after restart");}
            System.out.println("PASS live range independently persists and reloads after restart");
        }finally{Files.deleteIfExists(config);Files.deleteIfExists(config.getParent());}
    }
}
