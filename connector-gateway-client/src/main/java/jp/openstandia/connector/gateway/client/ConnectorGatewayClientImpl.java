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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.eclipse.jetty.client.*;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.api.ConnectorInfoManagerFactory;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.impl.api.ConnectorInfoManagerFactoryImpl;
import org.identityconnectors.framework.server.ConnectorServer;

import java.net.URI;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static java.util.stream.Collectors.toList;

public class ConnectorGatewayClientImpl extends ConnectorServer {

    private final String gatewayConfigurationEndpoint;
    private final String gatewayApiKey;
    private final String gatewayClientId;
    private final String gatewayProxy;
    private final int maxBinarySize;

    private CountDownLatch stopLatch;
    private Long startDate = null;
    private static final Log LOG = Log.getLog(ConnectorGatewayClientImpl.class);
    private final ScheduledExecutorService reconnectExecutorService = Executors.newScheduledThreadPool(1);
    private final Map<String, Session> connections = new ConcurrentHashMap<>();

    public ConnectorGatewayClientImpl(String gatewayConfigurationEndpoint, String gatewayApiKey, String gatewayClientId, String gatewayProxy, int maxBinarySize) {
        this.gatewayConfigurationEndpoint = gatewayConfigurationEndpoint;
        this.gatewayApiKey = gatewayApiKey;
        this.gatewayClientId = gatewayClientId;
        this.gatewayProxy = gatewayProxy;
        this.maxBinarySize = maxBinarySize;
    }

    public int getMaxBinarySize() {
        return maxBinarySize;
    }

    @Override
    public Long getStartTime() {
        return startDate;
    }

    @Override
    public boolean isStarted() {
        return startDate != null;
    }

    @Override
    public void start() {
        LOG.info("Starting Connector Server.");

        if (isStarted()) {
            throw new IllegalStateException("Server is already running.");
        }
        if (getPort() == 0) {
            throw new IllegalStateException("Port must be set prior to starting server.");
        }
        if (getKeyHash() == null) {
            throw new IllegalStateException("Key hash must be set prior to starting server.");
        }
        // make sure we are configured properly
        final ConnectorInfoManagerFactoryImpl factory =
                (ConnectorInfoManagerFactoryImpl) ConnectorInfoManagerFactory.getInstance();
        factory.getLocalManager(getBundleURLs(), getBundleParentClassLoader());

        // start connect scheduler
        reconnectExecutorService.scheduleAtFixedRate(() -> {
            resolveEndpoint()
                    .stream()
                    .filter(endpoint -> {
                        Session s = connections.get(endpoint);
                        return s == null || !s.isOpen();
                    })
                    .map(endpoint -> {
                        return this.connect(endpoint)
                                .thenAccept(s -> {
                                    if (s.isOpen()) {
                                        LOG.info("Connected to {0}", endpoint);
                                        connections.put(endpoint, s);
                                    }
                                })
                                .exceptionally(e -> {
                                    LOG.warn("Cannot connect to {0}. message={1}", endpoint, e.getMessage());
                                    return null;
                                });
                    })
                    .map(CompletableFuture::join)
                    .collect(toList());

            // clean closed session
            connections.entrySet().removeIf(entry -> !entry.getValue().isOpen());

        }, 0, 10, TimeUnit.SECONDS);

        stopLatch = new CountDownLatch(1);
        startDate = System.currentTimeMillis();

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(startDate);
        LOG.info("Connector Server started at {0}", calendar.getTime());
    }

    public List<String> resolveEndpoint() {
        HttpClient httpClient = new HttpClient();
        setupProxy(httpClient);

        try {
            httpClient.start();

            ContentResponse res = httpClient.GET(gatewayConfigurationEndpoint);
            if (res.getStatus() != 200) {
                throw new IllegalStateException("Error from the configuration endpoint. endpoint: "
                        + gatewayConfigurationEndpoint + ", status: " + res.getStatus());
            }
            Gson gson = new Gson();
            Map<String, List<String>> map = gson.fromJson(res.getContentAsString(), new TypeToken<Map<String, List<String>>>() {
            });
            return map.get("endpoint");
        } catch (Exception e) {
            LOG.warn(e, "Cannot resolve the configuration endpoint: {0}", gatewayConfigurationEndpoint);
            return Collections.emptyList();
        } finally {
            try {
                httpClient.stop();
            } catch (Exception ignore) {
            }
        }
    }

    public CompletableFuture<Session> connect(String endpoint) {
        HttpClient httpClient = new HttpClient();
        setupProxy(httpClient);

        WebSocketClient webSocketClient = new WebSocketClient(httpClient);
        webSocketClient.setMaxBinaryMessageSize(getMaxBinarySize());

        try {
            webSocketClient.start();

            // The client-side WebSocket EndPoint that
            // receives WebSocket messages from the server.
            WebSocketClientListener clientEndPoint = new WebSocketClientListener(this, endpoint);

            // The server URI to connect to.
            URI serverURI = URI.create(endpoint + "?token=" + gatewayApiKey + "&client_id=" + gatewayClientId);

            // Connect the client EndPoint to the server.
            CompletableFuture<Session> clientSessionPromise = webSocketClient.connect(clientEndPoint, serverURI);
            return clientSessionPromise;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void setupProxy(HttpClient httpClient) {
        if (this.gatewayProxy == null) {
            return;
        }

        URI uri = URI.create(this.gatewayProxy);

        final ProxyConfiguration.Proxy proxy;

        switch (uri.getScheme()) {
            case "http":
                proxy = new HttpProxy(uri.getHost(), uri.getPort());
                break;
            case "https":
                proxy = new HttpProxy(new Origin.Address(uri.getHost(), uri.getPort()), true);
                break;
            case "socks4":
                proxy = new Socks4Proxy(uri.getHost(), uri.getPort());
                break;
            default:
                throw new ConnectorException("Schema \"" + uri.getScheme() + "\" is not supported for connectorgateway.proxy");
        }

        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            String[] split = userInfo.split(":");

            final String user;
            final String password;
            if (split.length == 1) {
                user = split[0];
                password = "";
            } else if (split.length == 2) {
                user = split[0];
                password = split[1];
            } else {
                throw new ConnectorException("Invalid connectorgateway.proxy authentication configuration");
            }

            AuthenticationStore auth = httpClient.getAuthenticationStore();
            auth.addAuthentication(new BasicAuthentication(uri, "ProxyRealm", user, password));
        }

        httpClient.getProxyConfiguration().addProxy(proxy);
    }

    @Override
    public void stop() {
        LOG.info("About to initialize Connector server stop sequence.");
        if (isStarted()) {
            try {
//                listener.shutdown();
            } finally {
                stopLatch.countDown();
            }
            stopLatch = null;
            startDate = null;
//            listener = null;
        }
        ConnectorFacadeFactory.getManagedInstance().dispose();
    }

    @Override
    public void awaitStop() throws InterruptedException {
        stopLatch.await();
    }
}
