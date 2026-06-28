package com.zhanghonghao.doublearraylist;

import java.util.HashMap;
import java.util.Map;

public class hahaha {
    public static void main(String[] args) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        //添加数据时，如果键不存在，那么直接把键值对对象添加到map集合当中
        //如果存在，会把原有的键值对对象覆盖，并把覆盖的值返回
        //remove会把移除key值所对应的value值返回
        map.put("a", 1);
       int value= map.put("a", 1);
       System.out.println(value);
       map.put("b", 2);
       map.put("c", 3);
       System.out.println(map);
       int value1=map.remove("a");
       System.out.println(value1);
       boolean b = map.containsKey("b");
       System.out.println(b);
       boolean c=map.containsValue(3);
       System.out.println(c);
       boolean d=map.isEmpty();
       System.out.println(d);
       int e=map.size();
       System.out.println(e);
       map.clear();
       System.out.println(map);
    }
}
