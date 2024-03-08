/*
 *  Copyright Nomura Research Institute, Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package jp.openstandia.connector.gateway.server;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.WebSocketPingPongListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class WebSocketServerListener implements WebSocketListener, WebSocketPingPongListener {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServerListener.class);
    public final String clientId;
    public Session clientSession;

    public WebSocketServerListener(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public void onWebSocketConnect(Session session) {
        LOG.info("Connected the Gateway Client. clientId={}, session={}", clientId, session);

        this.clientSession = session;
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        LOG.info("Detected websocket closed. clientId={}, session={}, statusCode={}, reason={}, isSessionOpen={}",
                clientId, clientSession, statusCode, reason, clientSession.isOpen());

        Relay.detach(clientId, this);
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        LOG.warn("Detected websocket error. clientId={}, session={}, message={}, isSessionOpen={}",
                clientId, clientSession, cause.getMessage(), clientSession.isOpen());
    }

    @Override
    public void onWebSocketPong(ByteBuffer payload) {
        LOG.debug("onPong. clientId={}, session={}", clientId, clientSession);

        Relay.handleStartResponse(payload, this);
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len) {
        LOG.debug("onBinary. clientId={}, session={}", clientId, clientSession);

        ByteBuffer buffer = ByteBuffer.wrap(payload, offset, len);
        Relay.handleBodyResponse(buffer, this);
    }

    @Override
    public void onWebSocketPing(ByteBuffer payload) {
        LOG.debug("onPing. clientId={}, session={}", clientId, clientSession);
    }
}