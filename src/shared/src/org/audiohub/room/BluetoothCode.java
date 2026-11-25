package org.audiohub.room;

/** Versioned RFCOMM service + certificate identity + room authorization, never persisted. */
public final class BluetoothCode {
    public final String address,pin,secret;
    public BluetoothCode(String value){
        String[] p=value.trim().split("\\|",-1);
        if(p.length!=5||!p[0].equals("AHB2")||!(p[1].equals("PAIRED")||p[1].matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))||!p[2].matches("(?i)[0-9a-f]{64}")||!p[3].matches("(?i)[0-9a-f]{32}")||!p[4].equals("IMA1"))throw new IllegalArgumentException("需要当前 AHB2 蓝牙房间码（不兼容旧 AHB1/AH2）；编码必须为 IMA1");
        address=p[1].toUpperCase(java.util.Locale.ROOT);pin=p[2].toLowerCase(java.util.Locale.ROOT);secret=p[3].toLowerCase(java.util.Locale.ROOT);
    }
}
