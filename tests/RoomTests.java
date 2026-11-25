import org.audiohub.room.*;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.atomic.*;

public final class RoomTests {
    static int checks;
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static void waitFor(BooleanSupplier condition,String name)throws Exception{long end=System.currentTimeMillis()+8000;while(!condition.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(20);check(condition.getAsBoolean(),name);}
    static final class Audio implements AudioPlatform {
        volatile Consumer<byte[]> capture;final AtomicInteger played=new AtomicInteger();volatile int sample;volatile boolean active;
        volatile RoomSettings configured;
        final AtomicInteger enumerations=new AtomicInteger();
        public List<Map<String,Object>> devices(){enumerations.incrementAndGet();return Collections.emptyList();}
        public void configure(RoomSettings settings){configured=settings;}
        public void start(boolean send,String source,List<String> outputs,Consumer<byte[]> c,Consumer<String> f){capture=c;active=true;}
        public void play(byte[] b){played.incrementAndGet();sample=Pcm.sample(b,0);}
        public void outputGain(int i,float g){}
        public void stop(){active=false;}
        public String status(){return active?"active":"idle";}
        void emit(int value){byte[] b=new byte[Pcm.BYTES];for(int i=0;i<b.length;i+=2){b[i]=(byte)value;b[i+1]=(byte)(value>>>8);}if(capture!=null)capture.accept(b);}
    }
    static String code(RoomEngine e)throws Exception{String s=e.snapshot();String prefix="\"code\":\"";int start=s.indexOf(prefix)+prefix.length();return s.substring(start,s.indexOf('"',start));}
    public static void main(String[] args)throws Exception{
        check(Json.encode("x\n\"\\").equals("\"x\\n\\\"\\\\\""),"JSON escaping");
        try{new RoomCode("AH1|wrong");throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"old/invalid code rejected");}
        byte[] full=new byte[Pcm.BYTES];Arrays.fill(full,(byte)127);byte[] mixed=Pcm.mix(Arrays.asList(full,full),Arrays.asList(1f,1f));check(Pcm.sample(mixed,0)==32767,"mix clipping bounded");
        Audio ha=new Audio(),ca=new Audio();RoomEngine host=new RoomEngine("host","Host",ha),client=new RoomEngine("client","Client",ca);
        java.io.File preferences=java.io.File.createTempFile("audiohub-settings-",".properties");preferences.deleteOnExit();
        List<RoomEngine> extras=new ArrayList<>();
        try{
            check(NetworkAddresses.priority("192.168.137.1","Wi-Fi")<NetworkAddresses.priority("100.64.0.10","VPN"),"hotspot prioritized before VPN");
            RoomSettings defaults=new RoomSettings();defaults.save(preferences);host.loadSettings(preferences);
            Map<String,String> changed=new HashMap<>();changed.put("outputMs","240");changed.put("reserveMs","160");changed.put("inputMs","120");changed.put("lanRange","800");changed.put("captureDetail","true");host.saveSettings(changed);
            check(RoomSettings.load(preferences).outputMs==240&&RoomSettings.load(preferences).inputMs==120,"settings persisted with real buffer values");
            check(!java.nio.file.Files.readString(preferences.toPath()).contains("AH2|"),"settings never persist room code");
            host.snapshot();int enumerations=ha.enumerations.get();host.snapshot();check(ha.enumerations.get()==enumerations,"state polling does not enumerate devices repeatedly");host.refreshDevices();check(ha.enumerations.get()==enumerations+1,"explicit device refresh reaches audio adapter");
            check(host.snapshot().contains("\"running\":false"),"startup zero capture/playback");host.host("127.0.0.1",0);String code=code(host);check(new RoomCode(code).port>0,"ephemeral TLS room code");
            host.selfTest();waitFor(()->host.snapshot().contains("本机连接自检通过"),"self test exercises TCP TLS and room authentication");
            host.report("test "+code);check(!host.snapshot().substring(host.snapshot().indexOf("\"logs\"")).contains(code),"room credentials redacted from errors and logs");
            try{host.join(code);throw new AssertionError();}catch(IllegalStateException expected){check(true,"single room invariant");}
            client.join(code);waitFor(()->client.mode().equals("client"),"real TLS client connected");waitFor(()->client.snapshot().contains("Host"),"topology distributed");
            host.startAudio(true,"source",Arrays.asList("host-out"));client.startAudio(true,"source",Arrays.asList("client-out"));waitFor(client::running,"bidirectional roles acknowledged");
            check(ha.configured.outputMs==240&&ha.configured.reserveMs==160,"saved settings applied to audio start");
            for(int i=0;i<25;i++){ha.emit(1000);ca.emit(2000);Thread.sleep(10);}
            waitFor(()->ha.played.get()>0&&ca.played.get()>0,"mix fan-out to host and network output");
            check(ha.sample>0&&ca.sample>0,"received real mixed PCM");
            int before=ca.played.get();client.stopAudio();check(client.mode().equals("client"),"stop audio preserves room");Thread.sleep(150);check(!client.running(),"client stopped safely");
            for(int i=0;i<5;i++){Audio audio=new Audio();RoomEngine extra=new RoomEngine("extra"+i,"Extra"+i,audio);extras.add(extra);extra.join(code);waitFor(()->extra.mode().equals("client"),"extra TLS client "+i);extra.startAudio(true,"source",Collections.singletonList("out"));if(i<4)waitFor(extra::running,"slot admitted "+i);else{Thread.sleep(300);check(!extra.running()&&extra.snapshot().contains("最多5"),"sixth input/output rejected by center");}}
            RoomEngine duplicate=new RoomEngine("host","Duplicate",new Audio());extras.add(duplicate);duplicate.join(code);waitFor(()->duplicate.mode().equals("idle"),"self identity connection rejected");
            RoomCode good=new RoomCode(code);RoomEngine bad=new RoomEngine("bad","Bad",new Audio());extras.add(bad);bad.join("AH2|127.0.0.1|"+good.port+"|"+"0".repeat(64)+"|"+good.secret);waitFor(()->bad.mode().equals("idle"),"wrong certificate pin rejected");
            host.leave();waitFor(()->client.mode().equals("idle"),"center exit tears down room");check(!ha.active&&!ca.active,"audio stopped on room teardown");check(before>0,"client output was active before stop");
            for(String address:RoomEngine.addresses()){
                host.host(address,0);client.join(code(host));waitFor(()->client.mode().equals("client"),"non-loopback interface TLS (address omitted)");client.leave();host.leave();
            }
            java.net.ServerSocket blocked=new java.net.ServerSocket(0);int unused=blocked.getLocalPort();blocked.close();RoomCode goodCode=new RoomCode(code);
            client.join("AH2|127.0.0.1|"+unused+"|"+goodCode.pin+"|"+goodCode.secret);waitFor(()->client.mode().equals("idle"),"closed port fails safely");check(client.snapshot().contains("TCP 连接"),"connection failure identifies TCP stage");
        }finally{client.close();host.close();for(RoomEngine e:extras)e.close();}
        System.out.println("ALL "+checks+" CHECKS PASSED (synthetic audio; no hardware playback)");
    }
}
