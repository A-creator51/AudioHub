package org.audiohub.desktop;

import org.audiohub.room.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;

/** Embedded loopback-only UI. No external server, Node, browser helper, or cloud. */
public final class Desktop {
    private static RoomEngine room;private static HttpServer http;private static ExecutorService executor;
    public static String open(String root,String nativeLibrary)throws Exception{
        System.load(nativeLibrary);
        Path config=Paths.get(System.getProperty("audiohub.config",Paths.get(System.getenv().getOrDefault("LOCALAPPDATA",System.getProperty("java.io.tmpdir")),"AudioHub-v0.9").toString()));
        Files.createDirectories(config);Path identityFile=config.resolve("device-id.txt");String stable;
        if(Files.exists(identityFile)){stable=Files.readString(identityFile).trim();UUID.fromString(stable);}
        else{stable=UUID.randomUUID().toString();Files.writeString(identityFile,stable,StandardOpenOption.CREATE_NEW);}
        room=new RoomEngine(stable,System.getenv().getOrDefault("COMPUTERNAME","Windows"),new WindowsAudio(),new WindowsBluetooth());room.loadSettings(config.resolve("settings.properties").toFile());Controller controller=new Controller(room);
        String token=TlsIdentity.hex(TlsIdentity.random(24));String prefix="/"+token+"/";
        Path assets=Paths.get(root,"ui").toAbsolutePath().normalize();
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);
        executor=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"local-ui");t.setDaemon(true);return t;});http.setExecutor(executor);
        http.createContext(prefix,exchange->{try{
            exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; frame-src 'none'; base-uri 'none'");
            String path=exchange.getRequestURI().getPath().substring(prefix.length());byte[] bytes;String type;
            if(path.equals("state")&&exchange.getRequestMethod().equals("GET")){bytes=room.snapshot().getBytes(StandardCharsets.UTF_8);type="application/json; charset=utf-8";}
            else if(path.equals("command")&&exchange.getRequestMethod().equals("POST")){
                byte[] body=exchange.getRequestBody().readNBytes(16385);if(body.length>16384)throw new IOException("请求过长");
                bytes=controller.command(new String(body,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);type="application/json; charset=utf-8";
            }else{if(path.isEmpty())path="index.html";if(!Arrays.asList("index.html","app.js","style.css").contains(path)){exchange.sendResponseHeaders(404,-1);return;}
                bytes=Files.readAllBytes(assets.resolve(path));type=path.endsWith(".js")?"text/javascript; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8";}
            exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Exception e){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}});
        http.start();return "http://127.0.0.1:"+http.getAddress().getPort()+prefix+"index.html";
    }
    public static void shutdown(){if(room!=null)room.close();if(http!=null)http.stop(0);if(executor!=null)executor.shutdownNow();}
}
