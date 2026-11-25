import org.audiohub.desktop.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class DesktopSmoke {
    public static void main(String[] args)throws Exception{
        String url=Desktop.open(args[0],args[1]);
        try{
            String base=url.substring(0,url.lastIndexOf('/')+1);
            String state=new String(new URL(base+"state").openStream().readAllBytes(),StandardCharsets.UTF_8);
            if(!state.contains("\"running\":false")||!state.contains("\"mode\":\"idle\""))throw new AssertionError("unsafe startup");
            String page=new String(new URL(url).openStream().readAllBytes(),StandardCharsets.UTF_8);if(!page.contains("房间输入设备增益"))throw new AssertionError("UI missing");
            HttpURLConnection denied=(HttpURLConnection)new URL(new URL(base),"../state").openConnection();if(denied.getResponseCode()!=404)throw new AssertionError("capability path bypass");
            System.out.println("PASS JNI device enumeration / zero audio on startup / loopback HTTP / capability path / shared UI");
            // State can contain local device names, addresses and capability data: never publish it.
        }finally{Desktop.shutdown();}
    }
}
