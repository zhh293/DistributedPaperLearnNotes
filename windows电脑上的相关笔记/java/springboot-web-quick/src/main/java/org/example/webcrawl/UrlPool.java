package org.example.webcrawl;

import org.jsoup.helper.HttpConnection;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UrlPool {
    public static void main(String[] args) {
       getUrl("http://www.nipic.com/");
    }
    private static void getUrl(String url) {
        Map<String,Boolean> oldMap = new LinkedHashMap<>();
        String oldLinkHost="";
        Pattern pattern=Pattern.compile("(http?://)?[^/\\s]*");
        Matcher matcher=pattern.matcher(url);
        while (matcher.find()) {
            oldLinkHost=matcher.group();
        }
        oldMap.put(oldLinkHost,false);
        crawl(oldLinkHost,oldMap);
        Set<Map.Entry<String, Boolean>> entries = oldMap.entrySet();
        for (Map.Entry<String, Boolean> entry : entries) {
            System.out.println(entry.getKey()+":"+entry.getValue());
        }

    }
    private static void crawl(String url,Map<String,Boolean> oldMap) {
         Map<String,Boolean> newMap=new HashMap<>();
         String newLinkHost="";
        Set<Map.Entry<String, Boolean>> entries = oldMap.entrySet();
        for (Map.Entry<String, Boolean> entry : entries) {
            if(!entry.getValue()){
                newLinkHost=entry.getKey();
                try {
                    URL urlObj=new URL(newLinkHost);
                    HttpURLConnection urlConnection =(HttpURLConnection) urlObj.openConnection();
                    urlConnection.setRequestMethod("GET");
                    urlConnection.connect();
                    if(urlConnection.getResponseCode()==200){
                        InputStream inputStream = urlConnection.getInputStream();
                        BufferedReader bufferedReader=new BufferedReader(new InputStreamReader(inputStream));
                        Pattern pattern=Pattern.compile("(?<=[.!?;])\s*.*?<a\b[^>]*>.*?</a>.*?(?=[.!?;]|$)");
                        Matcher matcher=null;
                        String line="";
                        while ((line=bufferedReader.readLine())!=null) {
                            matcher=pattern.matcher(line);
                            if(matcher.find()){
                                String link=matcher.group(1).trim();
                                if(!link.startsWith("http")){
                                    if(link.startsWith("/")){
                                        link=url+link;
                                    }else {
                                        link=url+"/"+link;
                                    }
                                }
                                if(link.endsWith("/")){
                                    link=link.substring(0,link.length()-1);
                                }
                                if(!oldMap.containsKey(link)&&!newMap.containsKey(link)&&link.startsWith(url)){
                                    newMap.put(link,false);

                                }
                            }
                            System.out.println(bufferedReader.readLine());
                        }
                    }



                }catch (Exception e) {
                    e.printStackTrace();
                }finally {


                }
                oldMap.replace(newLinkHost,false,true);
            }
            if(!newMap.isEmpty()){
                oldMap.putAll(newMap);

            }
        }
    }
}
