/*
 * Copyright 2015 - 2022 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.InetSocketAddress;

@Singleton
@ChannelHandler.Sharable
public class RemoteAddressHandler extends ChannelInboundHandlerAdapter {

    private final boolean enabled;

    @Inject
    public RemoteAddressHandler(Config config) {
        enabled = config.getBoolean(Keys.PROCESSING_REMOTE_ADDRESS_ENABLE);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {

        if (enabled) {
            InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            String hostAddress = remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : null;

            if (msg instanceof Position) {
                Position position = (Position) msg;
                position.set(Position.KEY_IP, hostAddress);
            }
        }

        ctx.fireChannelRead(msg);
    }

}
