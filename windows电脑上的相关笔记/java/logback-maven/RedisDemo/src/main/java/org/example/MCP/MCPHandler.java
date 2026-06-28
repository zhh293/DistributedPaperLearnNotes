package org.example.MCP;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MCPHandler extends SimpleChannelInboundHandler<String> {
    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, String s) throws Exception {
        MCPRequest mcpRequest = JSONObject.parseObject(s, MCPRequest.class);
        String id = mcpRequest.getId();
        Map<String, String> params = mcpRequest.getParams();
        String s1 = params.get("city");
        String s2 = params.get("day");
        int i1 = Integer.parseInt(s2);
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet();
        String url="https://restapi.amap.com/v3/geocode/geo";
        URIBuilder uriBuilder=new URIBuilder(url);
        uriBuilder.addParameter("key", "6124c382ad473ae4055334c5c1a5a794");
        uriBuilder.addParameter("address", s1);
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
        String url1="https://q36yvxwtc7.re.qweatherapi.com/v7/weather/"+i1+"d";
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
        for(int i=0;i<i1;i++){
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
        mcpResponse.setId(id);
        mcpResponse.setStatus("success");
        String jsonString = JSON.toJSONString(mcpResponse);
        channelHandlerContext.writeAndFlush(jsonString);
    }
}
