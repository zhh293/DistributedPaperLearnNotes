package org.example;

import java.io.InputStream;
import java.util.Properties;

public class Config {
    static Properties properties;
    static{
        try (InputStream inputStream=Config.class.getResourceAsStream("/application.properties"))
            { properties=new Properties();
            properties.load(inputStream);
        } catch (Exception e) {
            throw new RuntimeException(e
                    .getCause());
        }
    }
    public static String getPassword(){
        return properties.getProperty("password");
    }
    public static String getUsername(){
        return properties.getProperty("username");
    }
    public static String getUrl(){
        return properties.getProperty("url");
    }
}
