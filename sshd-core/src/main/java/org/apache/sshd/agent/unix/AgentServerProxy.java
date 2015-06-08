/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sshd.agent.unix;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.agent.SshAgentServer;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.util.AbstractLoggingBean;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.threads.ExecutorServiceCarrier;
import org.apache.sshd.common.util.threads.ThreadUtils;
import org.apache.tomcat.jni.Local;
import org.apache.tomcat.jni.Pool;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.jni.Status;

/**
 * The server side fake agent, acting as an agent, but actually forwarding the requests to the auth channel on the client side.
 */
public class AgentServerProxy extends AbstractLoggingBean implements SshAgentServer, ExecutorServiceCarrier {
    private final ConnectionService service;
    private final String authSocket;
    private final long pool;
    private final long handle;
    private Future<?> piper;
    private final ExecutorService pipeService;
    private final boolean pipeCloseOnExit;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean innerFinished = new AtomicBoolean(false);

    //used to wake the Local.listen() JNI call
    private static final byte[] END_OF_STREAM_MESSAGE = new byte[] { "END_OF_STREAM".getBytes()[0] };

    public AgentServerProxy(ConnectionService service) throws IOException {
        this(service, null, false);
    }

    public AgentServerProxy(ConnectionService service, ExecutorService executor, boolean shutdownOnExit) throws IOException {
        this.service = service;
        try {
            String authSocket = AprLibrary.createLocalSocketAddress();

            pool = Pool.create(AprLibrary.getInstance().getRootPool());
            handle = Local.create(authSocket, pool);
            this.authSocket = authSocket;

            int result = Local.bind(handle, 0);

            if (result != Status.APR_SUCCESS) {
                throwException(result);
            }
            AprLibrary.secureLocalSocket(authSocket, handle);
            result = Local.listen(handle, 0);
            if (result != Status.APR_SUCCESS) {
                throwException(result);
            }
            
            pipeService = (executor == null) ? ThreadUtils.newSingleThreadExecutor("sshd-AgentServerProxy-PIPE-" + authSocket) : executor;
            pipeCloseOnExit = (executor == pipeService) ? shutdownOnExit : true;
            piper = pipeService.submit(new Runnable() {
                    @SuppressWarnings("synthetic-access")
                    @Override
                    public void run() {
                        try {
                            while (isOpen()) {
                                try {
                                    long clientSock = Local.accept(handle);
                                    if (!isOpen()) {
                                        break;
                                    }
                                    Socket.timeoutSet(clientSock, 10000000);    // TODO allow to configure this
                                    AgentForwardedChannel channel = new AgentForwardedChannel(clientSock);
                                    AgentServerProxy.this.service.registerChannel(channel);
                                    OpenFuture future = channel.open().await();
                                    Throwable t = future.getException();
                                    if (t instanceof Exception) {
                                        throw (Exception) t;
                                    } else if (t != null) {
                                        throw new Exception(t);
                                    }
                                } catch (Exception e) {
                                    if (isOpen()) {
                                        log.info(e.getClass().getSimpleName() + " while authentication forwarding: " + e.getMessage(), e);
                                    }
                                }
                            }
                        } finally {
                            innerFinished.set(true);
                        }
                    }
                });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new SshException(e);
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public ExecutorService getExecutorService() {
        return pipeService;
    }

    @Override
    public boolean isShutdownOnExit() {
        return pipeCloseOnExit;
    }

    @Override
    public String getId() {
        return authSocket;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!open.getAndSet(false)) {
            return; // already closed (or closing)
        }

        final boolean isDebug = log.isDebugEnabled();

        if (handle != 0) {
            if (!innerFinished.get()) {
                try {

                    final long tmpPool = Pool.create(AprLibrary.getInstance().getRootPool());
                    final long tmpSocket = Local.create(authSocket, tmpPool);
                    long connectResult = Local.connect(tmpSocket, 0L);

                    if (connectResult != Status.APR_SUCCESS) {
                        if (isDebug) {
                            log.debug("Unable to connect to socket PIPE {}. APR errcode {}", authSocket, Long.valueOf(connectResult));
                        }
                    }

                    //write a single byte -- just wake up the accept()
                    int sendResult = Socket.send(tmpSocket, END_OF_STREAM_MESSAGE, 0, 1);
                    if (sendResult != 1) {
                        if (isDebug) {
                            log.debug("Unable to send signal the EOS for {}. APR retcode {} != 1", authSocket, Integer.valueOf(sendResult));
                        }
                    }
                } catch (Exception e) {
                    //log eventual exceptions in debug mode
                    if (isDebug) {
                        log.debug("Exception connecting to the PIPE socket: " + authSocket, e);
                    }
                }
            }

            final int closeCode = Socket.close(handle);
            if (closeCode != Status.APR_SUCCESS) {
                log.warn("Exceptions closing the PIPE: {}. APR error code: {} ", authSocket, Integer.valueOf(closeCode));
            }
        }

        try {
            if (authSocket != null) {
                final File socketFile = new File(authSocket);
                if (socketFile.exists()) {
                    if (socketFile.delete()) {
                        if (isDebug) {
                            log.debug("Deleted PIPE socket {}", socketFile);
                        }
                    }

                    if (OsUtils.isUNIX()) {
                        final File parentFile = socketFile.getParentFile();
                        if (parentFile.delete()) {
                            if (isDebug) {
                                log.debug("Deleted parent PIPE socket {}", parentFile);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            //log eventual exceptions in debug mode
            if (isDebug) {
                log.debug("Exception deleting the PIPE socket: " + authSocket, e);
            }
        }
        
        try {
            if ((piper != null) && (!piper.isDone())) {
                piper.cancel(true);
            }
        } finally {
            piper = null;
        }
        
        ExecutorService executor = getExecutorService();
        if ((executor != null) && isShutdownOnExit() && (!executor.isShutdown())) {
            Collection<?>   runners = executor.shutdownNow();
            if (log.isDebugEnabled()) {
                log.debug("Shut down runners count=" + GenericUtils.size(runners));
            }
        }
    }

    /**
     * transform an APR error number in a more fancy exception
     * @param code APR error code
     * @throws java.io.IOException the produced exception for the given APR error number
     */
    static void throwException(int code) throws IOException {
        throw new IOException(org.apache.tomcat.jni.Error.strerror(-code) + " (code: " + code + ")");
    }

}
