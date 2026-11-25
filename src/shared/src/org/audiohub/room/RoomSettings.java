package org.audiohub.room;

import java.io.*;
import java.util.*;

/** Persist configuration only, never room credentials or active sessions. */
public final class RoomSettings {
    public int outputMs=160,reserveMs=100,inputMs=100,lanRange=200,bluetoothRange=400;
    public boolean mmcss=true,captureDetail=false,autostart=false,followLog=true;
    public String logLevel="INFO";
    public boolean a2dp=false;
    public String bluetoothProtocol="RFCOMM",bluetoothCodec="IMA1";
    public RoomSettings(){}
    public RoomSettings(Map<String,String> p){
        outputMs=choice(p,"outputMs",160,100,160,240,400);reserveMs=choice(p,"reserveMs",100,40,60,100,160,200);
        inputMs=choice(p,"inputMs",100,60,80,100,120,160);lanRange=choice(p,"lanRange",200,100,200,400,800);bluetoothRange=choice(p,"bluetoothRange",400,100,200,400,800);
        mmcss=Boolean.parseBoolean(p.getOrDefault("mmcss","true"));captureDetail=Boolean.parseBoolean(p.getOrDefault("captureDetail","false"));
        autostart=Boolean.parseBoolean(p.getOrDefault("autostart","false"));followLog=Boolean.parseBoolean(p.getOrDefault("followLog","true"));
        logLevel=p.getOrDefault("logLevel","INFO");if(!Arrays.asList("DEBUG","INFO","WARN","ERROR","OFF").contains(logLevel))throw new IllegalArgumentException("日志级别无效");
        a2dp=Boolean.parseBoolean(p.getOrDefault("a2dp","false"));bluetoothProtocol=p.getOrDefault("bluetoothProtocol","RFCOMM");bluetoothCodec=p.getOrDefault("bluetoothCodec","IMA1");
        if(!bluetoothProtocol.equals("RFCOMM")||!bluetoothCodec.equals("IMA1"))throw new IllegalArgumentException("仅实现 RFCOMM + IMA1 48kHz 立体声；不支持此协议/编码");
    }
    private static int choice(Map<String,String> p,String key,int fallback,int... choices){int n=Integer.parseInt(p.getOrDefault(key,Integer.toString(fallback)));for(int v:choices)if(n==v)return n;throw new IllegalArgumentException("参数无效："+key);}
    public Map<String,Object> values(){return Json.object("outputMs",outputMs,"reserveMs",reserveMs,"inputMs",inputMs,"lanRange",lanRange,"bluetoothRange",bluetoothRange,"mmcss",mmcss,"captureDetail",captureDetail,"autostart",autostart,"followLog",followLog,"logLevel",logLevel,"a2dp",a2dp,"bluetoothProtocol",bluetoothProtocol,"bluetoothCodec",bluetoothCodec);}
    public static RoomSettings load(File file)throws IOException{if(file==null||!file.exists())return new RoomSettings();Properties p=new Properties();try(InputStream in=new FileInputStream(file)){p.load(in);}Map<String,String> m=new HashMap<>();for(String k:p.stringPropertyNames())m.put(k,p.getProperty(k));return new RoomSettings(m);}
    public void save(File file)throws IOException{if(file==null)return;File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建设置目录");Properties p=new Properties();values().forEach((k,v)->p.setProperty(k,String.valueOf(v)));File tmp=new File(parent,file.getName()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){p.store(out,"AudioHub configuration; no room credentials");out.getFD().sync();}try{java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}
}
