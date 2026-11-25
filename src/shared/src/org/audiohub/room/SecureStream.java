package org.audiohub.room;

import java.io.*;
import java.nio.ByteBuffer;
import javax.net.ssl.*;

/** Bounded TLS 1.2 over RFCOMM. One reader/one writer after handshake. */
public final class SecureStream implements BluetoothPlatform.Link {
    private final BluetoothPlatform.Link link;
    private final SSLEngine engine;
    private final InputStream rawIn;
    private final OutputStream rawOut;
    private final ByteBuffer network=ByteBuffer.allocate(65536),plain=ByteBuffer.allocate(65536),packet=ByteBuffer.allocate(65536);
    private static final ByteBuffer EMPTY=ByteBuffer.allocate(0);
    public SecureStream(BluetoothPlatform.Link link,SSLContext context,boolean client) throws Exception {
        this.link=link;rawIn=link.input();rawOut=link.output();
        engine=context.createSSLEngine();engine.setUseClientMode(client);engine.setEnabledProtocols(new String[]{"TLSv1.2"});
        network.flip();plain.flip();engine.beginHandshake();
        for(int operations=0;engine.getHandshakeStatus()!=SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;operations++) {
            if(operations>10000)throw new SSLException("TLS handshake operation bound");
            switch(engine.getHandshakeStatus()) {
                case NEED_TASK: Runnable task;while((task=engine.getDelegatedTask())!=null)task.run();break;
                case NEED_WRAP: wrap(EMPTY.duplicate());break;
                case NEED_UNWRAP: unwrap();break;
                default:throw new SSLException("Unexpected TLS handshake state");
            }
        }
    }
    private void receive() throws IOException {
        network.compact();
        if(!network.hasRemaining())throw new SSLException("TLS record exceeds bound");
        int count=rawIn.read(network.array(),network.position(),network.remaining());
        if(count<0)throw new EOFException("Bluetooth peer disconnected");
        network.position(network.position()+count);network.flip();
    }
    private void wrap(ByteBuffer source) throws IOException {
        packet.clear();SSLEngineResult result=engine.wrap(source,packet);
        if(result.getStatus()!=SSLEngineResult.Status.OK)throw new SSLException("TLS wrap "+result.getStatus());
        rawOut.write(packet.array(),0,packet.position());rawOut.flush();
    }
    private void unwrap() throws IOException {
        plain.compact();
        for(;;) {
            SSLEngineResult result=engine.unwrap(network,plain);
            if(result.getStatus()==SSLEngineResult.Status.BUFFER_UNDERFLOW){receive();continue;}
            if(result.getStatus()!=SSLEngineResult.Status.OK)throw new SSLException("TLS unwrap "+result.getStatus());
            plain.flip();return;
        }
    }
    final InputStream input=new InputStream() {
        @Override public int read() throws IOException {byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
        @Override public int read(byte[] bytes,int offset,int count) throws IOException {
            if(count==0)return 0;
            while(!plain.hasRemaining()) {unwrap();checkHandshake();}
            int size=Math.min(count,plain.remaining());plain.get(bytes,offset,size);return size;
        }
    };
    final OutputStream output=new OutputStream() {
        @Override public void write(int value) throws IOException {write(new byte[]{(byte)value});}
        @Override public void write(byte[] bytes,int offset,int count) throws IOException {
            ByteBuffer source=ByteBuffer.wrap(bytes,offset,count);
            while(source.hasRemaining()){wrap(source);checkHandshake();}
        }
    };
    private void checkHandshake() throws SSLException {
        if(engine.getHandshakeStatus()!=SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)throw new SSLException("TLS renegotiation is not supported");
    }
    public InputStream input(){return input;}
    public OutputStream output(){return output;}
    @Override public void close(){link.close();}
}
