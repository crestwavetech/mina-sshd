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
package org.apache.sshd.client.auth;

import static org.apache.sshd.common.SshConstants.SSH_MSG_USERAUTH_INFO_REQUEST;
import static org.apache.sshd.common.SshConstants.SSH_MSG_USERAUTH_INFO_RESPONSE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.apache.sshd.client.ClientFactoryManager;
import org.apache.sshd.client.UserAuth;
import org.apache.sshd.client.UserInteraction;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.util.AbstractLoggingBean;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.buffer.Buffer;

/**
 * TODO Add javadoc
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class UserAuthKeyboardInteractive extends AbstractLoggingBean implements UserAuth {

    public static class UserAuthKeyboardInteractiveFactory implements NamedFactory<UserAuth> {
        public static final UserAuthKeyboardInteractiveFactory INSTANCE = new UserAuthKeyboardInteractiveFactory();

        public UserAuthKeyboardInteractiveFactory() {
            super();
        }

        @Override
        public String getName() {
            return "keyboard-interactive";
        }
        @Override
        public UserAuth create() {
            return new UserAuthKeyboardInteractive();
        }
    }

    private ClientSession session;
    private String service;
    private Iterator<String> passwords;
    private String current;
    private int nbTrials;
    private int maxTrials;

    public UserAuthKeyboardInteractive() {
        super();
    }

    @Override
    public void init(ClientSession session, String service, Collection<?> identities) throws Exception {
        this.session = session;
        this.service = service;
        List<String> pwds = new ArrayList<String>();
        for (Object o : identities) {
            if (o instanceof String) {
                pwds.add((String) o);
            }
        }
        this.passwords = pwds.iterator();
        this.maxTrials = session.getIntProperty(ClientFactoryManager.PASSWORD_PROMPTS, 3);
    }

    @Override
    public boolean process(Buffer buffer) throws Exception {
        if (buffer == null) {
            if (passwords.hasNext()) {
                current = passwords.next();
            } else if (nbTrials++ < maxTrials) {
                current = null;
            } else {
                return false;
            }
            log.debug("Send SSH_MSG_USERAUTH_REQUEST for keyboard-interactive");
            buffer = session.createBuffer(SshConstants.SSH_MSG_USERAUTH_REQUEST);
            buffer.putString(session.getUsername());
            buffer.putString(service);
            buffer.putString("keyboard-interactive");
            buffer.putString("");
            buffer.putString("");
            session.writePacket(buffer);
            return true;
        }
        byte cmd = buffer.getByte();
        if (cmd == SSH_MSG_USERAUTH_INFO_REQUEST) {
            log.debug("Received SSH_MSG_USERAUTH_INFO_REQUEST");
            String name = buffer.getString();
            String instruction = buffer.getString();
            String language_tag = buffer.getString();
            if (log.isDebugEnabled()) {
                log.debug("SSH_MSG_USERAUTH_INFO_REQUEST {} {} {}", name, instruction, language_tag);
            }
            int num = buffer.getInt();
            String[] prompt = new String[num];
            boolean[] echo = new boolean[num];
            for (int i = 0; i < num; i++) {
                prompt[i] = buffer.getString();
                echo[i] = (buffer.getByte() != 0);
            }
            
            if (log.isDebugEnabled()) {
                log.debug("Promt: {}", Arrays.toString(prompt));
                log.debug("Echo: {}", echo);
            }

            String[] rep = null;
            if (num == 0) {
                rep = GenericUtils.EMPTY_STRING_ARRAY;
            } else if (num == 1 && current != null && !echo[0] && prompt[0].toLowerCase().startsWith("password:")) {
                rep = new String[] { current };
            } else {
                UserInteraction ui = session.getUserInteraction();
                if (ui == null) {
                    ui = session.getFactoryManager().getUserInteraction();
                }
                if (ui != null) {
                    String dest = session.getUsername() + "@" + session.getIoSession().getRemoteAddress().toString();
                    rep = ui.interactive(dest, name, instruction, prompt, echo);
                }
            }
            if (rep == null) {
                return false;
            }

            buffer = session.createBuffer(SSH_MSG_USERAUTH_INFO_RESPONSE);
            buffer.putInt(rep.length);
            for (String r : rep) {
                buffer.putString(r);
            }
            session.writePacket(buffer);
            return true;
        }
        throw new IllegalStateException("Received unknown packet");
    }

    @Override
    public void destroy() {
        // nothing
    }
}
