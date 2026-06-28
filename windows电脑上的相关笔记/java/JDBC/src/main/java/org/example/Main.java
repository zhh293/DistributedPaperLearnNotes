package org.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
       public static void main(String[] args) throws ClassNotFoundException, SQLException {
          //注册驱动
           Class<?> aClass = Class.forName("com.mysql.cj.jdbc.Driver");
           Connection connection = DriverManager.getConnection(Config.getUrl(), Config.getUsername(), Config.getPassword());
           System.out.println("连接成功");
           String sql = "select * from school";
           ResultSet resultSet = connection.createStatement().executeQuery(sql);
           System.out.println("查询成功");
           connection.close();
           resultSet.close();
       }
}