package com.facedesk.gateway;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;

/** 本地视频与摄像头任务必须回到创建它的进程；地址仅取自Nacos，不接受客户端目标URL。 */
@Component
public class TaskAffinityFilter implements GlobalFilter,Ordered {
    private static final Pattern TASK=Pattern.compile("^/api/admin/(?:videos|live)/([^/]+)(?:/.*)?$");
    private static final Pattern ID=Pattern.compile("^([a-f0-9]{32})\\.[a-f0-9-]{36}$");
    private static final String COOKIE="FACEDESK_NODE";
    private final ReactiveDiscoveryClient discovery;
    private final AtomicInteger next=new AtomicInteger();
    public TaskAffinityFilter(ReactiveDiscoveryClient discovery){this.discovery=discovery;}
    // RouteToRequestUrlFilter之后、标准负载均衡过滤器之前写入确定的HTTP地址。
    @Override public int getOrder(){return 10149;}
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain){
        java.net.URI routed=exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        if(routed==null||!"lb".equals(routed.getScheme())||!"facedesk-api".equals(routed.getHost()))return chain.filter(exchange);
        Matcher task=TASK.matcher(exchange.getRequest().getURI().getPath());
        String owner=null;
        if(task.matches()&&!"capabilities".equals(task.group(1))){
            Matcher id=ID.matcher(task.group(1));if(!id.matches())return reject(exchange,400,"分布式任务ID无效，请重新创建任务");owner=id.group(1);
        }
        // 任务归属优先于浏览器 Cookie；Cookie 仅提供普通请求亲和性，不承担鉴权。
        final String required=owner;
        // 只在发现阶段转换错误；下游业务错误不能误触发第二次转发或重试上传。
        return discovery.getInstances("facedesk-api").filter(s->s.getMetadata().get("facedesk-route-id")!=null).collectList()
            .onErrorReturn(Collections.emptyList()).flatMap(instances->{
                ServiceInstance selected=null;
                HttpCookie cookie=exchange.getRequest().getCookies().getFirst(COOKIE);
                String preferred=required!=null?required:cookie==null?null:cookie.getValue();
                for(ServiceInstance instance:instances)if(Objects.equals(preferred,instance.getMetadata().get("facedesk-route-id"))){selected=instance;break;}
                // 任务状态保存在原进程内存，所属节点离线时必须报错，不能转交其他实例。
                if(selected==null&&required!=null)return reject(exchange,503,"任务所属实例已离线，请重新提交视频或连接摄像头");
                // 普通请求轮询可用实例，floorMod 处理计数器溢出后的负数。
                if(selected==null&&!instances.isEmpty())selected=instances.get(Math.floorMod(next.getAndIncrement(),instances.size()));
                if(selected==null)return reject(exchange,503,"暂无可用的FaceDesk识别实例");
                // 只替换服务发现提供的主机和端口，保留原路径及编码后的查询参数。
                java.net.URI target=UriComponentsBuilder.fromUri(exchange.getRequest().getURI()).scheme(selected.isSecure()?"https":"http").host(selected.getHost()).port(selected.getPort()).build(true).toUri();
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,target);
                String token=selected.getMetadata().get("facedesk-route-id");
                exchange.getResponse().getHeaders().set("X-FaceDesk-Node",token);
                // 浏览器保持管理节点亲和性，使状态、暂停和恢复针对同一实例；任务ID优先于Cookie。
                if(required==null)exchange.getResponse().addCookie(ResponseCookie.from(COOKIE,token).path("/api").httpOnly(true).sameSite("Lax").secure("https".equals(exchange.getRequest().getURI().getScheme())).build());
                // 只转发一次，避免重试造成重复上传视频或重复录入档案。
                return chain.filter(exchange);
            });
    }
    private Mono<Void> reject(ServerWebExchange exchange,int status,String message){
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        byte[] body=("{\"code\":\""+status+"\",\"msg\":\""+message+"\"}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
