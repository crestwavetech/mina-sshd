/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.common.io.nio2;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.io.IoHandler;
import org.apache.sshd.common.io.IoService;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.Readable;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.closeable.AbstractCloseable;

/**
 */
public class Nio2Session extends AbstractCloseable implements IoSession {

    public static final int DEFAULT_READBUF_SIZE = 32 * 1024;

    private static final AtomicLong SESSION_ID_GENERATOR = new AtomicLong(100L);

    private final long id = SESSION_ID_GENERATOR.incrementAndGet();
    private final Nio2Service service;
    private final IoHandler handler;
    private final AsynchronousSocketChannel socket;
    private final Map<Object, Object> attributes = new HashMap<Object, Object>();
    private final SocketAddress localAddress;
    private final SocketAddress remoteAddress;
    private final FactoryManager manager;
    private final Queue<Nio2DefaultIoWriteFuture> writes = new LinkedTransferQueue<>();
    private final AtomicReference<Nio2DefaultIoWriteFuture> currentWrite = new AtomicReference<>();

    public Nio2Session(Nio2Service service, FactoryManager manager, IoHandler handler, AsynchronousSocketChannel socket) throws IOException {
        this.service = service;
        this.manager = manager;
        this.handler = handler;
        this.socket = socket;
        this.localAddress = socket.getLocalAddress();
        this.remoteAddress = socket.getRemoteAddress();
        log.debug("Creating IoSession on {} from {}", localAddress, remoteAddress);
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public Object getAttribute(Object key) {
        return attributes.get(key);
    }

    @Override
    public Object setAttribute(Object key, Object value) {
        return attributes.put(key, value);
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return localAddress;
    }

    public void suspend() {
        try {
            this.socket.shutdownInput();
        } catch (IOException e) {
            // Ignore
        }
        try {
            this.socket.shutdownOutput();
        } catch (IOException e) {
            // Ignore
        }
    }

    @Override
    public IoWriteFuture write(Buffer buffer) {
        if (log.isDebugEnabled()) {
            log.debug("Writing {} bytes", Integer.valueOf(buffer.available()));
        }

        ByteBuffer buf = ByteBuffer.wrap(buffer.array(), buffer.rpos(), buffer.available());
        final Nio2DefaultIoWriteFuture future = new Nio2DefaultIoWriteFuture(null, buf);
        if (isClosing()) {
            Throwable exc = new ClosedChannelException();
            future.setException(exc);
            exceptionCaught(exc);
            return future;
        }
        writes.add(future);
        startWriting();
        return future;
    }

    private void exceptionCaught(Throwable exc) {
        if (!closeFuture.isClosed()) {
            if (isClosing() || !socket.isOpen()) {
                close(true);
            } else {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Caught {}[{}] - calling handler", exc.getClass().getSimpleName(), exc.getMessage());
                    }
                    handler.exceptionCaught(this, exc);
                } catch (Throwable t) {
                    if (log.isDebugEnabled()) {
                        log.debug("Exception handler threw {}, closing the session: {}",
                                  t.getClass().getSimpleName(), t.getMessage());
                    }
                    close(true);
                }
            }
        }
    }

    @Override
    protected CloseFuture doCloseGracefully() {
        return builder().when(writes).build().close(false);
    }

    @Override
    protected void doCloseImmediately() {
        for (;;) {
            Nio2DefaultIoWriteFuture future = writes.poll();
            if (future != null) {
                future.setException(new ClosedChannelException());
            } else {
                break;
            }
        }
        try {
            socket.close();
        } catch (IOException e) {
            log.info("Exception caught while closing socket", e);
        }
        service.sessionClosed(this);
        super.doCloseImmediately();
        try {
            handler.sessionClosed(this);
        } catch (Exception e) {
            // Ignore
            log.debug("Exception caught while calling IoHandler#sessionClosed", e);
        }
    }

    @Override
    public IoService getService() {
        return service;
    }

    public void startReading() {
        startReading(PropertyResolverUtils.getIntProperty(manager, FactoryManager.NIO2_READ_BUFFER_SIZE, DEFAULT_READBUF_SIZE));
    }

    public void startReading(int bufSize) {
        startReading(new byte[bufSize]);
    }

    public void startReading(byte[] buf) {
        startReading(buf, 0, buf.length);
    }

    public void startReading(byte[] buf, int offset, int len) {
        startReading(ByteBuffer.wrap(buf, offset, len));
    }

    public void startReading(final ByteBuffer buffer) {
        doReadCycle(buffer, new Readable() {
            @Override
            public int available() {
                return buffer.remaining();
            }

            @Override
            public void getRawBytes(byte[] data, int offset, int len) {
                buffer.get(data, offset, len);
            }
        });
    }

    protected void doReadCycle(final ByteBuffer buffer, final Readable bufReader) {
        final Nio2CompletionHandler<Integer, Object> completion = new Nio2CompletionHandler<Integer, Object>() {
            @Override
            @SuppressWarnings("synthetic-access")
            protected void onCompleted(Integer result, Object attachment) {
                try {
                    if (result >= 0) {
                        log.debug("Read {} bytes", result);
                        buffer.flip();
                        handler.messageReceived(Nio2Session.this, bufReader);
                        if (!closeFuture.isClosed()) {
                            // re-use reference for next iteration since we finished processing it
                            buffer.clear();
                            doReadCycle(buffer, this);
                        } else {
                            log.debug("IoSession has been closed, stop reading");
                        }
                    } else {
                        log.debug("Socket has been disconnected, closing IoSession now");
                        Nio2Session.this.close(true);
                    }
                } catch (Throwable exc) {
                    failed(exc, attachment);
                }
            }

            @Override
            @SuppressWarnings("synthetic-access")
            protected void onFailed(Throwable exc, Object attachment) {
                exceptionCaught(exc);
            }
        };
        doReadCycle(buffer, completion);
    }

    protected void doReadCycle(ByteBuffer buffer, Nio2CompletionHandler<Integer, Object> completion) {
        socket.read(buffer, null, completion);
    }

    @SuppressWarnings("synthetic-access")
    private void startWriting() {
        final Nio2DefaultIoWriteFuture future = writes.peek();
        if (future != null) {
            if (currentWrite.compareAndSet(null, future)) {
                try {
                    final ByteBuffer buffer = future.getBuffer();
                    socket.write(buffer, null, new Nio2CompletionHandler<Integer, Object>() {
                        @Override
                        protected void onCompleted(Integer result, Object attachment) {
                            if (buffer.hasRemaining()) {
                                try {
                                    socket.write(buffer, null, this);
                                } catch (Throwable t) {
                                    log.debug("Exception caught while writing", t);
                                    future.setWritten();
                                    finishWrite();
                                }
                            } else {
                                log.debug("Finished writing");
                                future.setWritten();
                                finishWrite();
                            }
                        }

                        @Override
                        protected void onFailed(Throwable exc, Object attachment) {
                            future.setException(exc);
                            exceptionCaught(exc);
                            finishWrite();
                        }

                        private void finishWrite() {
                            writes.remove(future);
                            currentWrite.compareAndSet(future, null);
                            startWriting();
                        }
                    });
                } catch (RuntimeException e) {
                    future.setWritten();
                    throw e;
                }
            }
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[local=" + localAddress + ", remote=" + remoteAddress + "]";
    }
}
