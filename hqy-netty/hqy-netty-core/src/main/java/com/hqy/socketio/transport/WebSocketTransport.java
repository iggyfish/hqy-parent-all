/**
 * Copyright (c) 2012-2019 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hqy.socketio.transport;

import com.hqy.socketio.Configuration;
import com.hqy.socketio.SocketIOChannelInitializer;
import com.hqy.socketio.Transport;
import com.hqy.socketio.handler.AuthorizeHandler;
import com.hqy.socketio.handler.ClientHead;
import com.hqy.socketio.handler.ClientsBox;
import com.hqy.socketio.handler.ClientsBoxEx;
import com.hqy.socketio.messages.PacketsMessage;
import com.hqy.socketio.namespace.Namespace;
import com.hqy.socketio.protocol.Packet;
import com.hqy.socketio.protocol.PacketType;
import com.hqy.socketio.scheduler.CancelableScheduler;
import com.hqy.socketio.scheduler.SchedulerKey;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.*;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Sharable
public class WebSocketTransport extends ChannelInboundHandlerAdapter {

    public static final String NAME = "websocket";

    private static final Logger log = LoggerFactory.getLogger(WebSocketTransport.class);

    private final AuthorizeHandler authorizeHandler;
    private final CancelableScheduler scheduler;
    private final Configuration configuration;
    private final ClientsBox clientsBox;

    private final boolean isSsl;

    public WebSocketTransport(boolean isSsl,
            AuthorizeHandler authorizeHandler, Configuration configuration,
            CancelableScheduler scheduler, ClientsBox clientsBox) {
        this.isSsl = isSsl;
        this.authorizeHandler = authorizeHandler;
        this.configuration = configuration;
        this.scheduler = scheduler;
        this.clientsBox = clientsBox;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof CloseWebSocketFrame) {
          ctx.channel().writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE);
        } else if (msg instanceof BinaryWebSocketFrame
                    || msg instanceof TextWebSocketFrame) {
            ByteBufHolder frame = (ByteBufHolder) msg;
            ClientHead client = clientsBox.get(ctx.channel());
            if (client == null) {
                log.debug("Client with was already disconnected. Channel closed!");
                ctx.channel().close();
                frame.release();
                return;
            }

            ctx.pipeline().fireChannelRead(new PacketsMessage(client, frame.content(), Transport.WEBSOCKET));
            frame.release();
        } else if (msg instanceof FullHttpRequest) {
            FullHttpRequest req = (FullHttpRequest) msg;
            QueryStringDecoder queryDecoder = new QueryStringDecoder(req.uri());
            String path = queryDecoder.path();
            List<String> transport = queryDecoder.parameters().get("transport");
            List<String> sid = queryDecoder.parameters().get("sid");

            if (transport != null && NAME.equals(transport.get(0))) {
                try {
                    if (!configuration.getTransports().contains(Transport.WEBSOCKET)) {
                        log.debug("{} transport not supported by configuration.", Transport.WEBSOCKET);
                        ctx.channel().close();
                        return;
                    }
                    if (sid != null && sid.get(0) != null) {
                        final UUID sessionId = UUID.fromString(sid.get(0));
                        handshake(ctx, sessionId, path, req);
                    } else {
                        ClientHead client = ctx.channel().attr(ClientHead.CLIENT).get();
                        // first connection
                        handshake(ctx, client.getSessionId(), path, req);
                    }
                } finally {
                    req.release();
                }
            } else {
                ctx.fireChannelRead(msg);
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ClientHead client = clientsBox.get(ctx.channel());
        if (client != null && client.isTransportChannel(ctx.channel(), Transport.WEBSOCKET)) {
            ctx.flush();
        } else {
            super.channelReadComplete(ctx);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        final Channel channel = ctx.channel();
        ClientHead client = clientsBox.get(channel);
        Packet packet = new Packet(PacketType.MESSAGE);
        packet.setSubType(PacketType.DISCONNECT);
        if (client != null && client.isTransportChannel(ctx.channel(), Transport.WEBSOCKET)) {
            log.debug("channel inactive {}", client.getSessionId());
            client.onChannelDisconnect();
        }
        super.channelInactive(ctx);
        if (client != null) {
            client.send(packet);
        }
        channel.close();
        ctx.close();
    }

    private void handshake(ChannelHandlerContext ctx, final UUID sessionId, String path, FullHttpRequest req) {
        final Channel channel = ctx.channel();

        WebSocketServerHandshakerFactory factory =
                new WebSocketServerHandshakerFactory(getWebSocketLocation(req), null, true, configuration.getMaxFramePayloadLength());
        WebSocketServerHandshaker handshaker = factory.newHandshaker(req);
        if (handshaker != null) {
            ChannelFuture f = handshaker.handshake(channel, req);
            f.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        log.error("Can't handshake " + sessionId, future.cause());
                        return;
                    }

                    channel.pipeline().addBefore(SocketIOChannelInitializer.WEB_SOCKET_TRANSPORT, SocketIOChannelInitializer.WEB_SOCKET_AGGREGATOR,
                            new WebSocketFrameAggregator(configuration.getMaxFramePayloadLength()));
                    connectClient(channel, sessionId);
                }
            });
        } else {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        }
    }

    private void connectClient(final Channel channel, final UUID sessionId) {
        ClientHead client = clientsBox.get(sessionId);
        if (client == null) {
            log.warn("Unauthorized client with sessionId: {} with ip: {}. Channel closed!",
                        sessionId, channel.remoteAddress());
            channel.close();
            return;
        }

        client.bindChannel(channel, Transport.WEBSOCKET);

        Namespace namespace = authorizeHandler.connect(client, false);
        if (client.getHandshakeData() != null) {
            log.info("@@@ 通道upgrade->连接协议：已切换websocket协议, bizId:{},sessionId:{}",client.getHandshakeData().getBizId(), sessionId);
        }

        //polling请求
        if (client.getCurrentTransport() == Transport.POLLING) {
            SchedulerKey key = new SchedulerKey(SchedulerKey.Type.UPGRADE_TIMEOUT, sessionId);
            scheduler.schedule(key, () -> {
                ClientHead clientHead = clientsBox.get(sessionId);
                if (clientHead != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("client did not complete upgrade - closing transport");
                    }
                    clientHead.onChannelDisconnect();
                    clientsBox.removeClient(sessionId);
                }
            }, configuration.getUpgradeTimeout(), TimeUnit.MILLISECONDS);
        } else if (client.getCurrentTransport() == Transport.WEBSOCKET) {
            //兼容直连ws用户的操作。
            client.upgrade(namespace);
        }

        log.debug("client {} handshake completed", sessionId);
    }

    private String getWebSocketLocation(HttpRequest req) {
        String protocol = "ws://";
        if (isSsl) {
            protocol = "wss://";
        }
        return protocol + req.headers().get(HttpHeaderNames.HOST) + req.uri();
    }

}
