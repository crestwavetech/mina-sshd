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
package org.apache.sshd.common.session;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.agent.common.AgentForwardSupport;
import org.apache.sshd.client.channel.AbstractClientChannel;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.RequestHandler;
import org.apache.sshd.common.channel.Window;
import org.apache.sshd.common.forward.TcpipForwarder;
import org.apache.sshd.common.forward.TcpipForwarderFactory;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.Int2IntFunction;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.BufferUtils;
import org.apache.sshd.common.util.closeable.AbstractInnerCloseable;
import org.apache.sshd.server.channel.OpenChannelException;
import org.apache.sshd.server.x11.X11ForwardSupport;

/**
 * Base implementation of ConnectionService.
 *
 * @param <S> Type of {@link AbstractSession} being used
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class AbstractConnectionService<S extends AbstractSession> extends AbstractInnerCloseable implements ConnectionService {
    /**
     * Property that can be used to configure max. allowed concurrent active channels
     *
     * @see #registerChannel(Channel)
     */
    public static final String MAX_CONCURRENT_CHANNELS_PROP = "max-sshd-channels";
    public static final int DEFAULT_MAX_CHANNELS = Integer.MAX_VALUE;

    /**
     * Default growth factor function used to resize response buffers
     */
    public static final Int2IntFunction RESPONSE_BUFFER_GROWTH_FACTOR = Int2IntFunction.Utils.add(Byte.SIZE);

    /**
     * Map of channels keyed by the identifier
     */
    protected final Map<Integer, Channel> channels = new ConcurrentHashMap<>();
    /**
     * Next channel identifier
     */
    protected final AtomicInteger nextChannelId = new AtomicInteger(0);

    /**
     * The tcpip forwarder
     */
    protected final TcpipForwarder tcpipForwarder;
    protected final AgentForwardSupport agentForward;
    protected final X11ForwardSupport x11Forward;
    private final AtomicBoolean allowMoreSessions = new AtomicBoolean(true);

    private final S sessionInstance;

    protected AbstractConnectionService(S session) {
        sessionInstance = ValidateUtils.checkNotNull(session, "No session");
        agentForward = new AgentForwardSupport(this);
        x11Forward = new X11ForwardSupport(this);

        FactoryManager manager =
                ValidateUtils.checkNotNull(session.getFactoryManager(), "No factory manager");
        TcpipForwarderFactory factory =
                ValidateUtils.checkNotNull(manager.getTcpipForwarderFactory(), "No forwarder factory");
        tcpipForwarder = ValidateUtils.checkNotNull(factory.create(this), "No forwarder created for %s", session);
    }

    public Collection<Channel> getChannels() {
        return channels.values();
    }

    @Override
    public S getSession() {
        return sessionInstance;
    }

    @Override
    public void start() {
        // do nothing
    }

    @Override
    public TcpipForwarder getTcpipForwarder() {
        return tcpipForwarder;
    }

    @Override
    protected Closeable getInnerCloseable() {
        return builder()
                .sequential(getTcpipForwarder(), agentForward, x11Forward)
                .parallel(channels.values())
                .build();
    }

    protected int getNextChannelId() {
        return nextChannelId.getAndIncrement();
    }

    @Override
    public int registerChannel(Channel channel) throws IOException {
        final Session session = getSession();
        int maxChannels = PropertyResolverUtils.getIntProperty(session, MAX_CONCURRENT_CHANNELS_PROP, DEFAULT_MAX_CHANNELS);
        int curSize = channels.size();
        if (curSize > maxChannels) {
            throw new IllegalStateException("Currently active channels (" + curSize + ") at max.: " + maxChannels);
        }

        int channelId = getNextChannelId();
        channel.init(this, session, channelId);
        synchronized (lock) {
            if (isClosing()) {
                throw new IllegalStateException("Session is being closed: " + toString());
            }

            channels.put(channelId, channel);
        }

        if (log.isDebugEnabled()) {
            log.debug("registerChannel({})[id={}] {}", this, channelId, channel);
        }
        return channelId;
    }

    /**
     * Remove this channel from the list of managed channels
     *
     * @param channel the channel
     */
    @Override
    public void unregisterChannel(Channel channel) {
        Channel result = channels.remove(channel.getId());
        if (log.isDebugEnabled()) {
            log.debug("unregisterChannel({}) result={}", channel, result);
        }
    }

    @Override
    public void process(int cmd, Buffer buffer) throws Exception {
        switch (cmd) {
            case SshConstants.SSH_MSG_CHANNEL_OPEN:
                channelOpen(buffer);
                break;
            case SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION:
                channelOpenConfirmation(buffer);
                break;
            case SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE:
                channelOpenFailure(buffer);
                break;
            case SshConstants.SSH_MSG_CHANNEL_REQUEST:
                channelRequest(buffer);
                break;
            case SshConstants.SSH_MSG_CHANNEL_DATA:
                channelData(buffer);
                break;
            case SshConstants.SSH_MSG_CHANNEL_EXTENDED_DATA:
                channelExtendedData(buffer);
                break;
            case SshConstants.SSH_MSG_CHANNEL_FAILURE:
                channelFailure(buffer);
                break;
            case SshConstants.SSH_MSG_CHANNEL_WINDOW_ADJUST:
                channelWindowAdjust(buffer);
                break;
            case SshConstants.SSH_MSG_CHANNEL_EOF:
                channelEof(buffer);
                break;
            case SshConstants.SSH_MSG_CHANNEL_CLOSE:
                channelClose(buffer);
                break;
            case SshConstants.SSH_MSG_GLOBAL_REQUEST:
                globalRequest(buffer);
                break;
            case SshConstants.SSH_MSG_REQUEST_SUCCESS:
                requestSuccess(buffer);
                break;
            case SshConstants.SSH_MSG_REQUEST_FAILURE:
                requestFailure(buffer);
                break;
            default:
                throw new IllegalStateException("Unsupported command: " + SshConstants.getCommandMessageName(cmd));
        }
    }

    @Override
    public boolean isAllowMoreSessions() {
        return allowMoreSessions.get();
    }

    @Override
    public void setAllowMoreSessions(boolean allow) {
        if (log.isDebugEnabled()) {
            log.debug("setAllowMoreSessions({}): {}", this, allow);
        }
        allowMoreSessions.set(allow);
    }

    public void channelOpenConfirmation(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        int sender = buffer.getInt();
        int rwsize = buffer.getInt();
        int rmpsize = buffer.getInt();
        if (log.isDebugEnabled()) {
            log.debug("channelOpenConfirmation({}) SSH_MSG_CHANNEL_OPEN_CONFIRMATION sender={}, window-size={}, packet-size={}",
                      channel, sender, rwsize, rmpsize);
        }
        /*
         * NOTE: the 'sender' of the SSH_MSG_CHANNEL_OPEN_CONFIRMATION is the
         * recipient on the client side - see rfc4254 section 5.1:
         *
         *      'sender channel' is the channel number allocated by the other side
         *
         * in our case, the server
         */
        channel.handleOpenSuccess(sender, rwsize, rmpsize, buffer);
    }

    public void channelOpenFailure(Buffer buffer) throws IOException {
        AbstractClientChannel channel = (AbstractClientChannel) getChannel(buffer);
        int id = channel.getId();
        if (log.isDebugEnabled()) {
            log.debug("channelOpenFailure({}) Received SSH_MSG_CHANNEL_OPEN_FAILURE", channel);
        }
        channels.remove(id);
        channel.handleOpenFailure(buffer);
    }

    /**
     * Process incoming data on a channel
     *
     * @param buffer the buffer containing the data
     * @throws IOException if an error occurs
     */
    public void channelData(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleData(buffer);
    }

    /**
     * Process incoming extended data on a channel
     *
     * @param buffer the buffer containing the data
     * @throws IOException if an error occurs
     */
    public void channelExtendedData(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleExtendedData(buffer);
    }

    /**
     * Process a window adjust packet on a channel
     *
     * @param buffer the buffer containing the window adjustment parameters
     * @throws IOException if an error occurs
     */
    public void channelWindowAdjust(Buffer buffer) throws IOException {
        try {
            Channel channel = getChannel(buffer);
            channel.handleWindowAdjust(buffer);
        } catch (SshException e) {
            if (log.isDebugEnabled()) {
                log.debug("channelWindowAdjust {} error: {}", e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * Process end of file on a channel
     *
     * @param buffer the buffer containing the packet
     * @throws IOException if an error occurs
     */
    public void channelEof(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleEof();
    }

    /**
     * Close a channel due to a close packet received
     *
     * @param buffer the buffer containing the packet
     * @throws IOException if an error occurs
     */
    public void channelClose(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleClose();
    }

    /**
     * Service a request on a channel
     *
     * @param buffer the buffer containing the request
     * @throws IOException if an error occurs
     */
    public void channelRequest(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleRequest(buffer);
    }

    /**
     * Process a failure on a channel
     *
     * @param buffer the buffer containing the packet
     * @throws IOException if an error occurs
     */
    public void channelFailure(Buffer buffer) throws IOException {
        Channel channel = getChannel(buffer);
        channel.handleFailure();
    }

    /**
     * Retrieve the channel designated by the given packet
     *
     * @param buffer the incoming packet
     * @return the target channel
     * @throws IOException if the channel does not exists
     */
    protected Channel getChannel(Buffer buffer) throws IOException {
        int recipient = buffer.getInt();
        Channel channel = channels.get(recipient);
        if (channel == null) {
            buffer.rpos(buffer.rpos() - 5);
            int cmd = buffer.getUByte();
            throw new SshException("Received " + SshConstants.getCommandMessageName(cmd) + " on unknown channel " + recipient);
        }
        return channel;
    }

    protected void channelOpen(Buffer buffer) throws Exception {
        String type = buffer.getString();
        final int sender = buffer.getInt();
        final int rwsize = buffer.getInt();
        final int rmpsize = buffer.getInt();
        /*
         * NOTE: the 'sender' is the identifier assigned by the remote side - the server in this case
         */
        if (log.isDebugEnabled()) {
            log.debug("channelOpen({}) SSH_MSG_CHANNEL_OPEN sender={}, type={}, window-size={}, packet-size={}",
                      this, sender, type, rwsize, rmpsize);
        }

        if (isClosing()) {
            // TODO add language tag
            sendChannelOpenFailure(buffer, sender, SshConstants.SSH_OPEN_CONNECT_FAILED, "Server is shutting down while attempting to open channel type=" + type, "");
            return;
        }

        if (!isAllowMoreSessions()) {
            // TODO add language tag
            sendChannelOpenFailure(buffer, sender, SshConstants.SSH_OPEN_CONNECT_FAILED, "additional sessions disabled", "");
            return;
        }

        final Session session = getSession();
        FactoryManager manager = ValidateUtils.checkNotNull(session.getFactoryManager(), "No factory manager");
        final Channel channel = NamedFactory.Utils.create(manager.getChannelFactories(), type);
        if (channel == null) {
            // TODO add language tag
            sendChannelOpenFailure(buffer, sender, SshConstants.SSH_OPEN_UNKNOWN_CHANNEL_TYPE, "Unsupported channel type: " + type, "");
            return;
        }

        final int channelId = registerChannel(channel);
        channel.open(sender, rwsize, rmpsize, buffer).addListener(new SshFutureListener<OpenFuture>() {
            @Override
            @SuppressWarnings("synthetic-access")
            public void operationComplete(OpenFuture future) {
                try {
                    if (future.isOpened()) {
                        Window window = channel.getLocalWindow();
                        if (log.isDebugEnabled()) {
                            log.debug("operationComplete({}) send SSH_MSG_CHANNEL_OPEN_CONFIRMATION recipient={}, sender={}, window-size={}, packet-size={}",
                                      channel, sender, channelId, window.getSize(), window.getPacketSize());
                        }
                        Buffer buf = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_CONFIRMATION, Integer.SIZE);
                        buf.putInt(sender); // remote (server side) identifier
                        buf.putInt(channelId);  // local (client side) identifier
                        buf.putInt(window.getSize());
                        buf.putInt(window.getPacketSize());
                        session.writePacket(buf);
                    } else {
                        Throwable exception = future.getException();
                        if (exception != null) {
                            String message = exception.getMessage();
                            int reasonCode = 0;
                            if (exception instanceof OpenChannelException) {
                                reasonCode = ((OpenChannelException) exception).getReasonCode();
                            } else {
                                message = exception.getClass().getSimpleName() + " while opening channel: " + message;
                            }

                            Buffer buf = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE, message.length() + Long.SIZE);
                            sendChannelOpenFailure(buf, sender, reasonCode, message, "");
                        }
                    }
                } catch (IOException e) {
                    if (log.isDebugEnabled()) {
                        log.debug("operationComplete({}) {}: {}",
                                  AbstractConnectionService.this, e.getClass().getSimpleName(), e.getMessage());
                    }
                    session.exceptionCaught(e);
                }
            }
        });
    }

    protected void sendChannelOpenFailure(Buffer buffer, int sender, int reasonCode, String message, String lang) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("sendChannelOpenFailure({}) sender={}, reason={}, lang={}, message='{}'",
                      this, sender, SshConstants.getOpenErrorCodeName(reasonCode), lang, message);
        }

        final Session session = getSession();
        Buffer buf = session.prepareBuffer(SshConstants.SSH_MSG_CHANNEL_OPEN_FAILURE, BufferUtils.clear(buffer));
        buf.putInt(sender);
        buf.putInt(reasonCode);
        buf.putString(message);
        buf.putString(lang);
        session.writePacket(buf);
    }

    /**
     * Process global requests
     *
     * @param buffer The request {@link Buffer}
     * @throws Exception If failed to process the request
     */
    protected void globalRequest(Buffer buffer) throws Exception {
        String req = buffer.getString();
        boolean wantReply = buffer.getBoolean();
        if (log.isDebugEnabled()) {
            log.debug("globalRequest({}) received SSH_MSG_GLOBAL_REQUEST {} want-reply={}",
                      this, req, Boolean.valueOf(wantReply));
        }

        Session session = getSession();
        FactoryManager manager =
                ValidateUtils.checkNotNull(session.getFactoryManager(), "No factory manager");
        List<RequestHandler<ConnectionService>> handlers = manager.getGlobalRequestHandlers();
        if (GenericUtils.size(handlers) > 0) {
            for (RequestHandler<ConnectionService> handler : handlers) {
                RequestHandler.Result result;
                try {
                    result = handler.process(this, req, wantReply, buffer);
                } catch (Exception e) {
                    log.warn("globalRequest({})[{}, want-reply={}] failed ({}) to process: {}",
                             this, req, wantReply, e.getClass().getSimpleName(), e.getMessage());
                    if (log.isDebugEnabled()) {
                        log.debug("globalRequest(" + this + ")[" + req + ", want-reply=" + wantReply + "] failure details", e);
                    }
                    result = RequestHandler.Result.ReplyFailure;
                }

                // if Unsupported then check the next handler in line
                if (RequestHandler.Result.Unsupported.equals(result)) {
                    if (log.isTraceEnabled()) {
                        log.trace("globalRequest({}) {}#process({})[want-reply={}] : {}",
                                  this, handler.getClass().getSimpleName(), req, wantReply, result);
                    }
                } else {
                    sendGlobalResponse(buffer, req, result, wantReply);
                    return;
                }
            }
        }

        log.warn("globalRequest({}) unknown global request: {}", this, req);
        sendGlobalResponse(buffer, req, RequestHandler.Result.Unsupported, wantReply);
    }

    protected void sendGlobalResponse(Buffer buffer, String req, RequestHandler.Result result, boolean wantReply) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("sendGlobalResponse({})[{}] result={}, want-reply={}", this, req, result, wantReply);
        }

        if (RequestHandler.Result.Replied.equals(result) || (!wantReply)) {
            return;
        }

        byte cmd = RequestHandler.Result.ReplySuccess.equals(result)
                 ? SshConstants.SSH_MSG_REQUEST_SUCCESS
                 : SshConstants.SSH_MSG_REQUEST_FAILURE;
        Session session = getSession();
        buffer = session.prepareBuffer(cmd, BufferUtils.clear(buffer));
        buffer.putByte(cmd);
        session.writePacket(buffer);
    }

    protected void requestSuccess(Buffer buffer) throws Exception {
        getSession().requestSuccess(buffer);
    }

    protected void requestFailure(Buffer buffer) throws Exception {
        getSession().requestFailure(buffer);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + getSession() + "]";
    }
}
