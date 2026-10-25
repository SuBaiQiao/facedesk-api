package com.face.api.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** 防止误连其他节点，并在共享库租约失效后拒绝新的业务请求。 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${facedesk.storage.type:sqlite}' == 'mysql' or '${facedesk.storage.type:sqlite}' == 'dm'")
public class ClusterRequestFilter extends OncePerRequestFilter {
    private static final Pattern TASK=Pattern.compile("^/admin/(?:videos|live)/([^/]+)(?:/.*)?$");
    private final ClusterProperties cluster;
    private final ObjectProvider<NodeLease> leases;
    private final ObjectMapper json;
    public ClusterRequestFilter(ClusterProperties cluster,ObjectProvider<NodeLease> leases,ObjectMapper json){this.cluster=cluster;this.leases=leases;this.json=json;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        response.setHeader("X-FaceDesk-Node",cluster.getRouteId());
        String path=request.getServletPath();if(path.isEmpty())path=request.getRequestURI().substring(request.getContextPath().length());
        // 健康端点需始终可达，租约失败由 HealthIndicator 报告不可用。
        if(path.startsWith("/actuator/")){chain.doFilter(request,response);return;}
        NodeLease lease=leases.getIfAvailable();
        if(lease==null||!lease.healthy()){reject(response,503,"处理实例租约失效，请通过网关重新连接");return;}
        Matcher task=TASK.matcher(path);
        // 直连 API 也需检查任务归属，不能读取其他进程内存中的任务。
        if(cluster.isEnabled()&&task.matches()&&!"capabilities".equals(task.group(1))&&!task.group(1).startsWith(cluster.getRouteId()+".")){
            reject(response,409,"任务不属于当前处理实例，请通过网关访问");return;
        }
        chain.doFilter(request,response);
    }
    private void reject(HttpServletResponse response,int status,String message)throws IOException{
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        Map<String,String> body=new LinkedHashMap<>();body.put("code",String.valueOf(status));body.put("msg",message);json.writeValue(response.getWriter(),body);
    }
}
