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

import org.eclipse.jetty.client.*;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.api.ConnectorFacadeFactory;
import org.identityconnectors.framework.api.ConnectorInfoManagerFactory;
import org.identityconnectors.framework.common.exceptions.ConnectorException;
import org.identityconnectors.framework.impl.api.ConnectorInfoManagerFactoryImpl;
import org.identityconnectors.framework.server.ConnectorServer;

import java.net.URI;
import java.util.Calendar;
import java.util.concurrent.*;

public class ConnectorGatewayClientImpl extends ConnectorServer {

    private final String gatewayURL;
    private final String gatewayProxy;
    private final int maxBinarySize;

    private CountDownLatch stopLatch;
    private Long startDate = null;
    private static final Log LOG = Log.getLog(ConnectorGatewayClientImpl.class);

    private SubmissionPublisher<Boolean> reconnectPublisher;

    public ConnectorGatewayClientImpl(String gatewayURL, String gatewayProxy, int maxBinarySize) {
        this.gatewayURL = gatewayURL;
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

        connect();

        reconnectPublisher = new SubmissionPublisher<>();

        // Start subscriber for handling reconnect
        reconnectPublisher.subscribe(new Flow.Subscriber<Boolean>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(Boolean message) {
                CompletableFuture<Session> future = connect();
                try {
                    future.get(10, TimeUnit.SECONDS);

                } catch (ExecutionException | InterruptedException | TimeoutException e) {
                    if (e instanceof ExecutionException) {
                        if (((ExecutionException) e).getCause() instanceof UpgradeException) {
                            int statusCode = ((UpgradeException) ((ExecutionException) e).getCause()).getResponseStatusCode();
                            if (statusCode == 401) {
                                LOG.error("The connector gateway server returned 401 error. Please check the authentication configuration.");
                            }
                        }
                    }
                    LOG.error(e, "Failed to connect. Waiting retry...");
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ex) {
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
        });


        stopLatch = new CountDownLatch(1);
        startDate = System.currentTimeMillis();

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(startDate);
        LOG.info("Connector Server started at {0}", calendar.getTime());
    }

    public CompletableFuture<Session> connect() {
        HttpClient httpClient = new HttpClient();
        setupProxy(httpClient);

        WebSocketClient webSocketClient = new WebSocketClient(httpClient);
        webSocketClient.setMaxBinaryMessageSize(getMaxBinarySize());

        try {
            webSocketClient.start();

            // The client-side WebSocket EndPoint that
            // receives WebSocket messages from the server.
            WebSocketClientListener clientEndPoint = new WebSocketClientListener(this);

            // The server URI to connect to.
            URI serverURI = URI.create(gatewayURL);

            // Connect the client EndPoint to the server.
            CompletableFuture<Session> clientSessionPromise = webSocketClient.connect(clientEndPoint, serverURI);
            return clientSessionPromise;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
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

    public void reconnect() {
        reconnectPublisher.submit(true);
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
