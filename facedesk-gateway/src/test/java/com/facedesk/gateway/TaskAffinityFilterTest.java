package com.facedesk.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.*;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskAffinityFilterTest {
    @Test void refusedConnectionReturns503WithoutRetrying(){
        MockServerWebExchange exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/status"));
        new GatewayUnavailableHandler().handle(exchange,new java.net.ConnectException("fixture")).block();assertEquals(503,exchange.getResponse().getRawStatusCode());
    }
    final String a="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    ServiceInstance instance(String token,int port){DefaultServiceInstance s=new DefaultServiceInstance(token,"facedesk-api","127.0.0.1",port,false);s.getMetadata().put("facedesk-route-id",token);return s;}
    MockServerWebExchange request(String path,String cookie){MockServerHttpRequest.BaseBuilder<?> builder=MockServerHttpRequest.get("http://localhost:8088"+path);if(cookie!=null)builder.cookie(new HttpCookie("FACEDESK_NODE",cookie));MockServerWebExchange exchange=MockServerWebExchange.from(builder);exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,URI.create("lb://facedesk-api"+path));return exchange;}
    @Test void taskOwnerOverridesCookieAndRetainsPathAndQuery(){
        ReactiveDiscoveryClient discovery=mock(ReactiveDiscoveryClient.class);when(discovery.getInstances("facedesk-api")).thenReturn(Flux.just(instance(a,18091),instance(b,18092)));
        TaskAffinityFilter filter=new TaskAffinityFilter(discovery);MockServerWebExchange exchange=request("/api/admin/videos/"+b+".12345678-1234-1234-1234-123456789012/faces?page=2",a);
        AtomicReference<URI> routed=new AtomicReference<>();filter.filter(exchange,e->{routed.set(e.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR));return Mono.empty();}).block();
        assertEquals(18092,routed.get().getPort());assertEquals("page=2",routed.get().getRawQuery());assertTrue(routed.get().getPath().endsWith("/faces"));
    }
    @Test void offlineOwnerReturns503WithoutForwardingToAnotherNode(){
        ReactiveDiscoveryClient discovery=mock(ReactiveDiscoveryClient.class);when(discovery.getInstances("facedesk-api")).thenReturn(Flux.just(instance(a,18091)));
        MockServerWebExchange exchange=request("/api/admin/live/"+b+".12345678-1234-1234-1234-123456789012/stop",null);
        new TaskAffinityFilter(discovery).filter(exchange,e->{fail("Wrong node must not receive request");return Mono.empty();}).block();assertEquals(503,exchange.getResponse().getRawStatusCode());
    }
    @Test void newClientIsBalancedThenBrowserCookiePinsManagementNode(){
        ReactiveDiscoveryClient discovery=mock(ReactiveDiscoveryClient.class);when(discovery.getInstances("facedesk-api")).thenReturn(Flux.just(instance(a,18091),instance(b,18092)));
        TaskAffinityFilter filter=new TaskAffinityFilter(discovery);List<Integer> ports=new ArrayList<>();
        for(int i=0;i<2;i++){MockServerWebExchange exchange=request("/api/admin/status",null);filter.filter(exchange,e->{ports.add(((URI)e.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)).getPort());return Mono.empty();}).block();assertNotNull(exchange.getResponse().getCookies().getFirst("FACEDESK_NODE"));}
        assertEquals(Arrays.asList(18091,18092),ports);
        MockServerWebExchange pinned=request("/api/admin/engine/pause",b);filter.filter(pinned,e->{assertEquals(18092,((URI)e.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)).getPort());return Mono.empty();}).block();
    }
}
