package org.audiohub.room;

import java.net.*;
import java.util.*;

public final class NetworkAddresses {
    private NetworkAddresses(){}
    public static int priority(String address,String name){
        String n=name.toLowerCase(Locale.ROOT);
        if(n.matches(".*(tailscale|zerotier|vpn|wireguard|tun[0-9]*|tap[0-9]*|virtualbox|vmware|hyper-v|vethernet).*"))return 90;
        if(address.startsWith("192.168.137."))return 0;
        if(address.startsWith("192.168."))return 10;
        String[] p=address.split("\\.");if(address.startsWith("10.")||(p.length==4&&p[0].equals("172")&&Integer.parseInt(p[1])>=16&&Integer.parseInt(p[1])<=31))return 20;
        return 80;
    }
    public static List<Map<String,Object>> list(){List<Map<String,Object>> out=new ArrayList<>();try{
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){NetworkInterface n=interfaces.nextElement();if(!n.isUp()||n.isLoopback())continue;
            for(InterfaceAddress a:n.getInterfaceAddresses()){InetAddress ip=a.getAddress();if(!(ip instanceof Inet4Address)||ip.isLinkLocalAddress())continue;
                String address=ip.getHostAddress(),name=n.getDisplayName()==null?n.getName():n.getDisplayName();int rank=priority(address,name);
                out.add(Json.object("address",address,"name",name,"prefix",(int)a.getNetworkPrefixLength(),"priority",rank,"label",address+" · "+name+(rank>=80?"（虚拟/VPN/非典型局域网，请核对）":address.startsWith("192.168.137.")?"（Windows 热点常用网段）":"")));
            }
        }
    }catch(Exception ignored){}out.sort(Comparator.comparingInt((Map<String,Object> m)->(int)m.get("priority")).thenComparing(m->(String)m.get("address")));return out;}
}
