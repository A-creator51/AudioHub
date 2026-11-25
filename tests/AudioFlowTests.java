import org.audiohub.room.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Remote-only source: unlike dual-source tests, local sound cannot mask a broken uplink. */
public class AudioFlowTests {
 static class Audio implements AudioPlatform {
  Consumer<byte[]> capture;AtomicInteger nonzero=new AtomicInteger();
  public List<Map<String,Object>> devices(){return Arrays.asList(Json.object("id","media","name","Remote media","input",true,"output",false),Json.object("id","speaker","name","Speaker","input",false,"output",true));}
  public void start(boolean send,String source,List<String> outputs,Consumer<byte[]> captured,Consumer<String> fail){capture=captured;}
  public void play(byte[] b){if(Pcm.peak(b)>0)nonzero.incrementAndGet();}
  public void outputGain(int index,float gain){}public void stop(){}public String status(){return "test";}
 }
 static void require(boolean value,String message){if(!value)throw new AssertionError(message);System.out.println("PASS "+message);}
 static void until(BooleanSupplier test,String name)throws Exception{long deadline=System.currentTimeMillis()+6000;while(!test.getAsBoolean()&&System.currentTimeMillis()<deadline)Thread.sleep(20);require(test.getAsBoolean(),name);}
 static String code(RoomEngine r){String s=r.snapshot(),p="\"code\":\"";int start=s.indexOf(p)+p.length();return s.substring(start,s.indexOf('"',start));}
 public static void main(String[] args)throws Exception{
  Audio sink=new Audio(),source=new Audio();try(RoomEngine center=new RoomEngine("pc","PC",sink);RoomEngine phone=new RoomEngine("phone","Phone",source)){
   center.host("127.0.0.1",0);phone.join(code(center));until(()->phone.mode().equals("client"),"join without enabling audio");
   center.startAudio(false,"",Collections.singletonList("speaker"));phone.startAudio(true,"media",Collections.emptyList());until(phone::running,"remote source starts without local output");
   byte[] block=new byte[Pcm.BYTES];for(int i=0;i<block.length;i+=2){block[i]=(byte)2000;block[i+1]=(byte)(2000>>8);}for(int i=0;i<100;i++){source.capture.accept(block);Thread.sleep(10);}
   until(()->sink.nonzero.get()>30,"remote-only nonzero PCM reaches center output");
   require(phone.snapshot().contains("\"capturePackets\":100"),"sender exposes captured PCM count");
   require(center.snapshot().contains("\"source\":\"media\"")&&center.snapshot().contains("Remote media"),"recognized inactive/active endpoint inventory shared to center");
   require(center.snapshot().contains("\"outputPackets\":"),"output delivery diagnostics visible");
  }
 }
}
