package org.audiohub.room;

/** AH2 is deliberately incompatible with the old one-way AH1 room. Never log codes. */
public final class RoomCode {
    public final String host,pin,secret;
    public final int port;
    public RoomCode(String text) {
        String[] p=text.trim().split("\\|",-1);
        if(p.length!=5 || !p[0].equals("AH2") || !p[1].matches("[A-Za-z0-9.:-]{1,253}") || !p[3].matches("[0-9a-f]{64}") || !p[4].matches("[0-9a-f]{32}"))throw new IllegalArgumentException("请粘贴完整的 AH2 局域网房间码（不兼容旧 AH1）");
        host=p[1];port=Integer.parseInt(p[2]);pin=p[3];secret=p[4];if(port<1 || port>65535)throw new IllegalArgumentException("端口无效");
    }
    @Override public String toString(){return "AH2|"+host+"|"+port+"|"+pin+"|"+secret;}
}
