package com.zwk.su_netty.netty_server.proxy;

import com.zwk.su_netty.log.LogConstant;
import com.zwk.su_netty.utils.UriUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Map;

/**
 * @author zhengweikang
 * @date 2024/8/24 23:22
 */
@Slf4j
public class HttpsProxyServerInitialzer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast(new HttpServerCodec());
        ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
        ch.pipeline().addLast(new ChunkedWriteHandler());
        ch.pipeline().addLast(new ForwardProxyServerHandler());

    }


    public static class ForwardProxyServerHandler extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            LogConstant.accessLogger.info("access msg:{}\n", msg);

            log.info("客户端发来信息 access msg:{}\n", msg);

            if (ctx.channel().attr(targetChannelAttributeKey).get() != null) {
                Channel outboundChannel = ctx.channel().attr(targetChannelAttributeKey).get();
                outboundChannel.writeAndFlush(msg);
            } else if (msg instanceof FullHttpRequest) {
                FullHttpRequest request = (FullHttpRequest) msg;
                if ("CONNECT".equalsIgnoreCase(request.method().name())) {
                    connectToTargetServer(ctx.channel(), request, true);
                } else {
                    connectToTargetServer(ctx.channel(), request, false);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("客户端与代理 连接发生异常", cause);
            ctx.close();
        }
    }

    public static final io.netty.util.AttributeKey<Channel> targetChannelAttributeKey = io.netty.util.AttributeKey.newInstance("targetChannel");


    @SneakyThrows
    public static void connectToTargetServer(Channel client2ProxyChannel, FullHttpRequest request, boolean ishttps) {
        request.retain();
        final HttpMethod method = request.method();
//        URI uri = new URI(request.uri());
        URI uri = UriUtil.createValidURI(request.uri(), ishttps);
        String targetHost = uri.getHost();
        int targetPort = uri.getPort() != -1 ? uri.getPort() : 80;
        if (cn.hutool.http.HttpUtil.isHttps(request.uri()) && uri.getPort() != -1) {
            targetPort = 443;
        }
        String targetPath = uri.getPath();

        HttpVersion httpVersion = request.protocolVersion();

        final String finalTargetPath = targetPath;

        SslContext sslCtxTmp = null;
        try {
            if (ishttps) {
                sslCtxTmp = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        final SslContext sslCtx = sslCtxTmp;

        Bootstrap b = new Bootstrap();
        b.group(client2ProxyChannel.eventLoop())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new TargetHttpHandler(client2ProxyChannel));
                    }
                });

        ChannelFuture targetFuture = b.connect(targetHost, targetPort);

        targetFuture.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.info("代理服务与远端建立连接成功");
                Channel targetChannel = future.channel();
                client2ProxyChannel.attr(targetChannelAttributeKey).set(targetChannel);
                if ("CONNECT".equalsIgnoreCase(request.method().name())) {
//                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(
//                            HttpVersion.HTTP_1_1,
//                            HttpResponseStatus.OK);
//                    serverChannel.write(response);

                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.OK);
                    // 设置响应头
                    response.headers().add(HttpHeaderNames.CONTENT_LENGTH, 0);
                    response.headers().add(HttpHeaderNames.PROXY_CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    client2ProxyChannel.writeAndFlush(response);

                    //todo 把pipeline改一下

                    Map<String, ChannelHandler> map = client2ProxyChannel.pipeline().toMap();
                    for (Map.Entry<String, ChannelHandler> entry : map.entrySet()) {
                        String key = entry.getKey();
                        ChannelHandler channelHandler = entry.getValue();
                        client2ProxyChannel.pipeline().remove(channelHandler);
                    }
                    client2ProxyChannel.pipeline().addLast(new ChannelDuplexHandler() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            targetChannel.writeAndFlush(msg);
                            //release
                        }
                    });

                } else {
                    FullHttpRequest fullRequest = new DefaultFullHttpRequest(
                            httpVersion,
                            method,
                            finalTargetPath,
                            request.content().copy()
                    );
                    for (CharSequence name : request.headers().names()) {
                        for (CharSequence value : request.headers().getAll(name)) {
                            fullRequest.headers().add(name, value);
                        }
                    }
                    // 释放原始的请求体
                    ReferenceCountUtil.release(request.content());
                    targetChannel.writeAndFlush(fullRequest);
                }


            } else {
                log.error("代理和目标服务器建立连接失败 error", future.cause());
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.BAD_GATEWAY);
                client2ProxyChannel.writeAndFlush(response);
                client2ProxyChannel.close();

            }
        });
    }

    public static class TargetHttpHandler extends ChannelDuplexHandler {

        private final Channel client2ProxyChannel;


        public TargetHttpHandler(Channel serverChannel) {
            this.client2ProxyChannel = serverChannel;
        }


        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            client2ProxyChannel.write(msg);
//            ReferenceCountUtil.release(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
            client2ProxyChannel.flush();
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            ctx.write(msg, promise);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("代理与目标服务器 exceptionCaught", cause);
            ctx.close();
            client2ProxyChannel.close();
        }
    }


}
