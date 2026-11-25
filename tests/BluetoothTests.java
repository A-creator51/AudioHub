import org.audiohub.room.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Actual shared RFCOMM/TLS/codec/room code over loopback byte streams, not a radio test. */
public final class BluetoothTests {
    static int checks;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static void waitFor(BooleanSupplier test,String name)throws Exception{long end=System.nanoTime()+45_000_000_000L;while(!test.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);check(test.getAsBoolean(),name);}
    static String code(RoomEngine r){String s=r.snapshot(),key="\"code\":\"";int i=s.indexOf(key)+key.length();return s.substring(i,s.indexOf('"',i));}
    static final class Radio implements BluetoothPlatform {
        static final Map<String,Integer> ports=new ConcurrentHashMap<>();
        final List<Gate> writes=new CopyOnWriteArrayList<>();
        final String address;Radio(String a){address=a;}
        public Listener listen()throws Exception{ServerSocket s=new ServerSocket(0,12,InetAddress.getLoopbackAddress());ports.put(address,s.getLocalPort());return new Listener(){public String address(){return address;}public Link accept()throws Exception{Socket accepted=s.accept();Gate gate=new Gate();writes.add(gate);return wrap(accepted,gate);}public void close(){try{s.close();}catch(Exception ignored){}}};}
        public Link connect(String a)throws Exception{Socket s=new Socket("127.0.0.1",ports.get(a));return wrap(s);}
        static Link wrap(Socket s){return wrap(s,new Gate());}
        static Link wrap(Socket s,Gate gate){return new Link(){public InputStream input()throws IOException{return s.getInputStream();}public OutputStream output()throws IOException{OutputStream real=s.getOutputStream();return new OutputStream(){public void write(int b)throws IOException{write(new byte[]{(byte)b});}public void write(byte[] b,int off,int len)throws IOException{gate.await();real.write(b,off,len);}};}public void close(){gate.release();try{s.close();}catch(Exception ignored){}}};}
        public List<Map<String,Object>> paired(){return List.of(Json.object("address",address,"name","test"));}
    }
    static final class Gate {
        volatile boolean blocked;
        synchronized void await()throws IOException{while(blocked)try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
        synchronized void release(){blocked=false;notifyAll();}
    }
    static class Audio implements AudioPlatform {
        volatile Consumer<byte[]> capture;volatile int count,sample,audibleCount;volatile boolean send;float gain;
        public List<Map<String,Object>> devices(){return List.of(Json.object("id","media","name","Media","input",true,"output",false),Json.object("id","out","name","Output","input",false,"output",true));}
        public void start(boolean s,String src,List<String> out,Consumer<byte[]> c,Consumer<String> f){send=s;capture=c;}
        public void play(byte[] p){count++;sample=Pcm.sample(p,0);if(Pcm.peak(p)>.001f)audibleCount++;}
        public void outputGain(int i,float g){gain=g;}
        public void stop(){send=false;}
        public String status(){return "test";}
        void emit(){byte[] p=new byte[1920];for(int i=0;i<p.length;i+=2){p[i]=(byte)0xd0;p[i+1]=7;}if(send)capture.accept(p);}
    }
    static final class A2dp implements BluetoothPlatform {
        int refreshes;boolean connected;
        public Listener listen(){throw new UnsupportedOperationException();}
        public Link connect(String a){throw new UnsupportedOperationException();}
        public List<Map<String,Object>> paired(){return List.of();}
        public void a2dp(String action,String device){if(action.equals("refresh"))refreshes++;if(action.equals("connect"))connected=true;}
        public Map<String,Object> a2dpState(){return Json.object("supported",true,"connected",connected);}
        public void cancel(){connected=false;}
    }
    static void a2dpTests()throws Exception{
        A2dp platform=new A2dp();try(RoomEngine r=new RoomEngine("a2dp","A2DP",new Audio(),platform)){
            r.saveSettings(Map.of("a2dp","true"));r.a2dp("refresh","");r.a2dp("connect","source");
            try{r.a2dp("refresh","");throw new AssertionError("active A2DP refresh must not cancel connection");}catch(IllegalStateException expected){check(platform.refreshes==1&&platform.connected,"A2DP refresh rejects an active or connecting session before touching platform");}
            r.startAudio(false,"",List.of("out"));r.stopAudio();check(r.snapshot().contains("\"room\":{\"center\":\"a2dp\",\"centerName\":\"A2DP\",\"members\":[{\"id\":\"a2dp\",\"name\":\"A2DP\",\"send\":false" )&&r.snapshot().contains("\"inputs\":0,\"outputs\":0}"),"A2DP stop publishes disabled topology immediately");
        }
    }
    static void slowLinkTests()throws Exception{
        Radio radio=new Radio("44:22:33:44:55:66");Audio ha=new Audio(),sa=new Audio(),fa=new Audio();
        try(RoomEngine h=new RoomEngine("slow-host","Host",ha,radio);RoomEngine slow=new RoomEngine("slow","Slow",sa,new Radio("55:22:33:44:55:66"));RoomEngine fast=new RoomEngine("fast","Fast",fa,new Radio("66:22:33:44:55:66"))){
            h.hostBluetooth();waitFor(()->h.mode().equals("host"),"slow-link fixture host ready");slow.joinBluetooth(code(h),"");waitFor(()->slow.mode().equals("client"),"slow-link fixture first member authenticated");slow.startAudio(false,"",List.of("out"));waitFor(()->!slow.localOutputs().isEmpty(),"slow member playback configured");
            fast.joinBluetooth(code(h),"");waitFor(()->fast.mode().equals("client"),"healthy member authenticated");fast.startAudio(false,"",List.of("out"));waitFor(()->!fast.localOutputs().isEmpty(),"healthy member playback configured");h.startAudio(true,"media",List.of("out"));
            Gate congested=radio.writes.get(0);congested.blocked=true;
            try{for(int i=0;i<160;i++){ha.emit();Thread.sleep(10);}check(ha.audibleCount>100&&fa.audibleCount>80,"blocked Bluetooth writer does not block local mixer or healthy member");}
            finally{congested.release();}
            int before=sa.audibleCount;for(int i=0;i<70;i++){ha.emit();Thread.sleep(10);}check(sa.audibleCount>before,"slow stream resumes after write congestion clears");
            congested.blocked=true;try{for(int i=0;i<620;i++){ha.emit();Thread.sleep(10);}check(java.util.regex.Pattern.compile("发送排队=(?:[0-9]|1[0-9]|20)/20；发送丢旧帧=[1-9]").matcher(h.snapshot()).find(),"slow member bounded audio queue reports dropped old frames");waitFor(()->!h.snapshot().contains("\"id\":\"slow\""),"persistent control congestion removes slow member without closing room");check(h.snapshot().contains("控制发送队列已满")&&fast.mode().equals("client")&&h.mode().equals("host"),"congestion reason logged and healthy session survives");}finally{congested.release();}
        }
    }
    public static void main(String[] args)throws Exception{
        a2dpTests();
        byte[] pcm=new byte[1920];for(int i=0;i<480;i++){int v=(int)(10000*Math.sin(i*.03));for(int c=0;c<2;c++){pcm[i*4+c*2]=(byte)v;pcm[i*4+c*2+1]=(byte)(v>>8);}}
        byte[] encoded=BluetoothCodec.encode(pcm,pcm.length),decoded=BluetoothCodec.decode(encoded);check(encoded.length==488&&decoded.length==1920,"IMA1 bounded stereo framing");
        double error=0;for(int i=0;i<1920;i+=2)error+=Math.pow(Pcm.sample(pcm,i)-Pcm.sample(decoded,i),2);check(Math.sqrt(error/960)<700,"IMA1 sinusoid reconstruction error bounded");
        encoded[2]=(byte)255;try{BluetoothCodec.decode(encoded);throw new AssertionError();}catch(IllegalArgumentException expected){check(true,"malformed codec header rejected");}
        Audio ha=new Audio(),ca=new Audio();try(RoomEngine h=new RoomEngine("h","Host",ha,new Radio("11:22:33:44:55:66"));RoomEngine c=new RoomEngine("c","Client",ca,new Radio("22:22:33:44:55:66"))){
            h.hostBluetooth();waitFor(()->h.mode().equals("host"),"custom Bluetooth host uses shared room engine");String code=code(h);new BluetoothCode(code);
            c.joinBluetooth(code,"");waitFor(()->c.mode().equals("client"),"RFCOMM byte stream TLS fingerprint + room authorization + IMA1 negotiated");
            c.startAudio(true,"media",List.of());waitFor(c::sending,"Bluetooth sends independently without local playback");h.startAudio(false,"",List.of("out"));
            for(int i=0;i<100;i++){ca.emit();Thread.sleep(10);}waitFor(()->ha.audibleCount>0,"compressed remote input mixed to center output without physical intermediate");
            c.startAudio(true,"media",List.of("out"));waitFor(()->!c.localOutputs().isEmpty(),"Bluetooth duplex roles");
            h.startAudio(true,"media",List.of("out"));for(int i=0;i<40;i++){ha.emit();ca.emit();Thread.sleep(10);}waitFor(()->ca.audibleCount>0,"Bluetooth mixed output returns through encrypted compressed stream");
            c.startAudio(true,"media",List.of());waitFor(()->c.localOutputs().isEmpty(),"Bluetooth playback off preserves input");check(c.sending(),"Bluetooth input still active");
            h.inputGain("c",4);h.outputGain(0,4);h.saveSettings(Map.of("bluetoothRange","100","lanRange","800"));check(ha.gain==1,"Bluetooth range applies actual gain independently of LAN range");
            try{h.saveSettings(Map.of("a2dp","true"));throw new AssertionError("must require mode confirmation");}catch(IllegalArgumentException expected){check(h.mode().equals("host"),"unconfirmed mode switch preserves session");}
            try(RoomEngine bad=new RoomEngine("bad","Bad",new Audio(),new Radio("33:22:33:44:55:66"))){
                String[] p=code.split("\\|");p[2]="0".repeat(64);bad.joinBluetooth(String.join("|",p),"");waitFor(()->bad.mode().equals("idle"),"Bluetooth wrong certificate pin rejected");
                p=code.split("\\|");p[3]="0".repeat(32);bad.joinBluetooth(String.join("|",p),"");waitFor(()->bad.mode().equals("idle"),"Bluetooth wrong room secret rejected");
            }
            h.saveSettings(Map.of("a2dp","true","confirmMode","true"));waitFor(()->c.mode().equals("idle"),"confirmed switch cleans both ends");check(h.mode().equals("idle")&&!ha.send&&!ca.send,"no capture remains after switching");
        }
        try(RoomEngine lan=new RoomEngine("lan","LAN",new Audio())){lan.host("127.0.0.1",0);lan.saveSettings(Map.of("a2dp","true"));check(lan.mode().equals("host"),"A2DP preference does not interrupt unrelated LAN");try{lan.a2dp("connect","");throw new AssertionError();}catch(IllegalStateException expected){check(lan.mode().equals("host"),"A2DP connect cannot steal LAN");}}
        slowLinkTests();
        System.out.println("ALL "+checks+" Bluetooth stream checks passed; synthetic PCM, NO wireless hardware validation");
    }
}
