package org.audiohub.room;

import java.util.*;
import java.util.function.Consumer;

/** Platform adapter seam: capture never includes AudioHub playback; errors fail closed. */
public interface AudioPlatform {
    List<Map<String,Object>> devices();
    void start(boolean send,String source,List<String> outputs,Consumer<byte[]> captured,Consumer<String> failure) throws Exception;
    void play(byte[] pcm);
    void outputGain(int index,float gain);
    void stop();
    String status();
    default void configure(RoomSettings settings) throws Exception {}
    default void diagnostics(RoomSettings settings) {}
    default String nativeLogs(){return "";}
    default void clearNativeLogs(){}
    default boolean supportsAutostart(){return false;}
    default boolean autostart(){return false;}
    default void autostart(boolean enabled)throws Exception {if(enabled)throw new IllegalArgumentException("此平台不支持登录自启");}
}
