import org.audiohub.room.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Shared command interface; no radio, system route or real audio changes. */
public final class DirectConnectionTests {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static final class Platform implements BluetoothPlatform {
        final BluetoothTests.Radio radio=new BluetoothTests.Radio("77:22:33:44:55:66");
        boolean supported=true,connected,fail;int refreshed,connections;
        public Listener listen()throws Exception{return radio.listen();}
        public Link connect(String a)throws Exception{return radio.connect(a);}
        public List<Map<String,Object>> paired(){return List.of();}
        public boolean a2dpSupported(){return supported;}
        public String reason(){return "此平台不支持公开 A2DP 接收";}
        public Map<String,Object> a2dpState(){return Json.object("supported",supported,"connected",connected,"devices",List.of(Json.object("id","source","name","Phone")));}
        public void a2dp(String action,String device)throws Exception{if(action.equals("refresh"))refreshed++;if(action.equals("connect")){if(fail)throw new IOException("simulated connect failure");connections++;connected=true;}}
        public void cancel(){connected=false;}
    }
    static boolean directEnabled(RoomEngine r){return r.snapshot().contains("\"a2dp\":true");}
    static final class MultiAudio extends BluetoothTests.Audio {
        @Override public List<Map<String,Object>> devices(){return List.of(Json.object("id","media","name","Loopback","input",true,"output",true),Json.object("id","out","name","Speaker","output",true),Json.object("id","out2","name","Headphones","output",true));}
    }
    static void captureTests()throws Exception{
        Platform platform=new Platform();MultiAudio audio=new MultiAudio();try(RoomEngine r=new RoomEngine("capture","Capture",audio,platform)){
            Controller c=new Controller(r);c.command("action=direct&operation=start&device=source");
            check(!r.sending()&&!r.running()&&platform.connected,"direct connection defaults to Windows system output without software capture/playback");
            r.volumeRange("bluetooth",100);check(r.mode().equals("a2dp")&&directEnabled(r)&&platform.connected,"live range preserves active direct connection and mode");
            check(c.command("action=directCapture&operation=start&source=media").contains("\"ok\":true")&&r.sending()&&r.localOutputs().isEmpty(),"capture start from direct card requires no local input checkbox or playback");
            r.startAudio(true,"media",List.of("out","out2"));
            check(c.command("action=directCapture&operation=stop").contains("\"ok\":true")&&!r.sending()&&r.localOutputs().size()==2&&platform.connected,"capture stop preserves multi-output route and Windows A2DP connection");
            check(c.command("action=directCapture&operation=start&source=out").contains("\"ok\":false")&&!r.sending()&&r.localOutputs().size()==2,"loopback rejection preserves existing multi-output playback");
            check(c.command("action=directCapture&operation=start&source=missing").contains("\"ok\":false")&&r.localOutputs().size()==2,"missing capture endpoint preserves playback");
            check(c.command("action=directCapture&operation=refresh").contains("\"ok\":true")&&platform.connected&&r.localOutputs().size()==2,"capture refresh enumerates audio devices without refreshing/disconnecting A2DP sources");
            c.command("action=directCapture&operation=start&source=media");
            check(r.sending()&&r.localOutputs().size()==2,"capture can restart with existing outputs");
            c.command("action=direct&operation=stop&confirmMode=true");check(!r.sending()&&!r.running(),"exit direct connection releases capture and playback");
            r.host("127.0.0.1",0);r.startAudio(true,"media",List.of("out"));
            check(c.command("action=directCapture&operation=stop").contains("\"ok\":false")&&r.sending(),"direct capture controls cannot stop unrelated LAN capture");
        }
    }
    public static void main(String[] args)throws Exception{
        captureTests();
        Platform p=new Platform();BluetoothTests.Audio audio=new BluetoothTests.Audio();
        try(RoomEngine r=new RoomEngine("direct","Direct",audio,p)){
            Controller c=new Controller(r);
            check(c.command("action=direct&operation=refresh").contains("\"ok\":true")&&p.refreshed==1&&!directEnabled(r),"refresh works without enabling A2DP or disabling software Bluetooth");
            r.hostBluetooth();BluetoothTests.waitFor(()->r.mode().equals("host"),"direct fixture Bluetooth room created");r.startAudio(true,"media",List.of("out"));
            check(c.command("action=direct&operation=start&device=source").contains("\"ok\":false")&&r.mode().equals("host")&&r.sending(),"unconfirmed direct start preserves active Bluetooth room/audio");
            check(c.command("action=direct&operation=start&device=missing&confirmMode=true").contains("\"ok\":false")&&r.sending(),"invalid source rejected before tearing down software Bluetooth");
            check(c.command("action=direct&operation=start&device=source&confirmMode=true").contains("\"ok\":true")&&r.mode().equals("a2dp")&&directEnabled(r)&&!audio.send,"confirmed direct start enables A2DP and releases custom room audio");
            check(c.command("action=btHost").contains("\"ok\":false")&&r.mode().equals("a2dp"),"software Bluetooth cannot run while direct connection active");
            r.startAudio(false,"",List.of("out"));r.saveSettings(Map.of("bluetoothRange","100"));
            check(r.mode().equals("a2dp")&&directEnabled(r)&&r.running(),"ordinary settings save does not silently exit direct connection");
            check(c.command("action=direct&operation=refresh").contains("\"ok\":false")&&p.refreshed==1,"active direct refresh rejected before touching native state");
            check(c.command("action=direct&operation=stop").contains("\"ok\":false")&&r.running(),"unconfirmed direct exit preserves session");
            check(c.command("action=direct&operation=stop&confirmMode=true").contains("\"ok\":true")&&r.mode().equals("idle")&&!directEnabled(r)&&!r.running()&&!p.connected,"direct exit releases audio and immediately restores custom Bluetooth availability");
            r.hostBluetooth();BluetoothTests.waitFor(()->r.mode().equals("host"),"custom Bluetooth can create room again after exit");r.leave();
            p.fail=true;check(c.command("action=direct&operation=start&device=source").contains("\"ok\":false")&&r.mode().equals("idle")&&!directEnabled(r),"native direct start failure restores software Bluetooth mode");p.fail=false;
            p.supported=false;r.hostBluetooth();BluetoothTests.waitFor(()->r.mode().equals("host"),"unsupported platform fixture ready");
            check(c.command("action=direct&operation=start&device=source&confirmMode=true").contains("\"ok\":false")&&r.mode().equals("host")&&!directEnabled(r),"unsupported A2DP cannot disable a working custom Bluetooth room");r.leave();p.supported=true;
            r.host("127.0.0.1",0);int before=p.connections;
            for(String operation:List.of("refresh","start","stop"))check(c.command("action=direct&operation="+operation+"&device=source&confirmMode=true").contains("\"ok\":false")&&r.mode().equals("host")&&p.connections==before,"direct "+operation+" never steals unrelated LAN room");
        }
        Path temp=Files.createTempDirectory("audiohub-direct-test-");File settings=temp.resolve("settings.properties").toFile();
        try{new RoomSettings(Map.of("a2dp","true")).save(settings);try(RoomEngine r=new RoomEngine("startup","Startup",new BluetoothTests.Audio(),new Platform())){r.loadSettings(settings);check(!directEnabled(r)&&r.mode().equals("idle")&&!r.running(),"legacy saved A2DP preference does not lock custom Bluetooth on next launch");}}
        finally{Files.deleteIfExists(settings.toPath());Files.deleteIfExists(temp);}
        System.out.println("ALL "+checks+" direct connection checks passed (no wireless/audio hardware)");
    }
}
