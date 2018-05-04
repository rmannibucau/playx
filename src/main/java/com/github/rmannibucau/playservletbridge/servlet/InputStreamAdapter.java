package com.github.rmannibucau.playservletbridge.servlet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

public class InputStreamAdapter extends ServletInputStream {

    private final ByteArrayInputStream buffer;

    private ReadListener listener;

    public InputStreamAdapter(final String buffer, final String encoding) throws UnsupportedEncodingException {
        this.buffer = new ByteArrayInputStream(buffer.getBytes(encoding));
    }

    @Override
    public boolean isFinished() {
        return true;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(final ReadListener listener) {
        this.listener = listener;
        if (buffer.available() > 0) {
            try {
                listener.onDataAvailable();
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public int read() throws IOException {
        try {
            final int read = buffer.read();
            if (read < 0) {
                listener.onAllDataRead();
            }
            return read;
        } catch (final Throwable ioe) {
            if (listener != null) {
                listener.onError(ioe);
            }
            throw ioe;
        }
    }
}
