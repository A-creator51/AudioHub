package org.audiohub.room;

/** Independent stereo IMA ADPCM blocks, 48kHz, 4 bits/channel/sample. */
public final class BluetoothCodec {
    private static final int[] STEPS={7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767};
    private static final int[] ADJUST={-1,-1,-1,-1,2,4,6,8};
    private static int sample(byte[] pcm,int offset){return (short)((pcm[offset]&255)|((pcm[offset+1]&255)<<8));}
    public static byte[] encode(byte[] pcm,int count) {
        if(count<4 || count>1920 || count%4!=0)throw new IllegalArgumentException("Invalid PCM block");
        int frames=count/4;byte[] result=new byte[8+frames];int[] predictor=new int[2],index=new int[2];
        for(int c=0;c<2;c++){
            predictor[c]=sample(pcm,c*2);
            int largest=7;for(int i=1;i<frames;i++)largest=Math.max(largest,Math.abs(sample(pcm,i*4+c*2)-sample(pcm,(i-1)*4+c*2)));
            while(index[c]<88 && STEPS[index[c]]<largest)index[c]++;
            result[c*4]=(byte)predictor[c];result[c*4+1]=(byte)(predictor[c]>>8);result[c*4+2]=(byte)index[c];
        }
        for(int i=0;i<frames;i++)for(int c=0;c<2;c++) {
            int delta=sample(pcm,i*4+c*2)-predictor[c],nibble=delta<0?8:0,step=STEPS[index[c]],diff=step/8;
            delta=Math.abs(delta);
            if(delta>=step){nibble|=4;delta-=step;diff+=step;}
            if(delta>=step/2){nibble|=2;delta-=step/2;diff+=step/2;}
            if(delta>=step/4){nibble|=1;diff+=step/4;}
            predictor[c]=Math.max(-32768,Math.min(32767,predictor[c]+((nibble&8)!=0?-diff:diff)));
            index[c]=Math.max(0,Math.min(88,index[c]+ADJUST[nibble&7]));result[8+i]|=(byte)(nibble<<(c*4));
        }
        return result;
    }
    public static byte[] decode(byte[] block){
        if(block.length!=488)throw new IllegalArgumentException("IMA1 frame must contain 480 stereo samples");
        byte[] pcm=new byte[1920];int[] predictor=new int[2],index=new int[2];
        for(int c=0;c<2;c++){predictor[c]=sample(block,c*4);index[c]=block[c*4+2]&255;if(index[c]>88||block[c*4+3]!=0)throw new IllegalArgumentException("Invalid IMA1 header");}
        for(int i=0;i<480;i++)for(int c=0;c<2;c++){
            int n=((block[8+i]&255)>>(c*4))&15,step=STEPS[index[c]],diff=step/8;
            if((n&4)!=0)diff+=step;if((n&2)!=0)diff+=step/2;if((n&1)!=0)diff+=step/4;
            predictor[c]=Math.max(-32768,Math.min(32767,predictor[c]+((n&8)!=0?-diff:diff)));
            index[c]=Math.max(0,Math.min(88,index[c]+ADJUST[n&7]));int offset=i*4+c*2;pcm[offset]=(byte)predictor[c];pcm[offset+1]=(byte)(predictor[c]>>8);
        }return pcm;
    }
}
