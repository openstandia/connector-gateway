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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.concurrent.*;

public class Channel {

    private static final Logger LOG = LoggerFactory.getLogger(Channel.class);

    final int id;
    final WebSocketServerListener client;
    final CompletableFuture<Channel> start;
    final Socket tcpSocket;
    final ScheduledExecutorService executor;
    CompletableFuture<Void> transferFuture;
    ScheduledFuture<?> scheduledFuture;
    final long timeoutInSeconds = 60; // TODO Configurable

    public Channel(int id, WebSocketServerListener client, CompletableFuture<Channel> start, Socket tcpSocket) {
        this.id = id;
        this.client = client;
        this.start = start;
        this.tcpSocket = tcpSocket;
        this.executor = Executors.newScheduledThreadPool(1);
    }

    public void start() {
        this.start.complete(this);
    }

    public CompletableFuture<Void> transfer(Runnable runnable) {
        this.transferFuture = CompletableFuture.runAsync(runnable);
        // Start initial transfer timeout thread
        scheduleTransferTimeout();
        return this.transferFuture;
    }

    public void scheduleTransferTimeout() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        scheduledFuture = executor.schedule(() -> {
            transferFuture.completeExceptionally(
                    new TimeoutException(String.format("Data Transfer Timeout after %d seconds. clientId=%s, session=%s, id=%d",
                            timeoutInSeconds, client.clientId, client.clientSession, id)));
        }, timeoutInSeconds, TimeUnit.SECONDS);
    }

    public void close() {
        this.executor.shutdown();
    }
}
