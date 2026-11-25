package org.audiohub.room;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.*;

/** Shared Android/desktop room module. One room, one mixer, bounded per-peer queues. */
public final class RoomEngine implements AutoCloseable {
    private static final int MAGIC=0x41483232,MAX_PACKET=32768;
    private final String id,name;
    private final AudioPlatform audio;
    private final BluetoothPlatform bluetooth;
    private String transport="lan",btStatus="尚未刷新蓝牙设备";
    private List<Map<String,Object>> pairedDevices=new ArrayList<>();
    private BluetoothPlatform.Listener btServer;
    private final Set<BluetoothPlatform.Link> btLinks=ConcurrentHashMap.newKeySet();
    private RoomSettings settings=new RoomSettings();
    private File settingsFile;
    private List<Map<String,Object>> devices;
    private final List<String> logs=new ArrayList<>();
    private final Map<String,Member> members=new LinkedHashMap<>();
    private final Set<SSLSocket> sockets=ConcurrentHashMap.newKeySet();
    private final Semaphore handshakes=new Semaphore(12);
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"room-mix");t.setDaemon(true);return t;});
    private volatile String mode="idle",error="",topology="{}",code="";
    private volatile boolean closed,running,authorizing;
    private volatile Peer upstream;
    private SSLServerSocket server;
    private TlsIdentity identity;
    private String secret;
    private volatile long generation;
    private long mixed,ticks,audioGeneration;
    private long nextMix;
    private long capturePackets,captureAudible,outputPackets,outputAudible;
    private float capturePeak,outputPeak;
    private final Member local;
    private final Map<String,Float> outputGains=new HashMap<>();
    private float gainLimit(){return (transport.equals("lan")?settings.lanRange:settings.bluetoothRange)/100f;}
    private void clampGains(){float max=gainLimit();for(Member m:members.values())m.gain=Math.min(max,m.gain);for(int i=0;i<local.outputs.size();i++){String key=local.outputs.get(i);float g=Math.min(max,outputGains.getOrDefault(key,.25f));outputGains.put(key,g);audio.outputGain(i,g);}publish();}
    private static final class Member {
        final String id,name;
        boolean send;
        String source="";
        List<Map<String,Object>> devices=new ArrayList<>();
        boolean ready;
        List<String> outputs=new ArrayList<>();
        float gain=1,peak;
        long drops,gaps,received,audible;
        boolean primed;
        int prebuffer=10;
        final ArrayBlockingQueue<byte[]> pcm=new ArrayBlockingQueue<>(24);
        Peer peer;
        Member(String id,String name){this.id=id;this.name=name;}
        synchronized void offer(byte[] b){if(!pcm.offer(b)){pcm.poll();pcm.offer(b);drops++;}peak=Pcm.peak(b);received++;if(peak>.0001f)audible++;}
        synchronized byte[] read(){if(!primed){if(pcm.size()<prebuffer)return null;primed=true;}byte[] b=pcm.poll();if(b==null){primed=false;gaps++;peak=0;}return b;}
        synchronized void reset(){pcm.clear();primed=false;peak=0;}
    }
    private static final class Packet {
        final int type;final byte[] bytes;
        Packet(int t,byte[] b){type=t;bytes=b;}
    }
    private static final class Peer implements AutoCloseable {
        final AutoCloseable connection;final DataInputStream in;final DataOutputStream out;final boolean compressed;BluetoothPlatform.Link raw;
        final ArrayBlockingQueue<Packet> audio=new ArrayBlockingQueue<>(20);
        final ArrayBlockingQueue<Packet> control=new ArrayBlockingQueue<>(8);
        volatile boolean done;volatile long progress=System.nanoTime();volatile String closeReason="";
        final java.util.concurrent.atomic.AtomicBoolean closing=new java.util.concurrent.atomic.AtomicBoolean();
        Peer(SSLSocket s)throws IOException{connection=s;compressed=false;in=new DataInputStream(s.getInputStream());out=new DataOutputStream(s.getOutputStream());}
        Peer(BluetoothPlatform.Link s,BluetoothPlatform.Link raw)throws IOException{connection=s;this.raw=raw;compressed=true;in=new DataInputStream(s.input());out=new DataOutputStream(new BufferedOutputStream(s.output(),8192));}
        volatile long dropped,decoded;
        void send(int type,byte[] data){if(done)return;ArrayBlockingQueue<Packet> q=type==2?audio:control;Packet p=new Packet(type,data);if(!q.offer(p)){if(type==2){q.poll();q.offer(p);dropped++;}else closeAsync("控制发送队列已满，慢成员已断开");}}
        void closeAsync(String reason){closeReason=reason;done=true;if(closing.compareAndSet(false,true))worker("peer-close",this::close);}
        void writer()throws Exception{while(!done){Packet p=control.poll();if(p==null)p=audio.poll(10,TimeUnit.MILLISECONDS);if(p==null)continue;byte[] b=p.type==2&&compressed?BluetoothCodec.encode(p.bytes,p.bytes.length):p.bytes;out.writeByte(p.type);out.writeInt(b.length);out.write(b);out.flush();progress=System.nanoTime();}}
        Packet read()throws IOException{int type=in.readUnsignedByte(),length=in.readInt();if(length<0||length>(type==3?1048576:MAX_PACKET))throw new IOException("超长协议消息");if(type==2&&length!=(compressed?488:Pcm.BYTES))throw new IOException("音频帧格式不兼容");byte[] b=new byte[length];in.readFully(b);try{if(type==2)decoded++;return new Packet(type,type==2&&compressed?BluetoothCodec.decode(b):b);}catch(IllegalArgumentException e){throw new IOException(e.getMessage());}}
        public void close(){done=true;try{connection.close();}catch(Exception ignored){}audio.clear();control.clear();}
    }
    public RoomEngine(String id,String name,AudioPlatform audio){this(id,name,audio,BluetoothPlatform.NONE);}
    public RoomEngine(String id,String name,AudioPlatform audio,BluetoothPlatform bluetooth){this.id=id;this.name=name;this.audio=audio;this.bluetooth=bluetooth;local=new Member(id,name);clock.execute(this::pulse);}
    // Monotonic audio pacing: skip missed deadlines rather than replaying a cached-process backlog.
    private void pulse(){if(closed)return;long now=System.nanoTime();if(nextMix==0||now-nextMix>TimeUnit.MILLISECONDS.toNanos(30))nextMix=now;while(now>=nextMix){nextMix+=TimeUnit.MILLISECONDS.toNanos(10);tick();}if(!closed)try{clock.schedule(this::pulse,Math.max(0,nextMix-System.nanoTime()),TimeUnit.NANOSECONDS);}catch(RejectedExecutionException ignored){}}
    private static void worker(String name,Runnable task){Thread t=new Thread(task,name);t.setDaemon(true);t.start();}
    private static String redact(String text){return String.valueOf(text).replaceAll("AH(?:B)?[12]\\|[^\\s]+","<REDACTED-CODE>").replaceAll("(?i)[0-9a-f]{32,}","<REDACTED>").replace('\r',' ').replace('\n',' ');}
    public synchronized void log(String text){log("INFO",text);}
    private synchronized void log(String level,String text){if(settings.logLevel.equals("OFF"))return;logs.add(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.ROOT).format(new Date())+" ["+level+"] "+redact(text));while(logs.size()>500)logs.remove(0);}
    public synchronized void clearLogs(){logs.clear();audio.clearNativeLogs();}
    public synchronized void report(String text){error=redact(text);log("ERROR",text);}
    public static List<String> addresses(){List<String> out=new ArrayList<>();for(Map<String,Object> n:NetworkAddresses.list())out.add((String)n.get("address"));return out;}
    public synchronized void loadSettings(File file)throws Exception{settingsFile=file;settings=RoomSettings.load(file);settings.a2dp=false;settings.autostart=audio.autostart();audio.diagnostics(settings);}
    public synchronized void saveSettings(Map<String,String> values)throws Exception{
        RoomSettings next=new RoomSettings(values);if(!values.containsKey("a2dp"))next.a2dp=settings.a2dp;boolean before=audio.autostart();
        boolean switching=next.a2dp!=settings.a2dp,active=switching&&!transport.equals("lan")&&!mode.equals("idle");
        if(active&&!Boolean.parseBoolean(values.getOrDefault("confirmMode","false")))throw new IllegalArgumentException("切换将停止当前蓝牙会话及其采集/播放，请确认后重试");
        if(next.autostart!=before)audio.autostart(next.autostart);
        try{next.save(settingsFile);}catch(Exception e){if(next.autostart!=before)audio.autostart(before);throw e;}
        if(active)leave();else if(switching&&mode.equals("idle"))bluetooth.cancel();settings=next;error="";audio.diagnostics(settings);clampGains();log("设置已保存；缓冲与调度下次启用本机音频生效，范围及诊断即时生效（超过新上限的增益已降低，不自动提高音量）");
    }
    public synchronized void volumeRange(String path,int range)throws Exception{
        if(!path.equals("lan")&&!path.equals("bluetooth"))throw new IllegalArgumentException("音量范围路径无效");
        Map<String,String> values=new HashMap<>();settings.values().forEach((key,value)->values.put(key,String.valueOf(value)));
        values.put(path+"Range",Integer.toString(range));RoomSettings next=new RoomSettings(values);
        next.save(settingsFile);settings=next;clampGains();error="";
        log((path.equals("lan")?"局域网":"蓝牙")+"音量范围已实时应用并保存：0–"+range+"%；只降低超限增益，不自动提高音量");
    }
    private void enumerate(){devices=new ArrayList<>();for(Map<String,Object> d:audio.devices()){if(devices.size()>=32)break;String key=String.valueOf(d.get("id")),label=String.valueOf(d.get("name"));if(key.length()>240)continue;devices.add(Json.object("id",key,"name",label.substring(0,Math.min(label.length(),80)),"input",Boolean.TRUE.equals(d.get("input")),"output",Boolean.TRUE.equals(d.get("output"))));}local.devices=devices;}
    public synchronized void refreshDevices()throws IOException{enumerate();if(mode.equals("host")||mode.equals("a2dp"))publish();else if(upstream!=null)upstream.send(7,inventory());log("已刷新本机音频设备："+devices.size()+" 项；不会自动切换播放输出");}
    private byte[] inventory()throws IOException{if(devices==null)enumerate();ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeByte(devices.size());for(Map<String,Object> item:devices){d.writeUTF((String)item.get("id"));d.writeUTF((String)item.get("name"));d.writeBoolean(Boolean.TRUE.equals(item.get("input")));d.writeBoolean(Boolean.TRUE.equals(item.get("output")));}return b.toByteArray();}
    private static List<Map<String,Object>> inventory(byte[] bytes)throws IOException{DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));int count=in.readUnsignedByte();if(count>32)throw new IOException("设备清单过长");List<Map<String,Object>> result=new ArrayList<>();Set<String> ids=new HashSet<>();for(int i=0;i<count;i++){String id=in.readUTF(),name=in.readUTF();boolean input=in.readBoolean(),output=in.readBoolean();if(id.length()>240||name.length()>80||!ids.add(id))throw new IOException("设备清单无效");result.add(Json.object("id",id,"name",name,"input",input,"output",output));}if(in.available()!=0)throw new IOException("设备清单尾部无效");return result;}
    public synchronized void selfTest()throws Exception{
        if(!mode.equals("host"))throw new IllegalStateException("请先在本机创建房间，再执行连接自检");
        final RoomCode target=new RoomCode(code);final long token=generation;
        worker("room-self-test",()->{String stage="本机 TCP";try(SSLSocket probe=(SSLSocket)TlsIdentity.client(target.pin).getSocketFactory().createSocket()){
            probe.setEnabledProtocols(new String[]{"TLSv1.2"});probe.connect(new InetSocketAddress("127.0.0.1",target.port),6000);probe.setSoTimeout(6000);
            stage="本机 TLS";probe.startHandshake();stage="本机认证";
            DataOutputStream out=new DataOutputStream(probe.getOutputStream());out.writeInt(MAGIC);out.writeUTF(target.secret);out.writeUTF("selftest-"+UUID.randomUUID());out.writeUTF("本机连接自检");out.flush();
            if(new DataInputStream(probe.getInputStream()).readInt()!=MAGIC)throw new IOException("认证响应无效");
            synchronized(this){if(token==generation)log("本机连接自检通过：TCP / TLS / 认证；此结果不代表其他设备能穿过热点隔离或防火墙");}
        }catch(Exception e){synchronized(this){if(token==generation)report("本机连接自检失败 ["+stage+"]："+e.getMessage());}}});
    }
    public synchronized void host(String address,int port)throws Exception{
        requireIdle();if(!addresses().contains(address)&&!address.equals("127.0.0.1"))throw new IOException("请选择本机局域网 IPv4");
        transport="lan";
        identity=TlsIdentity.create();secret=TlsIdentity.hex(TlsIdentity.random(16));
        SSLServerSocket pending=(SSLServerSocket)identity.context.getServerSocketFactory().createServerSocket();
        try{pending.setReuseAddress(true);pending.bind(new InetSocketAddress("0.0.0.0",port),12);pending.setEnabledProtocols(new String[]{"TLSv1.2"});}catch(Exception e){pending.close();throw e;}
        if(devices==null)enumerate();server=pending;mode="host";generation++;members.put(id,local);code="AH2|"+address+"|"+server.getLocalPort()+"|"+identity.fingerprint+"|"+secret;error="";publish();
        long token=generation;SSLServerSocket listener=server;log("局域网房间已创建，发布地址 "+address+":"+server.getLocalPort()+"；监听所有 IPv4 网卡 / TLS1.2 / 最多5输入5输出");
        if(NetworkAddresses.priority(address,"")>=80)log("WARN","发布地址不是典型局域网地址，请确认手机可访问；电脑热点通常选择 192.168.137.1");
        worker("room-accept",()->{while(!listener.isClosed()){try{SSLSocket s=(SSLSocket)listener.accept();s.setSoTimeout(6000);s.setTcpNoDelay(true);if(!handshakes.tryAcquire()){s.close();continue;}sockets.add(s);worker("room-peer",()->{try{accept(s,token);}finally{handshakes.release();sockets.remove(s);try{s.close();}catch(IOException ignored){}}});}catch(Exception e){if(!listener.isClosed())report("房间接收失败："+e.getMessage());}}});
    }
    private void requireIdle(){if(closed||!mode.equals("idle"))throw new IllegalStateException("请先关闭或离开当前房间");}
    private void accept(SSLSocket socket,long token){try{socket.startHandshake();acceptPeer(new Peer(socket),token);}catch(Exception e){if(token==generation)log("连接结束 [TLS 握手]："+e.getMessage());}}
    private void acceptPeer(Peer peer,long token){Member member=null;
        String stage="TLS 握手";
        try{stage="房间认证 / 编码协商";log("中心 TLS 握手通过，等待房间认证");if(peer.in.readInt()!=MAGIC)throw new IOException("协议版本不兼容");String supplied=peer.in.readUTF();String device=peer.in.readUTF(),label=peer.in.readUTF();if(peer.compressed&&!peer.in.readUTF().equals("IMA1"))throw new IOException("不支持的蓝牙编码");
            if(device.length()>80||label.length()>120)throw new IOException("设备标识过长");
            synchronized(this){if(token!=generation||!mode.equals("host")||!MessageDigest.isEqual(secret.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)))throw new IOException("房间认证失败");if(device.equals(id)||members.containsKey(device))throw new IOException("设备已加入房间（防止自身或重复连接）");if(members.size()>=11)throw new IOException("房间设备数量上限");member=new Member(device,label);member.peer=peer;members.put(device,member);}
            peer.out.writeInt(MAGIC);peer.out.flush();
            synchronized(this){if(token!=generation)throw new IOException("房间已关闭");if(peer.raw!=null)authenticatedLinks.add(peer.raw);publish();log("设备加入："+label+(peer.compressed?" / RFCOMM TLS1.2 IMA1":" / TCP TLS1.2 PCM"));}
            final Peer active=peer;worker("room-write",()->{try{active.writer();}catch(Exception ignored){}finally{active.close();}});
            while(!peer.done){Packet p=peer.read();if(p.type==1){DataInputStream d=new DataInputStream(new ByteArrayInputStream(p.bytes));boolean send=d.readBoolean();int count=d.readUnsignedByte();List<String> outputs=new ArrayList<>();if(count>5)throw new IOException("输出数量超限");for(int i=0;i<count;i++){String output=d.readUTF();if(output.length()>240)throw new IOException("输出标识过长");outputs.add(output);}String source=d.available()>0?d.readUTF():"";if(source.length()>240||d.available()!=0)throw new IOException("角色消息尾部无效");synchronized(this){try{configure(member,send,outputs);member.source=source;member.ready=false;peer.send(4,new byte[]{1});publish();}catch(Exception e){peer.send(5,e.getMessage().getBytes(StandardCharsets.UTF_8));}}}
                else if(p.type==2){if(p.bytes.length!=Pcm.BYTES)throw new IOException("PCM帧长度无效");synchronized(this){if(member.send)member.offer(p.bytes);}}
                else if(p.type==7){List<Map<String,Object>> available=inventory(p.bytes);synchronized(this){member.devices=available;publish();}}
                else if(p.type==8){if(p.bytes.length!=0)throw new IOException("启动状态无效");synchronized(this){member.ready=true;publish();}}
                else if(p.type!=6)throw new IOException("未知协议消息");}
        }catch(Exception e){if(token==generation)log("连接结束 ["+(member==null?stage:"已连接会话")+"]："+(member==null?"未认证设备":member.name)+" / "+e.getMessage());}
        finally{if(peer!=null)peer.close();synchronized(this){if(member!=null&&members.get(member.id)==member){if(peer.dropped>0||!peer.closeReason.isEmpty()){peerDiagnostic(peer,member.name);if(!peer.closeReason.isEmpty())log(member.name+"："+peer.closeReason);}members.remove(member.id);publish();}}}
    }
    public synchronized void join(String text)throws Exception{
        requireIdle();RoomCode room=new RoomCode(text);transport="lan";mode="joining";long token=++generation;error="";
        worker("room-connect",()->{SSLSocket socket=null;String stage="TCP 连接";try{
            log("连接阶段：TCP → "+room.host+":"+room.port);
            socket=(SSLSocket)TlsIdentity.client(room.pin).getSocketFactory().createSocket();sockets.add(socket);socket.setEnabledProtocols(new String[]{"TLSv1.2"});socket.connect(new InetSocketAddress(room.host,room.port),6000);socket.setSoTimeout(6000);socket.setTcpNoDelay(true);
            stage="TLS 握手";log("连接阶段：TCP 已连接，开始 TLS 指纹校验");socket.startHandshake();Peer p=new Peer(socket);stage="房间认证";log("连接阶段：TLS 已通过，开始房间认证");
            clientSession(p,room.secret,token);
        }catch(Exception e){synchronized(this){if(token==generation)report("连接结束 ["+stage+" / "+room.host+":"+room.port+"]："+e.getMessage()+(stage.equals("TCP 连接")?"。请确认中心房间已创建、连接码为当前网卡地址；电脑热点请选择 192.168.137.1，并检查中心 EXE 的防火墙入站权限（含当前网络配置文件）。":""));}}
        finally{if(socket!=null){sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}synchronized(this){if(token==generation)leave();}}});
    }
    private void clientSession(Peer p,String password,long token)throws Exception{
            if(token!=generation)throw new IOException("已取消连接");
            p.out.writeInt(MAGIC);p.out.writeUTF(password);p.out.writeUTF(id);p.out.writeUTF(name);if(p.compressed)p.out.writeUTF("IMA1");p.out.flush();if(p.in.readInt()!=MAGIC)throw new IOException("房间认证失败");
            synchronized(this){if(token!=generation)throw new IOException("已取消连接");if(p.raw!=null)authenticatedLinks.add(p.raw);upstream=p;mode="client";p.send(7,inventory());log("已加入房间，尚未开始采集或播放"+(p.compressed?" / RFCOMM TLS1.2 IMA1":""));}
            worker("room-uplink",()->{try{p.writer();}catch(Exception ignored){}finally{p.close();}});
            while(!p.done){Packet packet=p.read();if(packet.type==2){if(packet.bytes.length!=Pcm.BYTES)throw new IOException("无效PCM");synchronized(this){if(running&&!local.outputs.isEmpty())deliver(packet.bytes);}}
                else if(packet.type==3)topology=new String(packet.bytes,StandardCharsets.UTF_8);
                else if(packet.type==4){synchronized(this){if(pendingStart!=null){Runnable task=pendingStart;pendingStart=null;worker("room-audio-start",task);}}}
                else if(packet.type==5){synchronized(this){pendingStart=null;report(new String(packet.bytes,StandardCharsets.UTF_8));}}
                else if(packet.type!=6)throw new IOException("未知消息");}
    }
    private Runnable pendingStart;
    public synchronized void refreshBluetooth(){btStatus="正在枚举已配对蓝牙设备；新设备请先在系统蓝牙设置中配对";worker("bluetooth-discover",()->{try{List<Map<String,Object>> found=bluetooth.paired();synchronized(this){pairedDevices=found;btStatus="发现完成："+found.size()+" 台。选择设备并粘贴中心分享的 AHB2 码完成授权";log(btStatus);}}catch(Exception e){synchronized(this){btStatus="发现失败："+e.getMessage();report(btStatus);}}});}
    private final Set<BluetoothPlatform.Link> authenticatedLinks=ConcurrentHashMap.newKeySet();
    private void deadline(BluetoothPlatform.Link link){Thread timer=new Thread(()->{try{Thread.sleep(15000);if(btLinks.contains(link)&&!authenticatedLinks.contains(link))link.close();}catch(InterruptedException ignored){}},"bluetooth-handshake-deadline");timer.setDaemon(true);timer.start();}
    public synchronized void hostBluetooth()throws Exception{
        requireIdle();if(settings.a2dp)throw new IllegalStateException("请先在设置关闭 A2DP");
        transport="bluetooth";mode="joining";long token=++generation;error="";btStatus="正在创建 RFCOMM 服务";
        worker("bluetooth-listen",()->{BluetoothPlatform.Listener listener=null;try{
            TlsIdentity tls=TlsIdentity.create();if(token!=generation)return;listener=bluetooth.listen();
            synchronized(this){if(token!=generation){listener.close();return;}btServer=listener;identity=tls;secret=TlsIdentity.hex(TlsIdentity.random(16));if(devices==null)enumerate();members.put(id,local);mode="host";code="AHB2|"+listener.address()+"|"+tls.fingerprint+"|"+secret+"|IMA1";btStatus="RFCOMM 房间已创建 / TLS1.2 / IMA1 48kHz 立体声";publish();log(btStatus+"；单流约390kbps编码负载，并发容量需真机验证");}
            while(token==generation){BluetoothPlatform.Link raw=listener.accept();if(token!=generation||!handshakes.tryAcquire()){raw.close();continue;}btLinks.add(raw);deadline(raw);worker("bluetooth-peer",()->{try{SecureStream secure=new SecureStream(raw,tls.context,false);acceptPeer(new Peer(secure,raw),token);}catch(Exception e){if(token==generation)log("蓝牙连接结束 [TLS/认证]："+e.getMessage());}finally{authenticatedLinks.remove(raw);btLinks.remove(raw);raw.close();handshakes.release();}});}
        }catch(Exception e){synchronized(this){if(token==generation){report("蓝牙开房失败 [RFCOMM 服务/配对权限]："+e.getMessage());leave();}}}finally{if(listener!=null)listener.close();}});
    }
    public synchronized void joinBluetooth(String text,String selected)throws Exception{
        requireIdle();if(settings.a2dp)throw new IllegalStateException("请先在设置关闭 A2DP");BluetoothCode room=new BluetoothCode(text);
        if(room.address.equals("PAIRED")&&selected.isEmpty())throw new IllegalArgumentException("Android 中心不公开自身蓝牙地址，请从已配对设备列表选择中心；证书指纹仍严格校验");
        if(!room.address.equals("PAIRED")&&!selected.isEmpty()&&!selected.equalsIgnoreCase(room.address))throw new IllegalArgumentException("所选设备与蓝牙房间码中心地址不同");
        final String target=room.address.equals("PAIRED")?selected:room.address;
        transport="bluetooth";mode="joining";long token=++generation;error="";btStatus="RFCOMM 连接中";
        worker("bluetooth-connect",()->{BluetoothPlatform.Link raw=null;String stage="RFCOMM 连接（请确认已系统配对）";try{
            log("蓝牙连接阶段：RFCOMM → TLS指纹 → 房间认证 → IMA1协商");raw=bluetooth.connect(target);if(token!=generation)return;btLinks.add(raw);deadline(raw);
            stage="TLS 指纹校验";SecureStream secure=new SecureStream(raw,TlsIdentity.client(room.pin),true);stage="房间认证/IMA1协商/已连接会话";clientSession(new Peer(secure,raw),room.secret,token);
        }catch(Exception e){synchronized(this){if(token==generation)report("蓝牙连接结束 ["+stage+"]："+e.getMessage());}}finally{if(raw!=null){authenticatedLinks.remove(raw);btLinks.remove(raw);raw.close();}synchronized(this){if(token==generation)leave();}}});
    }
    public synchronized void a2dp(String action,String device)throws Exception{
        if(!settings.a2dp)throw new IllegalStateException("请先在设置启用 A2DP");
        if(!mode.equals("idle")&&!mode.equals("a2dp"))throw new IllegalStateException("当前有活动软件房间；请先离开，A2DP 不会抢占局域网");
        if(action.equals("refresh")&&!mode.equals("idle"))throw new IllegalStateException("A2DP 正在连接或已连接；请先断开，再刷新来源列表");
        bluetooth.a2dp(action,device);
        if(action.equals("connect")){transport="a2dp";mode="a2dp";if(devices==null)enumerate();members.put(id,local);publish();log("A2DP 系统连接请求；系统直放不等于软件混音。回采端点可能含其他程序声音");}
        if(action.equals("disconnect"))leave();
    }
    /** Direct connection owns A2DP mode; ordinary settings saves cannot toggle it. */
    public synchronized void direct(String action,String device,boolean confirmed)throws Exception{
        if(!Arrays.asList("refresh","start","stop").contains(action))throw new IllegalArgumentException("未知直接连接操作");
        if(!mode.equals("idle")&&transport.equals("lan"))throw new IllegalStateException("当前有局域网房间；请先离开，直接连接不会抢占局域网");
        if(action.equals("stop")){
            if(mode.equals("a2dp")&&!confirmed)throw new IllegalArgumentException("退出直接连接会断开 A2DP 并停止该路径采集/播放，请先确认");
            if(mode.equals("a2dp"))leave();else if(settings.a2dp)bluetooth.cancel();
            settings.a2dp=false;error="";log("已关闭/退出直接连接；软件蓝牙房间可用");return;
        }
        if(!bluetooth.a2dpSupported())throw new IllegalStateException(bluetooth.reason());
        if(mode.equals("a2dp"))throw new IllegalStateException("直接连接正在连接或已连接，请先关闭/退出，再刷新或重新开启");
        if(action.equals("refresh")){bluetooth.a2dp("refresh","");error="";log("正在刷新直接连接来源；发送端无需安装 AudioHub App");return;}
        Object found=bluetooth.a2dpState().get("devices");boolean available=false;
        if(found instanceof List<?>)for(Object entry:(List<?>)found)if(entry instanceof Map<?,?>&&device.equals(((Map<?,?>)entry).get("id")))available=true;
        if(device.isEmpty()||!available)throw new IllegalArgumentException("请先刷新并选择有效的直接连接来源设备；原蓝牙房间保留");
        if(!mode.equals("idle")&&!confirmed)throw new IllegalArgumentException("开启直接连接会关闭当前蓝牙房间并停止其采集/播放，请先确认");
        if(!mode.equals("idle"))leave();settings.a2dp=true;
        try{a2dp("connect",device);error="";}catch(Exception e){if(mode.equals("a2dp"))leave();else bluetooth.cancel();settings.a2dp=false;throw e;}
    }
    public synchronized void directCapture(String action,String source)throws Exception{
        if(!Arrays.asList("start","stop","refresh").contains(action))throw new IllegalArgumentException("未知回采操作");
        if(!bluetooth.a2dpSupported())throw new IllegalStateException(bluetooth.reason());
        if(action.equals("refresh")){refreshDevices();return;}
        if(!mode.equals("a2dp"))throw new IllegalStateException("请先开启直接连接；回采控件不会改变其他房间音频");
        startAudio(action.equals("start"),action.equals("start")?source:"",localOutputs());
    }
    private synchronized void configure(Member member,boolean send,List<String> outputs){
        int inputs=send?1:0,count=outputs.size();for(Member m:members.values())if(m!=member){if(m.send)inputs++;count+=m.outputs.size();}
        if(inputs>5||count>5)throw new IllegalArgumentException("全房间最多5路输入、5路输出（含中心本机）");
        if(new HashSet<>(outputs).size()!=outputs.size())throw new IllegalArgumentException("不能重复选择输出");
        if(member.send!=send)member.reset();member.send=send;member.outputs=new ArrayList<>(outputs);member.prebuffer=settings.inputMs/10;
    }
    private static byte[] roles(boolean send,String source,List<String> outputs)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeBoolean(send);d.writeByte(outputs.size());for(String output:outputs)d.writeUTF(output);d.writeUTF(source);return b.toByteArray();}
    public synchronized void startAudio(boolean send,String source,List<String> outputs)throws Exception{
        if(!mode.equals("host")&&!mode.equals("client")&&!mode.equals("a2dp"))throw new IllegalStateException("请先创建或加入房间");
        if(mode.equals("a2dp")&&!Boolean.TRUE.equals(bluetooth.a2dpState().get("connected")))throw new IllegalStateException("A2DP 尚未连接；系统直放与软件回采是不同阶段");
        if(pendingStart!=null)throw new IllegalStateException("正在等待中心确认，请稍后操作");
        if(!send&&outputs.isEmpty()){stopAudio();return;}
        if(outputs.size()>5||new HashSet<>(outputs).size()!=outputs.size())throw new IllegalArgumentException("输出选择无效");
        if(send&&outputs.contains(source))throw new IllegalArgumentException("回绕保护：输入回采端点不能同时作为本机播放输出");
        if(devices!=null&&!devices.isEmpty()){
            if(send&&devices.stream().noneMatch(d->Boolean.TRUE.equals(d.get("input"))&&source.equals(d.get("id"))))throw new IllegalArgumentException("输入设备已移除或不可采集，请刷新后重新选择；原音频方向保留");
            for(String output:outputs)if(devices.stream().noneMatch(d->Boolean.TRUE.equals(d.get("output"))&&output.equals(d.get("id"))))throw new IllegalArgumentException("输出设备已移除，请刷新后重新选择；原音频方向保留");
        }
        List<String> selected=new ArrayList<>(outputs);long token=generation,audioToken=++audioGeneration;
        Runnable begin=()->{synchronized(this){if(token!=generation||audioToken!=audioGeneration||mode.equals("idle"))return;try{
            audio.configure(settings);for(Member member:members.values())member.prebuffer=settings.inputMs/10;local.prebuffer=settings.inputMs/10;
            audio.start(send,source,selected,this::captured,this::audioFailed);local.send=send;local.source=source;local.outputs=selected;clampGains();local.ready=true;running=true;error="";if(upstream!=null)upstream.send(8,new byte[0]);log("音频路由已更新；发送="+send+"；本机播放="+selected.size()+" 路；两项独立控制；回绕保护开启；输出缓冲/预留/输入预缓冲="+settings.outputMs+"/"+settings.reserveMs+"/"+settings.inputMs+" ms");
        }catch(Exception e){stopAudio();report("音频启动失败："+e.getMessage());}}};
        if(mode.equals("host")||mode.equals("a2dp")){configure(local,send,selected);begin.run();publish();}
        else{pendingStart=begin;upstream.send(1,roles(send,source,selected));}
    }
    private void audioFailed(String message){worker("room-audio-failure",()->{synchronized(this){stopAudio();report(message);}});}
    private void captured(byte[] bytes){if(bytes.length!=Pcm.BYTES)return;synchronized(this){if(!running||!local.send)return;capturePackets++;capturePeak=Pcm.peak(bytes);if(capturePeak>.0001f)captureAudible++;if(mode.equals("host")||mode.equals("a2dp"))local.offer(bytes.clone());else if(upstream!=null)upstream.send(2,bytes.clone());}}
    private void deliver(byte[] bytes){outputPackets++;outputPeak=Pcm.peak(bytes);if(outputPeak>.0001f)outputAudible++;audio.play(bytes);}
    public synchronized void stopAudio(){audioGeneration++;authorizing=false;pendingStart=null;running=false;audio.stop();local.send=false;local.source="";local.ready=false;local.outputs.clear();local.reset();capturePeak=outputPeak=0;if(mode.equals("host")||mode.equals("a2dp"))publish();else if(upstream!=null)try{upstream.send(1,roles(false,"",Collections.emptyList()));}catch(IOException ignored){} }
    public synchronized void inputGain(String member,float gain){if(!mode.equals("host")&&!mode.equals("a2dp"))throw new IllegalStateException("输入增益由中心设备管理");if(!Float.isFinite(gain)||gain<0||gain>gainLimit())throw new IllegalArgumentException("增益超过当前路径设置范围");Member m=members.get(member);if(m==null)throw new IllegalArgumentException("设备不存在");m.gain=gain;publish();}
    public synchronized void outputGain(int index,float gain){if(index<0||index>=local.outputs.size()||!Float.isFinite(gain)||gain<0||gain>gainLimit())throw new IllegalArgumentException("输出增益超过当前路径设置范围");audio.outputGain(index,gain);outputGains.put(local.outputs.get(index),gain);}
    private void tick(){try{synchronized(this){if(closed)return;if(mode.equals("a2dp")&&!Boolean.TRUE.equals(bluetooth.a2dpState().get("connected"))&&running){stopAudio();report("A2DP 已断开，软件回采/播放已停止");}if(mode.equals("host")||mode.equals("a2dp")){
        List<byte[]> blocks=new ArrayList<>();List<Float> gains=new ArrayList<>();for(Member m:members.values())if(m.send){blocks.add(m.read());gains.add(m.gain);}
        byte[] mix=Pcm.mix(blocks,gains);for(Member m:members.values())if(!m.outputs.isEmpty()){if(m==local){if(running)deliver(mix);}else m.peer.send(2,mix);}
        if(++mixed%100==0)publish();
    }
        if(++ticks%100==0){for(Member m:members.values())if(m.peer!=null)m.peer.send(6,new byte[0]);if(upstream!=null)upstream.send(6,new byte[0]);}
        if(ticks%500==0&&(running||members.values().stream().anyMatch(m->m.send))){log("音频链路：本机采集="+capturePackets+"/非静音="+captureAudible+"；输出提交="+outputPackets+"/非静音="+outputAudible+"；"+audio.status());if(mode.equals("host"))for(Member m:members.values()){if(m.send)log("中心输入 "+m.name+"：收到="+m.received+"/非静音="+m.audible+"；电平="+Math.round(m.peak*100)+"%；增益="+m.gain+"；丢旧帧="+m.drops+"；欠载="+m.gaps);if(m.peer!=null)peerDiagnostic(m.peer,m.name);}if(upstream!=null)peerDiagnostic(upstream,"中心");}
        long now=System.nanoTime();Peer p=upstream;if(p!=null&&!p.done&&now-p.progress>TimeUnit.SECONDS.toNanos(8))p.closeAsync("发送阻塞超过8秒，已断开慢成员");for(Member m:members.values())if(m.peer!=null&&!m.peer.done&&now-m.peer.progress>TimeUnit.SECONDS.toNanos(8))m.peer.closeAsync("发送阻塞超过8秒，已断开慢成员");
    }}catch(Exception e){report("混音异常："+e.getMessage());}}
    private void peerDiagnostic(Peer p,String owner){log("传输队列 "+owner+"："+(p.compressed?"RFCOMM/TLS/IMA1":"TCP/TLS/PCM")+"；发送排队="+p.audio.size()+"/20；发送丢旧帧="+p.dropped+"；音频解码="+p.decoded+"。源静音、队列拥塞及输入欠载是不同指标，非无线射频丢包计数");}
    private synchronized Map<String,Object> room(){List<Object> list=new ArrayList<>();int in=0,out=0;for(Member m:members.values()){if(m.send)in++;out+=m.outputs.size();list.add(Json.object("id",m.id,"name",m.name,"send",m.send,"source",m.source,"ready",m.ready,"devices",m.devices,"outputs",m.outputs,"gain",m.gain,"peak",m.peak,"received",m.received,"audible",m.audible,"drops",m.drops,"gaps",m.gaps));}return Json.object("center",id,"centerName",name,"members",list,"inputs",in,"outputs",out);}
    private synchronized void publish(){if(!mode.equals("host")&&!mode.equals("a2dp"))return;topology=Json.encode(room());byte[] b=topology.getBytes(StandardCharsets.UTF_8);for(Member m:members.values())if(m.peer!=null)m.peer.send(3,b);}
    public synchronized String snapshot(){if(devices==null)enumerate();List<String> combined=new ArrayList<>(logs);String nativeText=audio.nativeLogs();if(!nativeText.isEmpty())for(String line:nativeText.split("\\r?\\n"))if(!line.isEmpty())combined.add(redact(line));Collections.sort(combined);if(combined.size()>500)combined=new ArrayList<>(combined.subList(combined.size()-500,combined.size()));
        Map<String,Object> state=Json.object("mode",mode,"transport",transport,"bluetoothDevices",pairedDevices,"bluetoothStatus",btStatus,"a2dp",bluetooth.a2dpState(),"outputGains",outputGains,"id",id,"name",name,"running",running,"pendingAudio",pendingStart!=null||authorizing,"localSend",local.send,"localSource",local.source,"localOutputs",local.outputs,"capturePackets",capturePackets,"captureAudible",captureAudible,"capturePeak",capturePeak,"outputPackets",outputPackets,"outputAudible",outputAudible,"outputPeak",outputPeak,"error",error,"code",code,"addresses",addresses(),"networkInterfaces",NetworkAddresses.list(),"devices",devices,"audioStatus",audio.status(),"logs",combined,"settings",settings.values(),"supportsAutostart",audio.supportsAutostart());String json=Json.encode(state);return json.substring(0,json.length()-1)+",\"room\":"+topology+"}";}
    public synchronized String mode(){return mode;}
    public synchronized boolean running(){return running;}
    public synchronized boolean sending(){return local.send;}
    /** Consent is only valid for this audio revision; stop, leave and route changes invalidate it. */
    public synchronized long audioRevision(){return audioGeneration;}
    public synchronized void authorizing(boolean value){authorizing=value;}
    public synchronized String localSource(){return local.source;}
    public synchronized List<String> localOutputs(){return new ArrayList<>(local.outputs);}
    public synchronized void leave(){generation++;authorizing=false;stopAudio();if(upstream!=null){upstream.close();upstream=null;}for(SSLSocket s:sockets)try{s.close();}catch(IOException ignored){}sockets.clear();if(server!=null){try{server.close();}catch(IOException ignored){}server=null;}if(btServer!=null){btServer.close();btServer=null;}for(BluetoothPlatform.Link l:btLinks)l.close();btLinks.clear();authenticatedLinks.clear();bluetooth.cancel();members.clear();mode="idle";topology="{}";code="";secret=null;identity=null;log("房间已关闭或离开；音频已停止");}
    @Override public synchronized void close(){leave();closed=true;clock.shutdownNow();}
}
