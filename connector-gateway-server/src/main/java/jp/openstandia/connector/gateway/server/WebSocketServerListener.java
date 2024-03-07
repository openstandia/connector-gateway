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

import org.eclipse.jetty.websocket.api.*;
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
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class WebSocketServerListener implements WebSocketListener, WebSocketPingPongListener {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServerListener.class);
    private static final Object LOCK = new Object();

    private static final byte OP_START = 1;
    private static final byte OP_BODY = 2;
    private static final byte OP_END = 3;

    private static final Map<String, Set<WebSocketServerListener>> allSessions = new ConcurrentHashMap<>();
    private static final Map<Integer, Channel> channels = new ConcurrentHashMap<>();

    private final String clientId;
    private Session clientSession;

    public WebSocketServerListener(String clientId) {
        this.clientId = clientId;
        synchronized (LOCK) {
            Set<WebSocketServerListener> clientSessions = allSessions.computeIfAbsent(clientId, k -> new LinkedHashSet<>());
            clientSessions.add(this);
        }
    }

    public static int generateId() {
        while (true) {
            int id = new SecureRandom().nextInt(Integer.MAX_VALUE);
            if (!channels.containsKey(id)) {
                return id;
            }
        }
    }

    private static class Channel {
        int id;
        Session session;
        SubmissionPublisher<Message> publisher;
    }

    private static class Message {
        byte op;
        byte[] payload;
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

    public static boolean connect(Socket tcpSocket, int maxBinarySize) {
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

        Set<WebSocketServerListener> currentClientSessions = allSessions.get(clientId);
        if (currentClientSessions == null) {
            LOG.warn("Not found client session(s). socket={}, clientId: {}", tcpSocket, clientId);
            close(tcpSocket);
            return false;
        }

        List<CompletableFuture<Channel>> futures = currentClientSessions.stream()
                .filter(entry -> entry.clientSession != null && entry.clientSession.isOpen())
                .map(entry -> {
                    Session session = entry.clientSession;

                    int id = generateId();
                    CompletableFuture<Channel> start = new CompletableFuture<>();

                    SubmissionPublisher<Message> publisher = new SubmissionPublisher<>();
                    Channel channel = new Channel();
                    channel.id = id;
                    channel.session = session;
                    channel.publisher = publisher;

                    // Start subscriber for handling response
                    publisher.subscribe(new Flow.Subscriber<Message>() {
                        private Flow.Subscription subscription;

                        @Override
                        public void onSubscribe(Flow.Subscription subscription) {
                            this.subscription = subscription;
                            subscription.request(1);
                        }

                        @Override
                        public void onNext(Message message) {
                            if (message.op == OP_START) {
                                start.complete(channel);

                            } else if (message.op == OP_BODY) {
                                try {
                                    OutputStream out = tcpSocket.getOutputStream();
                                    out.write(message.payload);
                                    out.flush();
                                } catch (IOException e) {
                                    LOG.error("Failed to write response to the TCP client. socket={}, clientId={}, session={}, id={}", tcpSocket, clientId, session, id);
                                    subscription.cancel();
                                    close();
                                    return;
                                }
                            }

                            subscription.request(1);
                        }

                        @Override
                        public void onError(Throwable throwable) {
                        }

                        @Override
                        public void onComplete() {
                        }

                        public void close() {
                            ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + Byte.BYTES)
                                    .put(OP_END)
                                    .putInt(id)
                                    .flip();

                            RemoteEndpoint remote = session.getRemote();
                            remote.sendPing(buffer, WriteCallback.NOOP);

                            try {
                                tcpSocket.close();
                            } catch (IOException e) {
                                LOG.warn("Failed to close TCP connection. socket={}, clientId={}, session={}, id={}", tcpSocket, clientId, session, id);
                            }
                        }
                    });

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
        } catch (Exception e) {
            LOG.error("Failed to start channel. socket={}, clientId={}", tcpSocket, clientId, e);
            close(tcpSocket);
            return false;
        }

        Session session = channel.session;
        int id = channel.id;
        SubmissionPublisher publisher = channel.publisher;

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
            publisher.close();
            close(tcpSocket, session, id);

            return true;

        } catch (Exception e) {
            LOG.error("Failed to send to the gateway client. socket={}, clientId={}, session={}, id={}", tcpSocket, clientId, session, id, e);
            close(tcpSocket);
            return false;
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
        try {
            tcpSocket.close();
        } catch (IOException e) {
            LOG.warn("Failed to close TCP connection. socket={}", tcpSocket);
        }
    }

    @Override
    public void onWebSocketConnect(Session session) {
        LOG.info("Connected the Gateway Client. clientId={}, session={}", clientId, session);

        this.clientSession = session;
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        LOG.info("Closed the Gateway Client. clientId={}, session={}, statusCode={}, reason={}, isOpen={}",
                clientId, clientSession, statusCode, reason, clientSession.isOpen());

        synchronized (LOCK) {
            Set<WebSocketServerListener> clientSessions = allSessions.get(clientId);
            if (clientSessions != null) {
                clientSessions.remove(this);
            }
            this.clientSession = null;
        }
    }

    @Override
    public void onWebSocketPong(ByteBuffer payload) {
        byte op = payload.get();

        if (op == OP_START) {
            int id = payload.getInt();
            Channel channel = channels.get(id);
            if (channel == null) {
                LOG.error("Cannot establish channel on the session. clientId={}, session={}, id={}", clientId, clientSession, id);
                return;
            }
            Message message = new Message();
            message.op = op;
            channel.publisher.submit(message);

            return;
        }
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len) {
        LOG.debug("onBinary");

        ByteBuffer buffer = ByteBuffer.wrap(payload, offset, len);
        byte op = buffer.get();
        if (op == OP_BODY) {
            int id = buffer.getInt();
            byte[] dst = new byte[buffer.remaining()];
            buffer.get(dst);

            Channel channel = channels.get(id);

            if (channel != null) {
                Message message = new Message();
                message.op = op;
                message.payload = dst;

                channel.publisher.submit(message);
            }
        }
    }

    @Override
    public void onWebSocketPing(ByteBuffer payload) {
        LOG.debug("onPing. clientId={}, session={}", clientId, clientSession);
    }
}