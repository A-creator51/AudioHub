import org.audiohub.room.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Same cadence assertion across the actual TLS uplink, mixer and TLS downlink. */
public class NetworkTimingTests {
 public static void main(String[] args)throws Exception{
  AudioTimingTests.Audio input=new AudioTimingTests.Audio(),output=new AudioTimingTests.Audio();
  try(RoomEngine center=new RoomEngine("center","Center",new AudioTimingTests.Audio());RoomEngine sender=new RoomEngine("sender","Test Tablet",input);RoomEngine receiver=new RoomEngine("receiver","Receiver",output)){
   center.host("127.0.0.1",0);String code=AudioFlowTests.code(center);sender.join(code);receiver.join(code);
   AudioFlowTests.until(()->sender.mode().equals("client")&&receiver.mode().equals("client"),"TLS sender and receiver joined");
   Controller control=new Controller(sender);control.command("action=input&send=true&source=media");new Controller(receiver).command("action=outputs&outputs=speaker");
   AudioFlowTests.until(()->sender.running()&&receiver.running(),"independent input and playback started through TLS");
   ScheduledExecutorService producer=Executors.newSingleThreadScheduledExecutor();byte[] pcm=new byte[Pcm.BYTES];for(int i=0;i<pcm.length;i+=2){pcm[i]=(byte)2000;pcm[i+1]=7;}AtomicInteger sent=new AtomicInteger();
   try{producer.scheduleAtFixedRate(()->{input.capture.accept(pcm);sent.incrementAndGet();},0,10,TimeUnit.MILLISECONDS);
    Thread.sleep(1200);int start=output.played.get(),silence=output.silent.get(),before=sent.get();
    Thread.sleep(4000);int received=output.played.get()-start,count=sent.get()-before,gaps=output.silent.get()-silence;
    System.out.println("TLS sustained audio: captured="+count+" receiver="+received+" silent="+gaps);
    AudioFlowTests.require(received>=count-8&&gaps<=2,"TLS 100 Hz stream keeps pace without manufactured silence");
    control.command("action=outputs&outputs=speaker");Thread.sleep(150);control.command("action=outputs&outputs=");Thread.sleep(150);
    AudioFlowTests.require(sender.sending()&&sender.localOutputs().isEmpty()&&sender.running(),"TLS playback off leaves sender enabled");
    control.command("action=input&send=false&source=media");Thread.sleep(150);AudioFlowTests.require(!sender.running()&&sender.mode().equals("client"),"TLS disabling final direction retains room");
   }finally{producer.shutdownNow();}
  }
 }
}
