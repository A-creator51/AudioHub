package org.audiohub.room;

import java.util.*;

/** One PCM consumer per room, then fan-out: every output receives the same mix. */
public final class Pcm {
    public static final int BYTES=1920; // 480 frames / 10ms, stereo PCM16 LE at 48kHz
    private Pcm(){}
    public static int sample(byte[] b,int i){return (short)((b[i]&255)|(b[i+1]<<8));}
    public static byte[] mix(List<byte[]> blocks,List<Float> gains) {
        byte[] out=new byte[BYTES];
        for(int i=0;i<BYTES;i+=2){float sum=0;for(int j=0;j<blocks.size();j++)if(blocks.get(j)!=null)sum+=sample(blocks.get(j),i)*gains.get(j);
            int x=Math.max(-32768,Math.min(32767,Math.round(sum)));out[i]=(byte)x;out[i+1]=(byte)(x>>>8);}
        return out;
    }
    public static float peak(byte[] b){int peak=0;for(int i=0;i<b.length;i+=2)peak=Math.max(peak,Math.abs(sample(b,i)));return peak/32768f;}
    public static byte[] gain(byte[] b,float gain){return mix(Collections.singletonList(b),Collections.singletonList(gain));}
}
