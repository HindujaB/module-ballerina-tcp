/*
 * Copyright (c) 2019 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.stdlib.tcp.endpoint;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.Future;
import io.ballerina.runtime.api.Runtime;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import org.ballerinalang.stdlib.tcp.ChannelRegisterCallback;
import org.ballerinalang.stdlib.tcp.SelectorManager;
import org.ballerinalang.stdlib.tcp.SocketConstants;
import org.ballerinalang.stdlib.tcp.SocketService;
import org.ballerinalang.stdlib.tcp.SocketUtils;
import org.ballerinalang.stdlib.tcp.exceptions.SelectorInitializeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.concurrent.RejectedExecutionException;

import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static org.ballerinalang.stdlib.tcp.SocketConstants.READ_TIMEOUT;

/**
 * Native function implementations of the TCP Listener.
 *
 * @since 1.1.0
 */
public class ServerActions {
    private static final Logger log = LoggerFactory.getLogger(ServerActions.class);

    public static Object initServer(BObject listener, long port, BMap<BString, Object> config) {
        try {
            ServerSocketChannel serverSocket = ServerSocketChannel.open();
            serverSocket.configureBlocking(false);
            serverSocket.socket().setReuseAddress(true);
            listener.addNativeData(SocketConstants.SERVER_SOCKET_KEY, serverSocket);
            listener.addNativeData(SocketConstants.LISTENER_CONFIG, config);
            listener.addNativeData(SocketConstants.CONFIG_FIELD_PORT, (int) port);
            final long timeout = config.getIntValue(StringUtils.fromString(READ_TIMEOUT));
            listener.addNativeData(READ_TIMEOUT, timeout);
        } catch (SocketException e) {
            return SocketUtils.createSocketError("unable to bind the tcp port");
        } catch (IOException e) {
            log.error("Unable to initiate the tcp listener", e);
            return SocketUtils.createSocketError("unable to initiate the tcp listener");
        }
        return null;
    }

    public static Object register(Environment env, BObject listener, BObject service) {
        final SocketService socketService =
                getSocketService(listener, env.getRuntime(), service);
        listener.addNativeData(SocketConstants.SOCKET_SERVICE, socketService);
        return null;
    }

    private static SocketService getSocketService(BObject listener, Runtime runtime, BObject service) {
        ServerSocketChannel serverSocket =
                (ServerSocketChannel) listener.getNativeData(SocketConstants.SERVER_SOCKET_KEY);
        long timeout = (long) listener.getNativeData(READ_TIMEOUT);
        return new SocketService(serverSocket, runtime, service, timeout);
    }

    public static Object start(Environment env, BObject listener) {
        final Future balFuture = env.markAsync();
        try {
            ServerSocketChannel channel =
                    (ServerSocketChannel) listener.getNativeData(SocketConstants.SERVER_SOCKET_KEY);
            int port = (int) listener.getNativeData(SocketConstants.CONFIG_FIELD_PORT);
            BMap<BString, Object> config =
                    (BMap<BString, Object>) listener.getNativeData(SocketConstants.LISTENER_CONFIG);
            String networkInterface = (String) config.getNativeData(SocketConstants.CONFIG_FIELD_INTERFACE);
            if (networkInterface == null) {
                channel.bind(new InetSocketAddress(port));
            } else {
                channel.bind(new InetSocketAddress(networkInterface, port));
            }
            // Start selector
            final SelectorManager selectorManager = SelectorManager.getInstance();
            selectorManager.start();
            SocketService socketService = (SocketService) listener.getNativeData(SocketConstants.SOCKET_SERVICE);
            ChannelRegisterCallback registerCallback = new ChannelRegisterCallback(socketService, balFuture, OP_ACCEPT);
            selectorManager.registerChannel(registerCallback);
            String socketListenerStarted = "[ballerina/tcp] started tcp listener ";
            PrintStream console = System.out;
            console.println(socketListenerStarted + channel.socket().getLocalPort());
        } catch (SelectorInitializeException e) {
            log.error(e.getMessage(), e);
            balFuture.complete(SocketUtils.createSocketError("unable to initialize the selector"));
        } catch (CancelledKeyException e) {
            balFuture.complete(SocketUtils.createSocketError("server tcp registration is failed"));
        } catch (AlreadyBoundException e) {
            balFuture.complete(SocketUtils.createSocketError("server tcp service is already bound to a port"));
        } catch (UnsupportedAddressTypeException e) {
            log.error("Address not supported", e);
            balFuture.complete(SocketUtils.createSocketError("provided address is not supported"));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            balFuture.complete(SocketUtils.createSocketError("unable to start the tcp service: " + e.getMessage()));
        } catch (RejectedExecutionException e) {
            log.error(e.getMessage(), e);
            balFuture.complete(SocketUtils.createSocketError("unable to start the tcp listener."));
        }
        return null;
    }

    public static Object stop(BObject listener, boolean graceful) {
        try {
            ServerSocketChannel channel =
                    (ServerSocketChannel) listener.getNativeData(SocketConstants.SERVER_SOCKET_KEY);
            final SelectorManager selectorManager = SelectorManager.getInstance();
            selectorManager.unRegisterChannel(channel);
            channel.close();
            selectorManager.stop(graceful);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return SocketUtils.createSocketError("unable to stop the tcp listener: " + e.getMessage());
        }
        return null;
    }
}