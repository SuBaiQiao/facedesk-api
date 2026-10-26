package com.face.api;

import com.face.api.cluster.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterRoutingTest {
    @Test void processTokensChangeAndTaskIdsCarryOwnerOnlyInClusterMode(){
        ClusterProperties a=new ClusterProperties(),b=new ClusterProperties();assertNotEquals(a.getRouteId(),b.getRouteId());assertFalse(a.newTaskId().contains("."));a.setEnabled(true);assertTrue(a.newTaskId().startsWith(a.getRouteId()+"."));
    }
    @SuppressWarnings("unchecked")
    @Test void wrongNodeRequestAndMissingLeaseCannotReachControllers()throws Exception {
        ClusterProperties cluster=new ClusterProperties();cluster.setEnabled(true);
        org.springframework.jdbc.datasource.DriverManagerDataSource source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        com.face.api.repository.MybatisPlusDatabase database=new com.face.api.repository.MybatisPlusDatabase(source,"mysql",false);
        new org.springframework.jdbc.core.JdbcTemplate(source).execute("CREATE TABLE facedesk_nodes(node_id VARCHAR(64) PRIMARY KEY,heartbeat_ms BIGINT,feature_space_id VARCHAR(512))");
        new org.springframework.jdbc.core.JdbcTemplate(source).execute("CREATE TABLE recognition_history(node_id VARCHAR(64),status VARCHAR(32),kind VARCHAR(16),task_state VARCHAR(32),task_error VARCHAR(512))");
        try(NodeLease lease=new NodeLease(database,cluster,"fixture")){
        ObjectProvider<NodeLease> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(lease);
        ClusterRequestFilter filter=new ClusterRequestFilter(cluster,provider,new ObjectMapper());
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/admin/live/other.task");request.setContextPath("/api");request.setServletPath("/admin/live/other.task");MockHttpServletResponse response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,s)->fail("Wrong node request reached controller"));assertEquals(409,response.getStatus());
        when(provider.getIfAvailable()).thenReturn(null);request=new MockHttpServletRequest("POST","/api/admin/faces/from-capture");request.setContextPath("/api");request.setServletPath("/admin/faces/from-capture");response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->fail("Fenced node reached controller"));assertEquals(503,response.getStatus());
        }
    }
}
