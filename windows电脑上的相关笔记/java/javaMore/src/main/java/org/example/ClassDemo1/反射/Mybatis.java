package org.example.ClassDemo1.反射;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;

public class Mybatis {
    public static void save(Object obj/*可变参数*/,String... value) throws IllegalAccessException, FileNotFoundException {
       // PrintStream out = new PrintStream("这里填你的文件地址");//但这时高级流，无法指定追加还是覆盖，所以需要让里面包裹一个低级流
        PrintStream out=new PrintStream(new FileOutputStream("",true));
         Class<?> clazz = obj.getClass();
         String sql = "insert into " + clazz.getSimpleName() + "(";
        Field[] declaredFields = clazz.getDeclaredFields();
        for (Field field : declaredFields) {
            sql += field.getName() + ",";
        }
        sql = sql.substring(0, sql.length() - 1) + ") values(";
        for(Field field:declaredFields){
            field.setAccessible(true);
            sql += "'" + field.get(obj) + "',";
            out.println(field.getName()+"="+field.get(obj));
        }
         sql = sql.substring(0, sql.length() - 1) + ")";
         System.out.println(sql);
         System.out.println("保存成功");
         out.close();
    }
}
