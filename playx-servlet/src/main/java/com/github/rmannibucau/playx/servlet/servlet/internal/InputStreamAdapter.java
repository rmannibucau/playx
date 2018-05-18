package com.github.rmannibucau.playx.servlet.servlet.internal;

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

class InputStreamAdapter extends ServletInputStream {

    private final InputStream buffer;

    private ReadListener listener;

    private boolean done;

    InputStreamAdapter(final InputStream buffer) {
        this.buffer = buffer;
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
        if (!done) {
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
                done = true;
                if (listener != null) {
                    listener.onAllDataRead();
                }
            }
            return read;
        } catch (final Throwable ioe) {
            if (listener != null) {
                done = true;
                listener.onError(ioe);
            }
            throw ioe;
        }
    }
}
