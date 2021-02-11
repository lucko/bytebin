package me.lucko.bytebin.util;

import java.util.ArrayList;
import java.util.List;

public enum ContentEncoding {

    IDENTITY("identity"),
    GZIP("gzip"),
    OTHER("");

    private final String encoding;
    ContentEncoding(String encoding) {
        this.encoding = encoding;
    }

    public String getEncoding() {
        return encoding;
    }

    public static ContentEncoding getEncoding(String encoding) {
        if (encoding == null || encoding.isEmpty()) {
            return IDENTITY;
        }
        for (ContentEncoding type : values()) {
            if (encoding.equals(type.encoding) || (encoding.equals("x-gzip") && type == GZIP)) {
                return type;
            }
        }
        return OTHER;
    }

    public static List<ContentEncoding> getEncoding(List<String> encoding) {
        List<ContentEncoding> retVal = new ArrayList<>(2);
        if (encoding != null && !encoding.isEmpty()) {
            for (String typeStr : encoding) {
                boolean added = false;
                for (ContentEncoding type : values()) {
                    if (typeStr.equals(type.encoding) || (typeStr.equals("x-gzip") && type == GZIP)) {
                        retVal.add(type);
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    retVal.add(OTHER);
                }
            }
        }
        if (!retVal.contains(IDENTITY)) {
            retVal.add(IDENTITY);
        }
        return retVal;
    }

}
