import org.audiohub.room.*;
import java.util.*;
import java.util.function.*;

public class IndependentAudioTests {
 static class Audio extends AudioTimingTests.Audio {
  boolean sending;List<String> outputs=Collections.emptyList();
  @Override public void start(boolean send,String source,List<String> out,Consumer<byte[]> c,Consumer<String> f){super.start(send,source,out,c,f);sending=send;outputs=new ArrayList<>(out);}
  @Override public void stop(){sending=false;outputs=Collections.emptyList();}
 }
 static void require(boolean b,String s){if(!b)throw new AssertionError(s);System.out.println("PASS "+s);}
 public static void main(String[] args)throws Exception{Audio a=new Audio();try(RoomEngine r=new RoomEngine("local","Local",a)){
  r.host("127.0.0.1",0);Controller c=new Controller(r);
  require(c.command("action=input&send=true&source=media").contains("\"ok\":true")&&a.sending&&a.outputs.isEmpty(),"send checkbox starts input without local output enable");
  require(c.command("action=outputs&outputs=media").contains("\"ok\":false")&&a.sending&&a.outputs.isEmpty(),"feedback rejection preserves already running input");
  require(c.command("action=outputs&outputs=speaker").contains("\"ok\":true")&&a.sending&&a.outputs.size()==1,"enable playback keeps sending");
  require(c.command("action=outputs&outputs=").contains("\"ok\":true")&&a.sending&&a.outputs.isEmpty(),"disable playback keeps sending");
  c.command("action=outputs&outputs=speaker");
  require(c.command("action=input&send=false&source=media").contains("\"ok\":true")&&!a.sending&&a.outputs.size()==1,"disable sending keeps playback");
  c.command("action=outputs&outputs=");require(!r.running(),"both disabled releases audio");
  long consent=r.audioRevision();r.leave();r.host("127.0.0.1",0);require(r.audioRevision()!=consent,"leave and rehost invalidate pending Android audio consent");
  consent=r.audioRevision();c.command("action=outputs&outputs=speaker");require(r.audioRevision()!=consent,"route changes invalidate stale audio consent");
  consent=r.audioRevision();r.stopAudio();require(r.audioRevision()!=consent,"stop invalidates pending audio consent even without leaving");
 }}
}
