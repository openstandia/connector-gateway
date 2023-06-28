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

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ServerSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    private CountDownLatch stopLatch;
    private Long startDate = null;

    // TCP server
    private int tcpServerPort;
    private String tcpBindAddress = null;
    private int tcpMaxConnections = 300;
    private int tcpMinWorkers = 10;
    private int tcpMaxWorkers = 100;
    private TcpConnectionListener listener;

    // WebSocket server
    private int wsServerPort;
    private String wsBindAddress = null;
    private int wsMaxConnections = 10;
    private Server server;
    private ServerConnector connector;

    public void setTcpServerPort(int tcpServerPort) {
        this.tcpServerPort = tcpServerPort;
    }

    public String getTcpBindAddress() {
        return tcpBindAddress;
    }

    public int getTcpServerPort() {
        return tcpServerPort;
    }

    public int getTcpMinWorkers() {
        return tcpMinWorkers;
    }

    public int getTcpMaxWorkers() {
        return tcpMaxWorkers;
    }

    public void setWsBindAddress(String wsBindAddress) {
        this.wsBindAddress = wsBindAddress;
    }

    public String getWsBindAddress() {
        return wsBindAddress;
    }

    public void setWsServerPort(int wsServerPort) {
        this.wsServerPort = wsServerPort;
    }

    public int getWsServerPort() {
        return wsServerPort;
    }

    public int getTcpMaxConnections() {
        return tcpMaxConnections;
    }

    public int getWsMaxConnections() {
        return wsMaxConnections;
    }

    public Long getStartTime() {
        return startDate;
    }

    public void startWsServer() throws Exception {
        LOG.info("Starting Connector Gateway WebSocket Server.");

        if (getWsServerPort() == 0) {
            throw new IllegalStateException("WebSocket Server Port must be set prior to starting server.");
        }

        final String bindAddress = getWsBindAddress();
        if (bindAddress == null) {
            server = new Server(getWsServerPort());
        } else {
            InetSocketAddress bindAddr = new InetSocketAddress(getWsBindAddress(), getWsServerPort());
            server = new Server(bindAddr);
        }

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        final String contextPath = resolveContextPath();
        final String apiKey = resolveAPIKey();

        JettyWebSocketServletContainerInitializer.configure(context, (servletContext, container) -> {
            container.setMaxBinaryMessageSize(65535);
            container.addMapping(contextPath, (req, resp) -> {
                // authentication by API key if configured
                // TODO support more authentication method
                if (apiKey != null) {
                    List<String> token = req.getParameterMap().get("token");
                    if (token == null || token.isEmpty() || !token.get(0).equals(apiKey)) {
                        LOG.info("Invalid access token. remoteAddress: {}", req.getHttpServletRequest().getRemoteAddr());
                        try {
                            resp.sendError(HttpStatus.UNAUTHORIZED_401, "Invalid API key");
                        } catch (IOException e) {
                            LOG.warn("Failed to send error", e);
                        }
                        return null;
                    }
                }

                return new WebSocketServerListener();
            });
        });

        server.start();
    }

    protected String resolveContextPath() {
        String contextPath = System.getenv("CONTEXT_PATH");
        if (contextPath == null || contextPath.isEmpty()) {
            return "/";
        }
        return contextPath;
    }

    protected String resolveAPIKey() {
        String file = System.getenv("API_KEY_FILE");
        if (file == null) {
            LOG.warn("The server will boot without API key because of no API key file. DO NOT use this configuration in production.");
            return null;
        }

        try {
            String apiKey = Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
            return apiKey;

        } catch (IOException e) {
            throw new RuntimeException("Failed to read API key file", e);
        }
    }

    public void startTcpServer() {
        LOG.info("Starting Connector Gateway TCP Server.");

        if (isStarted()) {
            throw new IllegalStateException("Server is already running.");
        }
        if (getTcpServerPort() == 0) {
            throw new IllegalStateException("TCP Server Port must be set prior to starting server.");
        }

        final ServerSocket socket = createServerSocket();

        final TcpConnectionListener listener = new TcpConnectionListener(this, socket);
        listener.start();

        stopLatch = new CountDownLatch(1);
        startDate = System.currentTimeMillis();

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(startDate);

        LOG.info("Connector Server started at {}", calendar.getTime());
        this.listener = listener;
    }

    public void stop() {
        try {
            server.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean isStarted() {
        return false;
    }

    public void awaitStop() throws InterruptedException {
    }

    public void join() throws InterruptedException {
        System.out.println("Use Ctrl+C to stop server");
        server.join();
    }

    private ServerSocket createServerSocket() {
        try {
            ServerSocketFactory factory;

            LOG.info("Creating default (no SSL) server socket.");
            factory = ServerSocketFactory.getDefault();

            final ServerSocket rv;
            final int port = getTcpServerPort();
            final int maxConnections = getTcpMaxConnections();
            final String bindAddress = getTcpBindAddress();

            if (bindAddress == null) {
                LOG.info("Creating server socket with the following parameters, port = {}, max connections {}"
                        , String.valueOf(port), String.valueOf(maxConnections));
                rv = factory.createServerSocket(port, maxConnections);
            } else {
                LOG.info("Creating server socket with the following parameters," +
                                " port = {}, network interface address = {}, max connections {}"
                        , String.valueOf(port), String.valueOf(maxConnections), bindAddress);
                rv = factory.createServerSocket(port, maxConnections, InetAddress.getByName(bindAddress));
            }
            return rv;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        Main server = new Main();
        server.setTcpServerPort(8759); // TODO configurable
        server.setWsServerPort(8080);  // TODO configurable
        server.startTcpServer();
        server.startWsServer();
        server.join();
    }
}