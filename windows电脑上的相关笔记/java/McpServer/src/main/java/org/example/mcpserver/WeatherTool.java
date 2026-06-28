package org.example.mcpserver;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class WeatherTool {
    @Tool(description = "查询天气")
    public String queryWeather(@ToolParam(description = "城市")String city,@ToolParam(description = "想要知道几天内的天气")Integer day) throws URISyntaxException, IOException {
        System.out.println(city+"     "+day);
        String dayString=getRuleResult(day);
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet();
        String url="https://restapi.amap.com/v3/geocode/geo";
        URIBuilder uriBuilder=new URIBuilder(url);
        uriBuilder.addParameter("key", "6124c382ad473ae4055334c5c1a5a794");
        uriBuilder.addParameter("address", city);
        httpGet.setURI(uriBuilder.build());
        httpGet.setHeader("Accept", "application/json");
        httpGet.setHeader("Content-type", "application/json");
        CloseableHttpResponse response = null;
        CloseableHttpResponse execute = httpClient.execute(httpGet);
        if (execute.getStatusLine().getStatusCode() != 200) {
            throw new URISyntaxException(httpGet.getURI().toString(), "真是想挨操了");
        }
        String json = EntityUtils.toString(execute.getEntity());
        System.out.println(json.toString());
        JSONObject jsonObject = JSON.parseObject(json);
        JSONObject result = jsonObject.getJSONArray("geocodes").getJSONObject(0);
        String string1 = result.getString("location");
        httpClient.close();
        execute.close();
        CloseableHttpClient httpClient1 = HttpClients.createDefault();
        HttpGet httpGet1 = new HttpGet();
        String url1="https://q36yvxwtc7.re.qweatherapi.com/v7/weather/"+dayString;
        URIBuilder uriBuilder1=new URIBuilder(url1);
        //添加请求路径
        uriBuilder1.addParameter("location",string1);
        uriBuilder1.addParameter("key","d7c6e686eaee4456b8aefb8af45f6dc9");
        httpGet1.setURI(uriBuilder1.build());
        httpGet1.setHeader("Accept", "application/json");
        httpGet1.setHeader("Content-type", "application/json");
        //生成jwt并且放入请求头
        CloseableHttpResponse execute1 = httpClient1.execute(httpGet1);
        System.out.println(uriBuilder1.build().toString());
        if (execute1.getStatusLine().getStatusCode() != 200) {
            System.out.println(execute1.getStatusLine().getStatusCode());
            System.out.println(execute1.getStatusLine().getReasonPhrase());
            throw new URISyntaxException(httpGet1.getURI().toString(), null);
        }
        String json1 = EntityUtils.toString(execute1.getEntity());
        JSONObject jsonObject1 = JSON.parseObject(json1);
        JSONArray daily = jsonObject1.getJSONArray("daily");
        MCPResponse mcpResponse = new MCPResponse(UUID.randomUUID().toString(),"success",new ArrayList<>(),"呜呜呜");
        for(int i=0;i<day;i++){
            JSONObject jsonObject2 = daily.getJSONObject(i);
            Map<String,String> map=new HashMap<>();
            String date = jsonObject2.getString("fxDate");
            LocalDate localDate = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String time = localDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            map.put("date",time);
            String tempMax = jsonObject2.getString("tempMax");
            String tempMin = jsonObject2.getString("tempMin");
            String temperature=tempMin+"-"+tempMax;
            map.put("temperature",temperature);
            String string = jsonObject2.getString("textDay");
            map.put("textDay",string);
            mcpResponse.getResult().add(map);
        }
        httpClient1.close();
        execute1.close();
        return JSON.toJSONString(mcpResponse);
    }
    private String getRuleResult(Integer day) {
        if(day>0&&day<=3){
            return "3d";
        }else if(day>3&&day<=7){
            return "7d";
        }else if(day>7&&day<=10){
            return "10d";
        }else if(day>10&&day<=15){
            return "15d";
        }else if(day>15&&day<=30){
            return "30d";
        }else{
            return "-1";
        }
    }
}
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
class MCPRequest {
    private String id;
    private String method;
    private Map<String, String> params;
    private String timeout;
}
