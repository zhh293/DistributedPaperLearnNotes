package org.example.springboot;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class xmlparserutils {
    public static <T>List<T> parse(String file,Class<T>targetClass) {
        ArrayList<T> list = new ArrayList<T>();
        try{
            SAXReader reader = new SAXReader();
            Document document = reader.read(new File(file));
            Element root = document.getRootElement();
            List<Element> elements = root.elements("emp");
            for (Element element : elements) {
                String name=element.element("name").getText();
                String age=element.element("age").getText();
                String gender=element.element("gender").getText();
                String job=element.element("job").getText();
                String image=element.element("image").getText();
                Constructor<T> constructor= targetClass.getDeclaredConstructor(String.class, Integer.class, String.class, String.class, String.class );
                constructor.setAccessible(true);

                T object= constructor.newInstance(name,Integer.parseInt(age),image,gender,job);
            }
        } catch (DocumentException | InvocationTargetException | NoSuchMethodException | InstantiationException |
                 IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        return list;
    }
}
