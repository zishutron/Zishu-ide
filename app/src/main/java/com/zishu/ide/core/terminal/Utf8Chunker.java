package com.zishu.ide.core.terminal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/** Turns a stream of byte chunks into text without splitting a multi-byte character that arrives in two reads. */
public final class Utf8Chunker {
    private final CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private byte[] carry = new byte[0];

    public String decode(byte[] data, int off, int len) {
        byte[] all = new byte[carry.length + len];
        System.arraycopy(carry, 0, all, 0, carry.length);
        System.arraycopy(data, off, all, carry.length, len);
        ByteBuffer in = ByteBuffer.wrap(all);
        CharBuffer out = CharBuffer.allocate(all.length + 1);
        decoder.reset();
        decoder.decode(in, out, false);
        int rest = in.remaining();
        carry = new byte[rest];
        in.get(carry);
        out.flip();
        return out.toString();
    }
}
