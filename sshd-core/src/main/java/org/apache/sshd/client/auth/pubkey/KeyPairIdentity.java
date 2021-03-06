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
package org.apache.sshd.client.auth.pubkey;

import java.security.KeyPair;
import java.security.PublicKey;

import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.kex.KexFactoryManager;
import org.apache.sshd.common.signature.Signature;
import org.apache.sshd.common.util.ValidateUtils;

/**
 * Uses a {@link KeyPair} to generate the identity signature
 *
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class KeyPairIdentity implements PublicKeyIdentity {
    private final KeyPair pair;
    private final KexFactoryManager manager;

    public KeyPairIdentity(KexFactoryManager manager, KeyPair pair) {
        this.manager = ValidateUtils.checkNotNull(manager, "No manager");
        this.pair = ValidateUtils.checkNotNull(pair, "No key pair");
    }

    @Override
    public PublicKey getPublicKey() {
        return pair.getPublic();
    }

    @Override
    public byte[] sign(byte[] data) throws Exception {
        String keyType = KeyUtils.getKeyType(getPublicKey());
        Signature verifier = ValidateUtils.checkNotNull(
                NamedFactory.Utils.create(manager.getSignatureFactories(), keyType),
                "No signer could be located for key type=%s",
                keyType);
        verifier.initSigner(pair.getPrivate());
        verifier.update(data);
        return verifier.sign();
    }

    @Override
    public String toString() {
        PublicKey pubKey = getPublicKey();
        return getClass().getSimpleName() + "[" + manager + "]"
             + " type=" + KeyUtils.getKeyType(pubKey)
             + ", fingerprint=" + KeyUtils.getFingerPrint(pubKey);
    }
}