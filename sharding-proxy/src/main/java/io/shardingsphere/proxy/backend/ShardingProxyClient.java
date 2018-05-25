/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
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
 * </p>
 */

package io.shardingsphere.proxy.backend;

import com.google.common.collect.Maps;
import com.zaxxer.hikari.HikariConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollChannelOption;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.ChannelPoolMap;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.shardingsphere.proxy.backend.netty.ClientHandlerInitializer;
import io.shardingsphere.proxy.config.RuleRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

/**
 * Sharding-Proxy Client.
 *
 * @author wangkai
 */
@Slf4j
public final class ShardingProxyClient {
    private static final ShardingProxyClient INSTANCE = new ShardingProxyClient();
    
    private static final int WORKER_MAX_THREADS = Runtime.getRuntime().availableProcessors();
    
    private EventLoopGroup workerGroup;
    
    @Getter
    private ChannelPoolMap<String, SimpleChannelPool> poolMap;
    
    private Map<String, DataScourceConfig> dataScourceConfigMap = Maps.newHashMap();
    
    /**
     * Start Sharding-Proxy.
     *
     * @throws InterruptedException  interrupted exception
     * @throws MalformedURLException url is illegal.
     */
    public void start() throws MalformedURLException, InterruptedException {
        Map<String, HikariConfig> dataSourceConfigurationMap = RuleRegistry.getInstance().getDataSourceConfigurationMap();
        for (Map.Entry<String, HikariConfig> each : dataSourceConfigurationMap.entrySet()) {
            URL url = new URL(each.getValue().getJdbcUrl().replaceAll("jdbc:mysql:", "http:"));
            final String ip = url.getHost();
            final int port = url.getPort();
            final String database = url.getPath().substring(1);
            final String username = (each.getValue()).getUsername();
            final String password = (each.getValue()).getPassword();
            dataScourceConfigMap.put(each.getKey(), new DataScourceConfig(ip, port, database, username, password));
        }
        final Bootstrap bootstrap = new Bootstrap();
        if (workerGroup instanceof EpollEventLoopGroup) {
            groupsEpoll(bootstrap);
        } else {
            groupsNio(bootstrap);
        }
        poolMap = new AbstractChannelPoolMap<String, SimpleChannelPool>() {
            @Override
            protected SimpleChannelPool newPool(String datasourceName) {
                DataScourceConfig dataScourceConfig = dataScourceConfigMap.get(datasourceName);
                //TODO maxConnection should be set.
                return new FixedChannelPool(bootstrap.remoteAddress(dataScourceConfig.ip, dataScourceConfig.port), new NettyChannelPoolHandler(dataScourceConfig), 10);
            }
        };
    }
    
    /**
     * Stop Sharding-Proxy.
     */
    public void stop() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
    
    private void groupsEpoll(final Bootstrap bootstrap) {
        workerGroup = new EpollEventLoopGroup(WORKER_MAX_THREADS);
        bootstrap.group(workerGroup)
                .channel(EpollSocketChannel.class)
                .option(EpollChannelOption.TCP_CORK, true)
                .option(EpollChannelOption.SO_KEEPALIVE, true)
                .option(EpollChannelOption.SO_BACKLOG, 128)
                .option(EpollChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }
    
    private void groupsNio(final Bootstrap bootstrap) {
        workerGroup = new NioEventLoopGroup(WORKER_MAX_THREADS);
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 100)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
    }
    
    /**
     * Get instance of sharding-proxy client.
     *
     * @return instance of sharding-proxy client
     */
    public static ShardingProxyClient getInstance() {
        return INSTANCE;
    }
    
    class DataScourceConfig {
        private final String ip;
        private final int port;
        private final String database;
        private final String username;
        private final String password;
        
        public DataScourceConfig(String ip, int port, String database, String username, String password) {
            this.ip = ip;
            this.port = port;
            this.database = database;
            this.username = username;
            this.password = password;
        }
    }
    
    class NettyChannelPoolHandler implements ChannelPoolHandler {
        private final DataScourceConfig dataScourceConfig;
        
        public NettyChannelPoolHandler(final DataScourceConfig dataScourceConfig) {
            this.dataScourceConfig = dataScourceConfig;
        }
        
        @Override
        public void channelReleased(Channel channel) throws Exception {
            log.info("channelReleased. Channel ID: {}" + channel.id().asShortText());
        }
        
        @Override
        public void channelAcquired(Channel channel) throws Exception {
            log.info("channelAcquired. Channel ID: {}" + channel.id().asShortText());
        }
        
        @Override
        public void channelCreated(Channel channel) throws Exception {
            channel.pipeline()
                    .addLast(new LoggingHandler(LogLevel.INFO))
                    .addLast(new ClientHandlerInitializer(dataScourceConfig.ip, dataScourceConfig.port, dataScourceConfig.database, dataScourceConfig.username, dataScourceConfig.password));
            log.info("channelCreated. Channel ID: {}" + channel.id().asShortText());
        }
    }
}
