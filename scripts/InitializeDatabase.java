import java.nio.file.*;
import java.sql.*;

/** 一次性初始化空数据库/模式。密码只从环境读取，绝不作为命令行参数或日志输出。 */
public class InitializeDatabase {
    public static void main(String[] args)throws Exception {
        String type=args[0],schema=args[1];
        // 数据库名称不能用预编译参数绑定，拼接 DDL 前先限制为安全标识符。
        if(!type.matches("mysql|dm")||!schema.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new IllegalArgumentException("数据库类型或名称无效");
        String url=System.getenv("FACEDESK_DB_URL"),user=System.getenv("FACEDESK_DB_USER"),password=System.getenv("FACEDESK_DB_PASSWORD");
        if(user==null||password==null)throw new IllegalArgumentException("请设置FACEDESK_DB_USER与FACEDESK_DB_PASSWORD环境变量");
        if(url==null||url.isEmpty())url=type.equals("mysql")?"jdbc:mysql://localhost:3306/":"jdbc:dm://localhost:5236";
        // 先连接MySQL服务器，避免URL中的目标数据库尚未创建导致连接失败。
        if(type.equals("mysql"))url=url.replaceFirst("(jdbc:mysql://[^/]+/)[^?]*","$1");
        Class.forName(type.equals("mysql")?"com.mysql.cj.jdbc.Driver":"dm.jdbc.driver.DmDriver");
        try(Connection connection=DriverManager.getConnection(url,user,password);Statement statement=connection.createStatement()){
            if(type.equals("mysql")){
                statement.execute("CREATE DATABASE IF NOT EXISTS "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");connection.setCatalog(schema);
            }else{
                boolean exists=false;try(ResultSet schemas=connection.getMetaData().getSchemas()){while(schemas.next())if(schema.equals(schemas.getString("TABLE_SCHEM")))exists=true;}
                String owner=connection.getMetaData().getUserName();if(!owner.matches("[A-Za-z][A-Za-z0-9_]*"))throw new IllegalArgumentException("请使用标准命名的达梦初始化账户");
                if(!exists)statement.execute("CREATE SCHEMA "+schema+" AUTHORIZATION "+owner);connection.setSchema(schema);
            }
            // 仅允许初始化空库或空模式，拒绝覆盖已有业务表。
            try(ResultSet tables=connection.getMetaData().getTables(type.equals("mysql")?schema:null,type.equals("dm")?schema:null,"%",new String[]{"TABLE"})){
                if(tables.next())throw new IllegalStateException("目标数据库/模式不是空的，拒绝覆盖。请使用新建的独立数据库/模式");
            }
            // Connector/J的Statement会记住创建时的catalog，切换目标库后必须重新创建Statement。
            String sql=Files.readString(Paths.get(args[2]));
            // DDL 可能隐式提交；失败后需检查已建对象，不应直接对业务库重复执行。
            try(Statement ddl=connection.createStatement()){for(String part:sql.split(";"))if(!part.trim().isEmpty())ddl.execute(part);}
            System.out.println("FaceDesk数据库初始化完成："+type+" / "+schema);
        }
    }
}
