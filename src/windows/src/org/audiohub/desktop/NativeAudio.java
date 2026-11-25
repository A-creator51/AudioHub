package org.audiohub.desktop;

public final class NativeAudio {
    private NativeAudio(){}
    public static native String[] devices(); // id/name pairs of WASAPI render endpoints
    public static native String[] inputDevices(); // render loopback + explicitly labelled A2DP candidates, never generic microphones
    public static native void start(boolean capture,String source,String[] outputs);
    public static native byte[] capture();
    public static native void play(byte[] pcm);
    public static native void gain(int output,float value);
    public static native String status();
    public static native void stop();
    public static native void configure(int outputMs,int reserveMs,int inputMs,boolean mmcss);
    public static native void diagnostics(boolean captureDetail,int logLevel);
    public static native String logs();
    public static native void clearLogs();
    public static native boolean autostart();
    public static native void setAutostart(boolean enabled);
}
