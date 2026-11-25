package org.audiohub.app;

import android.Manifest;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.audiohub.room.*;
import java.io.*;
import java.util.*;

/** Public Android RFCOMM server/client. No hidden A2DP Sink APIs. */
final class AndroidBluetooth implements BluetoothPlatform {
    private final Context context;
    private volatile BluetoothSocket pending;
    AndroidBluetooth(Context c){context=c;}
    private BluetoothAdapter adapter()throws IOException{
        if(Build.VERSION.SDK_INT>=31&&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new IOException("请授予附近设备权限后重试");
        BluetoothManager manager=context.getSystemService(BluetoothManager.class);BluetoothAdapter a=manager==null?null:manager.getAdapter();
        if(a==null)throw new IOException("设备没有可用蓝牙适配器");if(!a.isEnabled())throw new IOException("请先在系统设置开启蓝牙");return a;
    }
    private static Link link(BluetoothSocket s){return new Link(){public InputStream input()throws IOException{return s.getInputStream();}public OutputStream output()throws IOException{return s.getOutputStream();}public void close(){try{s.close();}catch(IOException ignored){}}};}
    public Listener listen()throws Exception{
        if(Build.VERSION.SDK_INT>=31&&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new IOException("未授予附近设备权限");
        BluetoothServerSocket s=adapter().listenUsingRfcommWithServiceRecord("AudioHub AHB2 encrypted duplex room",UUID.fromString(SERVICE));
        return new Listener(){public String address(){return "PAIRED";}public Link accept()throws Exception{return link(s.accept());}public void close(){try{s.close();}catch(IOException ignored){}}};
    }
    public Link connect(String address)throws Exception{
        if(Build.VERSION.SDK_INT>=31&&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new IOException("未授予附近设备权限");
        BluetoothAdapter a=adapter();BluetoothDevice d=a.getRemoteDevice(address);if(d.getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("中心尚未配对，请先在系统蓝牙设置配对");
        BluetoothSocket s=d.createRfcommSocketToServiceRecord(UUID.fromString(SERVICE));pending=s;
        Thread timeout=new Thread(()->{try{Thread.sleep(12000);if(pending==s)s.close();}catch(Exception ignored){}},"rfcomm-connect-deadline");timeout.setDaemon(true);timeout.start();
        try{s.connect();return link(s);}catch(Exception e){s.close();throw e;}finally{if(pending==s)pending=null;}
    }
    public List<Map<String,Object>> paired()throws Exception{if(Build.VERSION.SDK_INT>=31&&context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new IOException("未授予附近设备权限");List<Map<String,Object>> list=new ArrayList<>();for(BluetoothDevice d:adapter().getBondedDevices()){if(list.size()>=128)break;list.add(Json.object("address",d.getAddress(),"name",d.getName()==null?"已配对设备":d.getName(),"paired",true));}return list;}
    public void cancel(){BluetoothSocket s=pending;if(s!=null)try{s.close();}catch(IOException ignored){}}
}
