package org.audiohub.room;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

/** Ephemeral room identity. Standard X.509/SHA256withRSA and platform TLS only. */
public final class TlsIdentity {
    public final SSLContext context;
    public final String fingerprint;
    private TlsIdentity(SSLContext c,String f){context=c;fingerprint=f;}
    static byte[] der(int tag,byte[]... parts) throws IOException {
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        for(byte[] part:parts)body.write(part);
        byte[] bytes=body.toByteArray();ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(tag);
        if(bytes.length<128)out.write(bytes.length);
        else {int size=bytes.length,n=0;while(size>0){size>>>=8;n++;}out.write(128|n);for(int i=n-1;i>=0;i--)out.write(bytes.length>>>(8*i));}
        out.write(bytes);return out.toByteArray();
    }
    public static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
    public static TlsIdentity create() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair key=generator.generateKeyPair();
        byte[] algorithm=der(0x30,der(6,new byte[]{42,(byte)134,72,(byte)134,(byte)247,13,1,1,11}),der(5));
        byte[] name=der(0x30,der(0x31,der(0x30,der(6,new byte[]{85,4,3}),der(12,"AudioHub ephemeral room".getBytes(StandardCharsets.UTF_8)))));
        java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("yyMMddHHmmss'Z'",Locale.ROOT);date.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now=System.currentTimeMillis();
        byte[] validity=der(0x30,der(23,date.format(new Date(now-86400000L)).getBytes(StandardCharsets.US_ASCII)),der(23,date.format(new Date(now+7*86400000L)).getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs=der(0x30,der(2,new BigInteger(120,new SecureRandom()).add(BigInteger.ONE).toByteArray()),algorithm,name,validity,name,key.getPublic().getEncoded());
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(key.getPrivate());signature.update(tbs);
        byte[] sig=signature.sign(),bits=new byte[sig.length+1];System.arraycopy(sig,0,bits,1,sig.length);
        byte[] encoded=der(0x30,tbs,algorithm,der(3,bits));
        X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));cert.verify(key.getPublic());
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);char[] password=hex(random(16)).toCharArray();
        store.setKeyEntry("room",key.getPrivate(),password,new java.security.cert.Certificate[]{cert});
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password);Arrays.fill(password,'\0');
        SSLContext context=SSLContext.getInstance("TLSv1.2");context.init(km.getKeyManagers(),null,new SecureRandom());
        return new TlsIdentity(context,hex(MessageDigest.getInstance("SHA-256").digest(encoded)));
    }
    public static byte[] random(int size){byte[] b=new byte[size];new SecureRandom().nextBytes(b);return b;}
    public static SSLContext client(String pin) throws Exception {
        if(!pin.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("证书指纹无效");
        TrustManager[] trust={new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("Client certificates unsupported");}
            public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{
                try{if(chain.length==0 || !MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII),hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())).getBytes(StandardCharsets.US_ASCII)))throw new CertificateException("房间证书指纹不匹配");}
                catch(GeneralSecurityException e){throw new CertificateException(e);}
            }
        }};
        SSLContext c=SSLContext.getInstance("TLSv1.2");c.init(null,trust,new SecureRandom());return c;
    }
}
