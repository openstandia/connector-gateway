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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpConnectionListener extends Thread {

    public static class PoolThreadFactory implements ThreadFactory {
        static final AtomicInteger POOL_NUMBER = new AtomicInteger(1);
        final ThreadGroup group;
        final AtomicInteger threadNumber = new AtomicInteger(1);
        final String namePrefix;

        public PoolThreadFactory() {
            this.group = Thread.currentThread().getThreadGroup();
            this.namePrefix = "pool-" + POOL_NUMBER.getAndIncrement() + "-thread-";
        }

        public Thread newThread(Runnable r) {
            Thread t = new Thread(this.group, r, this.namePrefix + this.threadNumber.getAndIncrement(), 0L);
            if (t.isDaemon()) {
                t.setDaemon(false);
            }

            if (t.getPriority() != 5) {
                t.setPriority(5);
            }

            return t;
        }
    }

    /**
     * This is the size of our internal queue. For now I have this relatively
     * small because I want the OS to manage the connect queue coming in. That
     * way it can properly turn away excessive requests
     */
    private final static int INTERNAL_QUEUE_SIZE = 2;

    private static final Logger LOG = LoggerFactory.getLogger(TcpConnectionListener.class);

    /**
     * The server object that we are using
     */
    private final Main connectorServer;

    /**
     * The server socket. This must be bound at the time of creation.
     */
    private ServerSocketChannel socketChannel;
    private ServerSocket socket;

    /**
     * Pool of executors
     */
    private final ExecutorService threadPool;

    /**
     * Set to indicated we need to start shutting down
     */
    private boolean stopped = false;

    /**
     * Creates the listener thread
     *
     * @param server The server object
     * @param socket The socket (should already be bound)
     */
    public TcpConnectionListener(Main server, ServerSocket socket) {
        super("ConnectionListener");
        connectorServer = server;
        this.socket = socket;
        // idle time timeout
        threadPool =
                new ThreadPoolExecutor(server.getTcpMinWorkers(), server.getTcpMaxWorkers(), 30,
                        TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(INTERNAL_QUEUE_SIZE,
                        true), // fair
                        new PoolThreadFactory());
        LOG.info("Initialized instance of Connection listener with min amount of worker threads: {} ,and " +
                "max worker threads: {}", server.getTcpMinWorkers(), server.getTcpMaxWorkers());
    }

    @Override
    public void run() {
        while (!isStopped()) {
            try {
                Socket connection = socket.accept();

                while (true) {
                    try {
                        threadPool.execute(() -> {
                            if (!Relay.start(connection, connectorServer.getWsMaxBinarySize())) {
                                try {
                                    connection.close();
                                } catch (IOException e) {
                                    LOG.warn("Handled exception occurred closing tcp connection.", e);
                                }
                            }
                        });
                        // Return to wait for next incoming request
                        break;
                    } catch (RejectedExecutionException e) {
                        LOG.warn("Execution exception occurred during Connector Server connection runtime: {}", e.getLocalizedMessage(), e);
                        try {
                            Thread.sleep(100);
                        } catch (Exception e2) {
                            /* ignore */
                            LOG.warn("Handled exception occurred during Connector Server connection runtime: {}", e2.getLocalizedMessage(), e);
                        }
                    }
                }
            } catch (Throwable e) {
                // log the error unless it's because we've stopped
                if (!isStopped() || !(e instanceof SocketException)) {

                    LOG.error("Error processing request: {}", e.getLocalizedMessage(), e);
                }
                // wait a second before trying again
                if (!isStopped()) {
                    try {
                        LOG.info("Retry of connection execution.");
                        Thread.sleep(1000);
                    } catch (Exception e2) {
                        /* ignore */
                        LOG.warn("Handled exception occurred during Connector Server connection runtime: {}", e2.getLocalizedMessage(), e);
                    }
                }
            }
        }
    }

    private synchronized void markStopped() {
        stopped = true;
    }

    private synchronized boolean isStopped() {
        return stopped;
    }

    public void shutdown() throws IOException, InterruptedException {
        if (Thread.currentThread() == this) {
            throw new IllegalArgumentException("Shutdown may not be called from this thread");
        }
        if (!isStopped()) {
            // set the stopped flag so we no its a normal
            // shutdown and don't log the SocketException
            markStopped();
            // close the socket - this causes accept to throw an exception

            LOG.info("About do close the Connector Server connection socket.");
            socket.close();
            // wait for the main listener thread to die so we don't
            // get any new requests
            join();
            // wait for all in-progress requests to finish

            LOG.info("Shutting down Connector Server connection thread pool.");
            threadPool.shutdown();

            LOG.info("Connector Server connection shutdown complete");
        }
    }
}
