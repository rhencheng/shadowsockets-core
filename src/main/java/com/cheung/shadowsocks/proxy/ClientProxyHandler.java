package com.cheung.shadowsocks.proxy;

import com.cheung.shadowsocks.common.ClientProxy;
import com.cheung.shadowsocks.encryption.CryptUtil;
import com.cheung.shadowsocks.encryption.ICrypt;
import com.cheung.shadowsocks.model.SSModel;
import com.cheung.shadowsocks.utils.BootContext;
import com.cheung.shadowsocks.utils.BytesUtils;
import com.google.common.collect.Lists;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 接受客户端代理发送来的消息
 */
@Deprecated
public class ClientProxyHandler extends ChannelInboundHandlerAdapter {

    private static Logger logger = LoggerFactory.getLogger(ClientProxyHandler.class);
    private final ICrypt _crypt;
    private volatile Channel remoteChannel = null;
    private List<byte[]> clientCache = Lists.newCopyOnWriteArrayList();
    private ChannelHandlerContext channelHandlerContext;


    ClientProxyHandler(String host, int port, ByteBuf clientCache, ChannelHandlerContext channelHandlerContext, ICrypt _crypt) {

        // 防止 channel 断开 无法 释放 byteBuf
        byte[] data = BytesUtils.utils.byteBuf2Bytes(clientCache);
        this.clientCache.add(data);
        clientCache.release();

        this._crypt = _crypt;
        this.channelHandlerContext = channelHandlerContext;
        AttributeKey<SSModel> ss_model = AttributeKey.valueOf("ss.model");
        Attribute<SSModel> ssModelAttribute = channelHandlerContext.channel().attr(ss_model);
        SSModel model = new SSModel();
        model.setChannelHandlerContext(this.channelHandlerContext);
        model.setCrypt(this._crypt);
        model.setData(this.clientCache);
        ssModelAttribute.setIfAbsent(model);
        init(host, port);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
    }

    private synchronized void init(final String host, final int port) {

        try {
            ClientProxy channelPool = BootContext.getBeanFactory().getBean(ClientProxy.class);
            ChannelFuture future = channelPool.connect(host, port);
            // 保存客户端连接
            future.addListener(arg0 -> {
                if (future.isSuccess()) {
                    remoteChannel = future.channel();
                    AttributeKey<ChannelHandlerContext> serverChannel = AttributeKey.valueOf("server.channel");
                    Attribute<ChannelHandlerContext> channelAttribute = future.channel().attr(serverChannel);
                    channelAttribute.set(channelHandlerContext);
                } else {
                    future.cancel(true);
                }
            });
        } catch (Exception e) {
            logger.error("connect intenet error", e);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {

        ByteBuf buff = (ByteBuf) msg;
        if (buff.readableBytes() <= 0) {
            return;
        }

        byte[] decrypt = CryptUtil.decrypt(_crypt, buff);

        if (remoteChannel != null && remoteChannel.isActive()) {
            remoteChannel.writeAndFlush(Unpooled.copiedBuffer(decrypt));
            buff.release();
        } else {
            this.clientCache.add(decrypt);
        }

    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.info("ClientProxyHandler channelInactive close client address={}", ctx.channel().remoteAddress());
        ctx.close();
        if (remoteChannel != null) {
            remoteChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("ClientProxyHandler error client address=" + ctx.channel().remoteAddress(), cause);
        ctx.close();
        if (remoteChannel != null) {
            remoteChannel.close();
        }
    }
}
