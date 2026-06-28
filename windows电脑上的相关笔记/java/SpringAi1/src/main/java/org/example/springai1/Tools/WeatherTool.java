package org.example.springai1.Tools;

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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class WeatherTool {
    @Tool(description = "查询天气")
    public String queryWeather(@ToolParam(description = "城市")String city,@ToolParam(description = "想要知道几天内的天气")Integer day) throws URISyntaxException, IOException {
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
        JSONObject jsonObject = JSON.parseObject(json);
        JSONObject result=jsonObject.getJSONObject("geocodes");
        String string1 = result.getString("location");
        httpClient.close();
        execute.close();
        CloseableHttpClient httpClient1 = HttpClients.createDefault();
        HttpGet httpGet1 = new HttpGet();
        String url1="https://q36yvxwtc7.re.qweatherapi.com/v7/weather/"+day+"d";
        URIBuilder uriBuilder1=new URIBuilder(url1);
        //添加请求路径
        uriBuilder1.addParameter("location",string1);
        uriBuilder1.addParameter("key","27TPA88QVA");
        httpGet1.setURI(uriBuilder1.build());
        httpGet1.setHeader("Accept", "application/json");
        httpGet1.setHeader("Content-type", "application/json");
        //生成jwt并且放入请求头
        httpGet1.setHeader("X-QW-Api-Key","27TPA88QVA");
        CloseableHttpResponse execute1 = httpClient1.execute(httpGet1);
        if (execute1.getStatusLine().getStatusCode() != 200) {
            throw new URISyntaxException(httpGet1.getURI().toString(), null);
        }
        String json1 = EntityUtils.toString(execute1.getEntity());
        JSONObject jsonObject1 = JSON.parseObject(json1);
        JSONArray daily = jsonObject1.getJSONArray("daily");
        MCPResponse mcpResponse = new MCPResponse();
        for(int i=0;i<day;i++){
            JSONObject jsonObject2 = daily.getJSONObject(i);
            String date = jsonObject2.getString("fxDate");
            LocalDateTime localDateTime = LocalDateTime.parse(date, DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
            String time = localDateTime.format(DateTimeFormatter.ofPattern("yyyy-HH-mm"));
            mcpResponse.getResult().put("date",time);
            String tempMax = jsonObject2.getString("tempMax");
            String tempMin = jsonObject2.getString("tempMin");
            String temperature=tempMin+"-"+tempMax;
            mcpResponse.getResult().put("temperature",temperature);
            String string = jsonObject2.getString("textDay");
            mcpResponse.getResult().put("weather",string);
        }
        String jsonString = JSON.toJSONString(mcpResponse);
        return jsonString;
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
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
class MCPResponse {
    private String id;
    private String status;
    private Map<String,String> result;
    private String error;
}
