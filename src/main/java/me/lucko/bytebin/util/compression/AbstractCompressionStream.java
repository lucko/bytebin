package me.lucko.bytebin.util.compression;

import java.io.IOException;

public abstract class AbstractCompressionStream {
    public abstract byte[] compress(byte[] buf) throws IOException;
    public abstract byte[] decompress(byte[] buf) throws IOException;

}
