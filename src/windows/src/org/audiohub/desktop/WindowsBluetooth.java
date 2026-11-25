package org.audiohub.desktop;

import org.audiohub.room.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Public Winsock RFCOMM + WinRT A2DP adapter. No helper process or default route changes. */
public final class WindowsBluetooth implements BluetoothPlatform {
    private static native long listenNative();
    private static native String addressNative(long handle);
    private static native long acceptNative(long handle);
    private static native long connectNative(String address);
    private static native int readNative(long handle,byte[] buffer,int offset,int count);
    private static native void writeNative(long handle,byte[] buffer,int offset,int count);
    private static native void closeNative(long handle);
    private static native String[] pairedNative();
    private static native void a2dpNative(String action,String id);
    private static native String[] a2dpStateNative();
    private static final class Stream implements Link {
        final AtomicLong handle;
        Stream(long value){handle=new AtomicLong(value);}
        long value()throws IOException{long v=handle.get();if(v==0)throw new IOException("蓝牙连接已关闭");return v;}
        public InputStream input(){return new InputStream(){public int read()throws IOException{byte[] b=new byte[1];int n=read(b);return n<0?-1:b[0]&255;}public int read(byte[] b,int o,int n)throws IOException{Objects.checkFromIndexSize(o,n,b.length);return n==0?0:readNative(value(),b,o,n);}};}
        public OutputStream output(){return new OutputStream(){public void write(int b)throws IOException{write(new byte[]{(byte)b});}public void write(byte[] b,int o,int n)throws IOException{Objects.checkFromIndexSize(o,n,b.length);if(n>0)writeNative(value(),b,o,n);}};}
        public void close(){long v=handle.getAndSet(0);if(v!=0)closeNative(v);}
    }
    public Listener listen(){long h=listenNative();return new Listener(){final Stream stream=new Stream(h);public String address(){return addressNative(h);}public Link accept()throws Exception{return new Stream(acceptNative(stream.value()));}public void close(){stream.close();}};}
    public Link connect(String address){return new Stream(connectNative(address));}
    public List<Map<String,Object>> paired(){String[] pairs=pairedNative();List<Map<String,Object>> result=new ArrayList<>();for(int i=0;i+1<pairs.length;i+=2)result.add(Json.object("address",pairs[i],"name",pairs[i+1],"paired",true));return result;}
    public boolean a2dpSupported(){return true;}
    public String reason(){return "Windows AudioPlaybackConnection：需系统支持且已配对；系统直放，非独立隔离 PCM 接收";}
    public void a2dp(String action,String device){a2dpNative(action,device);}
    public Map<String,Object> a2dpState(){String[] p=a2dpStateNative();List<Map<String,Object>> devices=new ArrayList<>();for(int i=2;i+1<p.length;i+=2)devices.add(Json.object("id",p[i],"name",p[i+1]));return Json.object("supported",true,"reason",reason(),"status",p[0],"connected",p[1].equals("true"),"devices",devices);}
    public void cancel(){a2dpNative("disconnect","");}
}
