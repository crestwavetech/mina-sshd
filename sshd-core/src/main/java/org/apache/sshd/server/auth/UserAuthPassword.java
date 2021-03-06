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
package org.apache.sshd.server.auth;

import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.password.PasswordChangeRequiredException;
import org.apache.sshd.server.session.ServerSession;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class UserAuthPassword extends AbstractUserAuth {
    public static final String NAME = UserAuthPasswordFactory.NAME;

    public UserAuthPassword() {
        super(NAME);
    }

    @Override
    public Boolean doAuth(Buffer buffer, boolean init) throws Exception {
        ValidateUtils.checkTrue(init, "Instance not initialized");

        boolean newPassword = buffer.getBoolean();
        String password = buffer.getString();
        if (newPassword) {
            return handleClientPasswordChangeRequest(buffer, getServerSession(), getUsername(), password, buffer.getString());
        } else {
            return checkPassword(buffer, getServerSession(), getUsername(), password);
        }
    }

    /**
     * Invokes the configured {@link PasswordAuthenticator} and returns the result.
     * If {@link PasswordChangeRequiredException} thrown by the authenticator then
     * {@link #handleServerPasswordChangeRequest(Buffer, ServerSession, String, String, PasswordChangeRequiredException)}
     * is invoked
     *
     * @param buffer   The received {@link Buffer} to be re-used if need to send
     *                 a password change request
     * @param session  The {@link ServerSession} through which the request was received
     * @param username The username
     * @param password The password
     * @return The authentication result - if {@code null} then exception was handled
     * internally and authentication is still in progress
     * @throws Exception If internal error during authentication (exception for
     * {@link PasswordChangeRequiredException} which is handled internally)
     * @see #handleServerPasswordChangeRequest(Buffer, ServerSession, String, String, PasswordChangeRequiredException)
     */
    protected Boolean checkPassword(Buffer buffer, ServerSession session, String username, String password) throws Exception {
        PasswordAuthenticator auth = session.getPasswordAuthenticator();
        if (auth == null) {
            if (log.isDebugEnabled()) {
                log.debug("checkPassword({}) no password authenticator", session);
            }
            return false;
        }

        try {
            boolean authed = auth.authenticate(username, password, session);
            if (log.isDebugEnabled()) {
                log.debug("checkPassword({}) authentication result: {}", session, authed);
            }
            return authed;
        } catch (PasswordChangeRequiredException e) {
            if (log.isDebugEnabled()) {
                log.debug("checkPassword({}) password change required: {}", session, e.getMessage());
            }
            return handleServerPasswordChangeRequest(buffer, session, username, password, e);
        }
    }

    /**
     * Invoked when the client sends a {@code SSH_MSG_USERAUTH_REQUEST} indicating
     * a password change. Throws {@link UnsupportedOperationException} by default
     *
     * @param buffer The {@link Buffer} to re-use in order to respond
     * @param session The associated {@link ServerSession}
     * @param username The username
     * @param oldPassword The old password
     * @param newPassword The new password
     * @return Password change and authentication result - {@code null} means
     * authentication incomplete - i.e., handler has sent some extra query.
     * @throws Exception If failed to handle the request.
     */
    protected Boolean handleClientPasswordChangeRequest(
            Buffer buffer, ServerSession session, String username, String oldPassword, String newPassword)
                        throws Exception {
        throw new UnsupportedOperationException("Password change not supported");
    }

    /**
     * Invoked by {@link #checkPassword(Buffer, ServerSession, String, String)}
     * when a {@link PasswordChangeRequiredException} was thrown by the authenticator.
     * By default it re-throws the original exception.
     *
     * @param buffer   The received {@link Buffer} to be re-used if need to send
     *                 a password change request
     * @param session  The {@link ServerSession} through which the request was received
     * @param username The username
     * @param password The (rejected) password
     * @param e        The original thrown exception
     * @return {@code null} by default to indicate incomplete authentication
     * @throws Exception If failed to dispatch the message
     */
    protected Boolean handleServerPasswordChangeRequest(
            Buffer buffer, ServerSession session, String username, String password, PasswordChangeRequiredException e)
                throws Exception {
        String prompt = e.getPrompt();
        String lang = e.getLanguage();
        if (log.isDebugEnabled()) {
            log.debug("handlePasswordChangeRequest({}) password change required - prompt={}, lang={}",
                      session, prompt, lang);
        }

        buffer = session.prepareBuffer(SshConstants.SSH_MSG_USERAUTH_PASSWD_CHANGEREQ, buffer);
        buffer.putString(prompt);
        buffer.putString(lang);
        session.writePacket(buffer);
        return null;    // authentication incomplete
    }
}
