package com.github.rmannibucau.playx.servlet.servlet.internal;

import java.io.IOException;
import java.io.OutputStream;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

class OutputStreamAdapter extends ServletOutputStream {

    private final OutputStream delegate;

    private WriteListener listener;

    OutputStreamAdapter(final OutputStream output) {
        this.delegate = output;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setWriteListener(final WriteListener listener) {
        this.listener = listener;
        try {
            listener.onWritePossible();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void write(final int b) throws IOException {
        try {
            delegate.write(b);
        } catch (final IOException ioe) {
            listener.onError(ioe);
            throw ioe;
        }
    }

    @Override
    public void write(final byte[] b) throws IOException {
        try {
            delegate.write(b);
        } catch (final IOException ioe) {
            listener.onError(ioe);
            throw ioe;
        }
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        try {
            delegate.write(b, off, len);
        } catch (final IOException ioe) {
            listener.onError(ioe);
            throw ioe;
        }
    }

    @Override
    public void flush() throws IOException {
        try {
            delegate.flush();
        } catch (final IOException ioe) {
            listener.onError(ioe);
            throw ioe;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } catch (final IOException ioe) {
            listener.onError(ioe);
            throw ioe;
        }
    }
}
