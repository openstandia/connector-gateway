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

import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.common.security.SecurityUtil;
import org.identityconnectors.framework.common.serializer.BinaryObjectDeserializer;
import org.identityconnectors.framework.common.serializer.ObjectSerializerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class Relay {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServerListener.class);
    private static final Object LOCK = new Object();
    private static final Map<String, Set<WebSocketServerListener>> clientSets = new ConcurrentHashMap<>();
    private static final Map<Integer, Channel> channels = new ConcurrentHashMap<>();
    private static final AtomicInteger idGenerator = new AtomicInteger(0);

    private static final byte OP_START = 1;
    private static final byte OP_BODY = 2;
    private static final byte OP_END = 3;

    public static void attach(WebSocketServerListener webSocketServerListener) {
        synchronized (LOCK) {
            Set<WebSocketServerListener> clientSessions = clientSets.computeIfAbsent(webSocketServerListener.clientId,
                    k -> new HashSet<>());
            clientSessions.add(webSocketServerListener);
        }
    }

    public static void detach(String clientId, WebSocketServerListener webSocketServerListener) {
        synchronized (LOCK) {
            Set<WebSocketServerListener> clients = clientSets.get(clientId);
            if (clients != null) {
                clients.remove(webSocketServerListener);
            }

            // close all TCP sockets for this websocket server listener since the client (IDM) is waiting the response
            channels.entrySet().stream()
                    .filter(entry -> entry.getValue().client.clientSession.equals(webSocketServerListener.clientSession))
                    .forEach(entry -> {
                        close(entry.getValue().tcpSocket);
                    });

            // clean
            channels.entrySet().removeIf(entry -> !entry.getValue().client.clientSession.equals(webSocketServerListener.clientSession));
            webSocketServerListener.clientSession = null;
        }
    }

    public static Channel findChannel(int id) {
        return channels.get(id);
    }

    public static int generateId() {
        return idGenerator.getAndIncrement();
    }

    public static boolean start(Socket tcpSocket, int maxBinarySize) {
        // Handle clientId for routing to the client
        final String clientId;
        final InputStream in;
        try {
            // TODO Handle big size key
            in = new BufferedInputStream(tcpSocket.getInputStream(), 8192);
            in.mark(0);

            ObjectSerializerFactory factory = ObjectSerializerFactory.getInstance();
            BinaryObjectDeserializer decoder = factory.newBinaryDeserializer(in);
            Locale locale = (Locale) decoder.readObject();
            GuardedString key = (GuardedString) decoder.readObject();
            clientId = toClientId(key);

            // Reset to the top position for reading later
            in.reset();
        } catch (IOException e) {
            LOG.error("Failed to handle TCP connect request. socket={}", tcpSocket, e);
            close(tcpSocket);
            return false;
        }

        // Find the routing by clientId
        Set<WebSocketServerListener> currentClientSessions = clientSets.get(clientId);
        if (currentClientSessions == null) {
            LOG.warn("Not found client session(s). socket={}, clientId: {}", tcpSocket, clientId);
            close(tcpSocket);
            return false;
        }

        List<CompletableFuture<Channel>> futures = currentClientSessions.stream()
                .filter(client -> client.clientSession != null && client.clientSession.isOpen())
                .map(client -> {
                    Session session = client.clientSession;

                    int id = generateId();
                    CompletableFuture<Channel> start = new CompletableFuture<>();

                    Channel channel = new Channel(id, client, start, tcpSocket);

                    channels.put(id, channel);

                    ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES)
                            .put(OP_START)
                            .putInt(id)
                            .flip();

                    RemoteEndpoint remote = session.getRemote();
                    remote.sendPing(buffer, WriteCallback.NOOP);

                    return start;
                })
                .collect(Collectors.toList());

        final CompletableFuture<Channel>[] completableFutures = futures.toArray(new CompletableFuture[0]);
        final CompletableFuture<Object> objectCompletableFuture = CompletableFuture.anyOf(completableFutures);

        // TODO configurable timeout
        final Channel channel;
        try {
            channel = (Channel) objectCompletableFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOG.error("Failed to start channel. socket={}, clientId={}", tcpSocket, clientId, e);
            close(tcpSocket);
            return false;
        }

        final Session session = channel.client.clientSession;
        final int id = channel.id;

        try {
            RemoteEndpoint wsRemote = session.getRemote();

            byte[] bytes = new byte[maxBinarySize - Byte.BYTES - Integer.BYTES];
            int count = 0;
            while (true) {
                count = in.read(bytes);
                if (count > 0) {
                    ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + count)
                            .put(OP_BODY)
                            .putInt(id)
                            .put(bytes, 0, count)
                            .flip();

                    wsRemote.sendBytes(buffer);
                    wsRemote.flush();

                } else if (count == -1) {
                    LOG.info("Detected TCP client is closed. socket={}, clientId={}, session={}, id={}", tcpSocket, clientId, session, id);
                    break;

                } else {
                    LOG.info("Waiting request from TCP client. socket={}, clientId={}, session={}, id={}", tcpSocket, clientId, session, id);
                }
            }
            return true;

        } catch (IOException e) {
            LOG.error("Failed to send to the gateway client. socket={}, clientId={}, session={}, id={}", tcpSocket, clientId, session, id, e);
            return false;

        } finally {
            close(tcpSocket, session, id);
        }
    }

    private static String toClientId(GuardedString guardedString) {
        AtomicReference<String> clientId = new AtomicReference<>();
        guardedString.access((c) -> {
            try {
                byte[] bytes = SecurityUtil.charsToBytes(c);
                String hash = SecurityUtil.computeBase64SHA1Hash(bytes);

                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                byte[] sha256Byte = sha256.digest(hash.getBytes(StandardCharsets.UTF_8));
                HexFormat hex = HexFormat.of().withLowerCase();
                clientId.set(hex.formatHex(sha256Byte));
                return;
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        });
        return clientId.get();
    }

    public static void handleStartResponse(ByteBuffer payload, WebSocketServerListener client) {
        byte op = payload.get();

        if (op == OP_START) {
            int id = payload.getInt();
            Channel channel = findChannel(id);
            if (channel == null) {
                LOG.error("Cannot establish channel on the session. clientId={}, session={}, id={}", client.clientId, client.clientSession, id);
                return;
            }
            channel.start();
        }
    }

    public static void handleBodyResponse(ByteBuffer buffer, WebSocketServerListener client) {
        byte op = buffer.get();
        if (op == OP_BODY) {
            int id = buffer.getInt();
            byte[] dst = new byte[buffer.remaining()];
            buffer.get(dst);

            Channel channel = Relay.findChannel(id);

            if (channel == null) {
                LOG.error("Cannot continue using this channel on the session. clientId={}, session={}, id={}", client.clientId, client.clientSession, id);
                return;
            }

            try {
                OutputStream out = channel.tcpSocket.getOutputStream();
                out.write(dst);
                out.flush();
            } catch (IOException e) {
                LOG.error("Failed to write response to the TCP client. socket={}, clientId={}, session={}, id={}", channel.tcpSocket, client.clientId, client.clientSession, id);
                close(channel.tcpSocket, client.clientSession, id);
            }
        }
    }

    private static void close(Socket tcpSocket, Session session, int id) {
        if (session.isOpen()) {
            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + Byte.BYTES)
                    .put(OP_END)
                    .putInt(id)
                    .flip();

            RemoteEndpoint remote = session.getRemote();
            remote.sendPing(buffer, WriteCallback.NOOP);
        }

        close(tcpSocket);
    }

    private static void close(Socket tcpSocket) {
        if (tcpSocket.isClosed()) {
            return;
        }
        LOG.info("Closing TCP connection. socket={}", tcpSocket);
        try {
            tcpSocket.close();
        } catch (IOException e) {
            LOG.warn("Failed to close TCP connection. socket={}", tcpSocket);
        }
    }
}
