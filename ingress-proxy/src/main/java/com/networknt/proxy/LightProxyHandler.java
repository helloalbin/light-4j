/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.client.Http2Client;
import com.networknt.config.JsonMapper;
import com.networknt.handler.Handler;
import com.networknt.metrics.MetricsConfig;
import com.networknt.metrics.AbstractMetricsHandler;
import com.networknt.utility.ModuleRegistry;
import com.networknt.handler.ProxyHandler;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;


/**
 * This is a wrapper class for LightProxyHandler as it is implemented as final. This class implements
 * the HttpHandler which can be injected into the handler.yml configuration file as another option
 * for the handler injection. The other option is to use RouterHandlerProvider in service.yml file.
 *
 * @author Steve Hu
 */
public class LightProxyHandler implements HttpHandler {
    static final String CLAIMS_KEY = "jwtClaims";
    private static final int LONG_CLOCK_SKEW = 1000000;

    static final Logger logger = LoggerFactory.getLogger(LightProxyHandler.class);
    static ProxyConfig config;

    static ProxyHandler proxyHandler;
    static AbstractMetricsHandler metricsHandler;

    public LightProxyHandler() {
        config = ProxyConfig.load();
        ModuleRegistry.registerModule(LightProxyHandler.class.getName(), config.getMappedConfig(), null);
        List<String> hosts = new ArrayList<>(Arrays.asList(config.getHosts().split(",")));
        if(logger.isTraceEnabled()) logger.trace("hosts = " + JsonMapper.toJson(hosts));
        LoadBalancingProxyClient loadBalancer = new LoadBalancingProxyClient()
                .setConnectionsPerThread(config.getConnectionsPerThread());
        // we want to duplicate the host to double if there is only one host in the configuration.
        if(hosts.size() == 1) {
            hosts.add(hosts.get(0));
        }
        for(String host: hosts) {
            try {
                URI uri = new URI(host);
                switch (uri.getScheme()) {
                    case "http":
                        loadBalancer.addHost(new URI(host));
                        break;
                    case "https":
                        loadBalancer.addHost(new URI(host), Http2Client.getInstance().getDefaultXnioSsl());
                        break;
                    default:
                        logger.error("Incorrect schema " + uri.getScheme());
                }
            } catch (URISyntaxException e) {
                logger.error("Exception for host " + host, e);
                throw new RuntimeException(e);
            }
        }
        proxyHandler = ProxyHandler.builder()
                .setProxyClient(loadBalancer)
                .setMaxConnectionRetries(config.getMaxConnectionRetries())
                .setMaxQueueSize(config.getMaxQueueSize())
                .setMaxRequestTime(config.getMaxRequestTime())
                .setReuseXForwarded(config.isReuseXForwarded())
                .setRewriteHostHeader(config.isRewriteHostHeader())
                .setNext(ResponseCodeHandler.HANDLE_404)
                .build();

        if(config.isMetricsInjection()) {
            // get the metrics handler from the handler chain for metrics registration. If we cannot get the
            // metrics handler, then an error message will be logged.
            Map<String, HttpHandler> handlers = Handler.getHandlers();
            metricsHandler = (AbstractMetricsHandler) handlers.get(MetricsConfig.CONFIG_NAME);
            if(metricsHandler == null) {
                logger.error("An instance of MetricsHandler is not configured in the handler.yml.");
            }
        }
    }

    @Override
    public void handleRequest(HttpServerExchange httpServerExchange) throws Exception {
        if(logger.isDebugEnabled()) logger.debug("LightProxyHandler.handleRequest starts.");
        long startTime = System.nanoTime();
        if(config.isForwardJwtClaims()) {
            HeaderMap headerValues = httpServerExchange.getRequestHeaders();
            JwtClaims jwtClaims = extractClaimsFromJwt(headerValues);
            httpServerExchange.getRequestHeaders().put(HttpString.tryFromString(CLAIMS_KEY), new ObjectMapper().writeValueAsString(jwtClaims.getClaimsMap()));
        }
        proxyHandler.handleRequest(httpServerExchange);
        if(config.isMetricsInjection() && metricsHandler != null) metricsHandler.injectMetrics(httpServerExchange, startTime, config.getMetricsName(), null);
        if(logger.isDebugEnabled()) logger.debug("LightProxyHandler.handleRequest ends.");
    }

    /**
     * Takes in the header values from the request as a headerMap.
     * Grab the JWT from the auth header, then extract and return the claims.
     *
     * @param headerValues - the header values from the request
     * @return - the claims from the token
     */
    private JwtClaims extractClaimsFromJwt(HeaderMap headerValues) {

        // make sure request actually contained authentication header value
        if(headerValues.get(Headers.AUTHORIZATION_STRING) != null)
        {
            String jwt = String.valueOf(headerValues.get(Headers.AUTHORIZATION_STRING)).split(" ")[1];
            JwtConsumer jwtConsumer = new JwtConsumerBuilder()
                    .setSkipSignatureVerification()
                    .setSkipAllDefaultValidators()
                    .setAllowedClockSkewInSeconds(LONG_CLOCK_SKEW)
                    .build();
            JwtClaims jwtClaims = null;
            try {
                jwtClaims = jwtConsumer.processToClaims(jwt);
            } catch (InvalidJwtException e) {
                e.printStackTrace();
            }
            return jwtClaims;
        } else {
            return new JwtClaims();
        }

    }

    public void reload() {
        config.reload();
        ModuleRegistry.registerModule(LightProxyHandler.class.getName(), config.getMappedConfig(), null);
        List<String> hosts = new ArrayList<>(Arrays.asList(config.getHosts().split(",")));
        if(logger.isTraceEnabled()) logger.trace("hosts = " + JsonMapper.toJson(hosts));
        LoadBalancingProxyClient loadBalancer = new LoadBalancingProxyClient()
                .setConnectionsPerThread(config.getConnectionsPerThread());
        // we want to duplicate the host to double if there is only one host in the configuration.
        if(hosts.size() == 1) {
            hosts.add(hosts.get(0));
        }
        for(String host: hosts) {
            try {
                URI uri = new URI(host);
                switch (uri.getScheme()) {
                    case "http":
                        loadBalancer.addHost(new URI(host));
                        break;
                    case "https":
                        loadBalancer.addHost(new URI(host), Http2Client.getInstance().getDefaultXnioSsl());
                        break;
                    default:
                        logger.error("Incorrect schema " + uri.getScheme());
                }
            } catch (URISyntaxException e) {
                logger.error("Exception for host " + host, e);
                throw new RuntimeException(e);
            }
        }
        proxyHandler = ProxyHandler.builder()
                .setProxyClient(loadBalancer)
                .setMaxConnectionRetries(config.getMaxConnectionRetries())
                .setMaxQueueSize(config.getMaxQueueSize())
                .setMaxRequestTime(config.getMaxRequestTime())
                .setReuseXForwarded(config.isReuseXForwarded())
                .setRewriteHostHeader(config.isRewriteHostHeader())
                .setNext(ResponseCodeHandler.HANDLE_404)
                .build();
        if(config.isMetricsInjection()) {
            // get the metrics handler from the handler chain for metrics registration. If we cannot get the
            // metrics handler, then an error message will be logged.
            Map<String, HttpHandler> handlers = Handler.getHandlers();
            metricsHandler = (AbstractMetricsHandler) handlers.get(MetricsConfig.CONFIG_NAME);
            if(metricsHandler == null) {
                logger.error("An instance of MetricsHandler is not configured in the handler.yml.");
            }
        }
    }
}
