package me.lucko.bytebin.util.compression;

import com.google.common.io.ByteStreams;
import com.nixxcode.jvmbrotli.dec.BrotliInputStream;
import com.nixxcode.jvmbrotli.enc.BrotliOutputStream;
import com.nixxcode.jvmbrotli.enc.Encoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class BrotliCompressionStream extends AbstractCompressionStream {
    private static final Encoder.Parameters PARAMS = new Encoder.Parameters().setQuality(4);

    public byte[] compress(byte[] buf) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length);
        try (BrotliOutputStream brotliOut = new BrotliOutputStream(out, PARAMS)) {
            brotliOut.write(buf);
        }
        return out.toByteArray();
    }

    public byte[] decompress(byte[] buf) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(buf);
        try (BrotliInputStream brotliIn = new BrotliInputStream(in)) {
            return ByteStreams.toByteArray(brotliIn);
        }
    }

}
