import org.audiohub.room.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** A real sustained 100 Hz source; nonzero PCM makes dropped/empty blocks observable. */
public class AudioTimingTests {
 static class Audio implements AudioPlatform {
  volatile Consumer<byte[]> capture;final AtomicInteger played=new AtomicInteger(),silent=new AtomicInteger();
  public List<Map<String,Object>> devices(){return Collections.emptyList();}
  public void start(boolean send,String source,List<String> outputs,Consumer<byte[]> c,Consumer<String> f){capture=c;}
  public void play(byte[] b){played.incrementAndGet();if(Pcm.peak(b)==0)silent.incrementAndGet();}
  public void outputGain(int i,float g){}public void stop(){}public String status(){return "timing test";}
 }
 public static void main(String[] args)throws Exception{
  Audio audio=new Audio();try(RoomEngine room=new RoomEngine("timing","Timing",audio)){
   room.host("127.0.0.1",0);room.startAudio(true,"source",Collections.singletonList("speaker"));
   byte[] pcm=new byte[Pcm.BYTES];for(int i=0;i<pcm.length;i+=2){pcm[i]=(byte)2000;pcm[i+1]=7;}
   ScheduledExecutorService producer=Executors.newSingleThreadScheduledExecutor();AtomicInteger sent=new AtomicInteger();
   try{producer.scheduleAtFixedRate(()->{audio.capture.accept(pcm);sent.incrementAndGet();},0,10,TimeUnit.MILLISECONDS);
    Thread.sleep(1200);int start=audio.played.get(),silent=audio.silent.get(),captured=sent.get();
    Thread.sleep(4000);int output=audio.played.get()-start,input=sent.get()-captured,gaps=audio.silent.get()-silent;
    System.out.println("Sustained audio: captured="+input+" output="+output+" silent="+gaps);
    if(output<input-8||gaps>2)throw new AssertionError("100 Hz input must not be dropped by slower mixer pacing or become silence");
    System.out.println("PASS sustained 100 Hz input/output cadence");
   }finally{producer.shutdownNow();}
  }
 }
}
