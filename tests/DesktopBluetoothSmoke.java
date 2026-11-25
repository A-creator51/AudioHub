import org.audiohub.desktop.*;
import org.audiohub.room.*;

/** No audio, no remote connection, no pairing/default-device changes. */
public final class DesktopBluetoothSmoke {
    public static void main(String[] args)throws Exception{
        System.load(args[0]);WindowsBluetooth b=new WindowsBluetooth();
        try{
            System.out.println("Windows paired Bluetooth device count="+b.paired().size()+" (names/addresses withheld)");
            try(BluetoothPlatform.Listener listener=b.listen()){
                if(!listener.address().matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}"))throw new AssertionError("invalid radio address");
                System.out.println("PASS actual Windows RFCOMM listen / SDP registration / close (no remote peer or audio)");
            }
            b.a2dp("refresh","");long end=System.nanoTime()+12_000_000_000L;
            while(String.valueOf(b.a2dpState().get("status")).contains("正在")&&System.nanoTime()<end)Thread.sleep(50);
            System.out.println("A2DP enumeration status="+b.a2dpState().get("status"));
        }finally{b.cancel();}
    }
}
