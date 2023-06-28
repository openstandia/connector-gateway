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
package jp.openstandia.connector.gateway.client;

import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.websocket.api.*;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.server.impl.ConnectionProcessor;
import org.identityconnectors.framework.server.impl.ThreadFactoryUtil;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public class WebSocketClientListener implements WebSocketListener, WebSocketPingPongListener {

    private static final Log LOG = Log.getLog(WebSocketClientListener.class);

    /**
     * This is the size of our internal queue. For now I have this relatively
     * small because I want the OS to manage the connect queue coming in. That
     * way it can properly turn away excessive requests
     */
    private final static int INTERNAL_QUEUE_SIZE = 2;

    private static final byte OP_START = 1;
    private static final byte OP_BODY = 2;
    private static final byte OP_END = 3;

    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
    private final ConnectorGatewayClientImpl server;

    private Session session;
    private ScheduledFuture<?> keepAlive;
    private ThreadPoolExecutor threadPool;

    Map<Integer, PipedOutputStream> channels = new HashMap<>();

    public WebSocketClientListener(ConnectorGatewayClientImpl server) {
        this.server = server;

        threadPool =
                new ThreadPoolExecutor(server.getMinWorkers(), server.getMaxWorkers(), 30,
                        TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(INTERNAL_QUEUE_SIZE,
                        true),
                        ThreadFactoryUtil.newThreadFactory());
    }

    @Override
    public void onWebSocketConnect(Session session) {
        this.session = session;

        session.setMaxTextMessageSize(16 * 1024);

        // Start keepAlive thread
        keepAlive = executorService.scheduleAtFixedRate(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(8).putLong(NanoTime.now()).flip();
            session.getRemote().sendPing(buffer, new WriteCallback() {
                @Override
                public void writeSuccess() {
                    LOG.ok("Keep-alive: sending is OK. session={0}", session);
                }

                @Override
                public void writeFailed(Throwable x) {
                    LOG.error("Keep-alive: sending is NG. session={0}", session);
                }
            });
        }, 0, 10, TimeUnit.SECONDS);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        keepAlive.cancel(true);
        channels.clear();
        executorService.shutdown();
    }

    @Override
    public void onWebSocketError(Throwable cause) {
        server.reconnect();
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int length) {
        // In
        ByteBuffer channelMessage = ByteBuffer.wrap(payload, offset, length);
        byte op = channelMessage.get();

        if (op != OP_BODY) {
            LOG.warn("Detected invalid operation code for binary message from the Gateway Server. op={0}, session={1}", op, session);
            return;
        }

        int id = channelMessage.getInt();
        byte[] dst = new byte[channelMessage.remaining()];
        channelMessage.get(dst);

        // Continue on the running thread
        if (channels.containsKey(id)) {
            LOG.ok("Continue...");
            PipedOutputStream pout = channels.get(id);
            try {
                pout.write(dst);
                pout.flush();
            } catch (IOException e) {
                LOG.warn(e, "Failed to write");
            }
            return;
        }

        PipedOutputStream pout = new PipedOutputStream();
        PipedInputStream pis = new PipedInputStream();
        try {
            pis.connect(pout);
            channels.put(id, pout);

            ConnectionProcessor processor = new ConnectionProcessor(server, new Socket() {
                @Override
                public boolean isConnected() {
                    return true;
                }

                @Override
                public void shutdownOutput() throws IOException {
                }

                @Override
                public void shutdownInput() throws IOException {
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    return pis;
                }

                @Override
                public OutputStream getOutputStream() throws IOException {
                    return new OutputStream() {
                        @Override
                        public void write(int b) throws IOException {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public void write(byte[] b) throws IOException {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public void write(byte[] b, int off, int len) throws IOException {
                            ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + (len - off))
                                    .put(OP_BODY)
                                    .putInt(id)
                                    .put(b, off, len)
                                    .flip();

                            RemoteEndpoint remote = session.getRemote();
                            remote.sendBytes(buffer);
                            remote.flush();
                        }
                    };
                }

                @Override
                public synchronized void close() throws IOException {
                    closeChannel(id);
                }
            });

            threadPool.submit(processor);
            pout.write(dst);
            pout.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void closeChannel(int id) {
        PipedOutputStream pout = channels.remove(id);
        if (pout != null) {
            try {
                pout.close();
            } catch (IOException e) {
            }
        }
    }

    @Override
    public void onWebSocketPong(ByteBuffer payload) {
        // The remote peer echoed back the local nanoTime.
        long start = payload.getLong();

        // Calculate the round-trip time.
        long roundTrip = NanoTime.millisSince(start);

        LOG.ok("On Pong: " + roundTrip);
    }

    @Override
    public void onWebSocketPing(ByteBuffer payload) {
        LOG.ok("on Ping");
        try {
            byte op = payload.get();
            int id = payload.getInt();

            if (op == OP_START) {
                LOG.info("Starting the channel. session={0}, id={0}", session, id);

                // Verify the channel
                if (channels.containsKey(id)) {
                    LOG.warn("Detected existing channel id with start operation. id={0}", id);
                    return;
                }
                ByteBuffer buffer = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES)
                        .put(op)
                        .putInt(id)
                        .flip();
                session.getRemote().sendPong(buffer);

                LOG.info("Finished replay to the Gateway Server for the start operation. session={0}, id={0}", session, id);

            } else if (op == OP_END) {
                LOG.info("Closing the channel. session={0}, id={0}", session, id);

                // Close the channel if exists
                closeChannel(id);
            }
        } catch (IOException e) {
            LOG.error("IO error", e);
        }
    }
}