package org.audiohub.room;

import java.util.*;

/** Output-only JSON: network messages never use an unbounded object parser. */
public final class Json {
    private Json() {}
    public static String encode(Object value) {
        if(value==null)return "null";
        if(value instanceof Boolean || value instanceof Number)return value.toString();
        if(value instanceof Map) {
            StringJoiner out=new StringJoiner(",","{","}");
            ((Map<?,?>)value).forEach((k,v)->out.add(encode(k.toString())+":"+encode(v)));
            return out.toString();
        }
        if(value instanceof Iterable) {
            StringJoiner out=new StringJoiner(",","[","]");
            for(Object item:(Iterable<?>)value)out.add(encode(item));
            return out.toString();
        }
        StringBuilder out=new StringBuilder("\"");
        for(char c:value.toString().toCharArray()) {
            switch(c) {
                case '"':out.append("\\\"");break;
                case '\\':out.append("\\\\");break;
                case '\n':out.append("\\n");break;
                case '\r':out.append("\\r");break;
                case '\t':out.append("\\t");break;
                default:if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);
            }
        }
        return out.append('"').toString();
    }
    public static Map<String,Object> object(Object... pairs) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);
        return out;
    }
}
