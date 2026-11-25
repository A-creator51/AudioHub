package org.audiohub.room;

import java.io.*;
import java.util.*;

/** RFCOMM stream seam; pairing is transport security, not room authorization. */
public interface BluetoothPlatform {
    String SERVICE="91b86d5a-b742-40cc-96a0-c970667bfda4";
    interface Link extends AutoCloseable {
        InputStream input()throws IOException;
        OutputStream output()throws IOException;
        void close();
    }
    interface Listener extends AutoCloseable {
        String address();
        Link accept()throws Exception;
        void close();
    }
    Listener listen()throws Exception;
    Link connect(String address)throws Exception;
    List<Map<String,Object>> paired()throws Exception;
    default boolean a2dpSupported(){return false;}
    default String reason(){return "Android 公开通用接口不支持 A2DP Sink；可使用软件自定义蓝牙";}
    default void a2dp(String action,String device)throws Exception{throw new IOException(reason());}
    default Map<String,Object> a2dpState(){return Json.object("supported",a2dpSupported(),"reason",reason(),"status","未连接","connected",false,"devices",Collections.emptyList());}
    default void cancel(){}
    BluetoothPlatform NONE=new BluetoothPlatform(){
        public Listener listen()throws Exception{throw new IOException("此平台未提供蓝牙适配器");}
        public Link connect(String a)throws Exception{throw new IOException("此平台未提供蓝牙适配器");}
        public List<Map<String,Object>> paired(){return Collections.emptyList();}
        public String reason(){return "此平台未提供蓝牙适配器";}
    };
}
