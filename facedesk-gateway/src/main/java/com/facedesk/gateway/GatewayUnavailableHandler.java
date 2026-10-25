package com.facedesk.gateway;

import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

/** 注册中心感知实例离线需要时间；连接失败直接返回503，不重试具有副作用的上传/录入请求。 */
@Component @Order(-2)
public class GatewayUnavailableHandler implements ErrorWebExceptionHandler {
    @Override public Mono<Void> handle(ServerWebExchange exchange,Throwable failure){
        boolean unavailable=false;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof ConnectException||cause instanceof TimeoutException||cause instanceof io.netty.handler.timeout.ReadTimeoutException){unavailable=true;break;}
        // 只转换连接故障，业务异常及已发送响应的请求交由原有错误链处理。
        if(!unavailable||exchange.getResponse().isCommitted())return Mono.error(failure);
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        byte[] body="{\"code\":\"503\",\"msg\":\"处理实例暂不可用，请稍后重试；视频或摄像头任务可能需要重新创建\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
