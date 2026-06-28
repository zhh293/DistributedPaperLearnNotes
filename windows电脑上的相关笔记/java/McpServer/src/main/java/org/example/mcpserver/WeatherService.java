package org.example.mcpserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;


import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/*
@Service
public class WeatherService {
    private static final Logger logger= LoggerFactory.getLogger(WeatherService.class);
    //用户问我这里今天天气怎么样，ai就会想着调用我这里的工具类，然后我获取到天气数据，然后返回给ai
    private static final String API_KEY="2067e98e5727c05a5f4c91c60cf60d62";
    private static final String URL="https://restapi.amap.com/v3/weather/weatherInfo";
    private final RestTemplate restTemplate = new RestTemplate();

    */
/**
     * Spring AI工具方法：获取驾车路线并生成游玩规划
     * @param start 出发地（地址字符串，如"北京朝阳区"）
     * @param end 目的地（地址字符串，如"上海浦东新区"）
     * @return 包含路线信息和游玩规划的JSON字符串
     *//*

    @Tool(name = "规划驾车路线与游玩行程",
            description = "根据出发地和目的地获取驾车路线，并生成沿途游玩规划")
    public String getRouteAndPlan(
            @ToolParam(description = "出发地地址") String start,
            @ToolParam(description = "目的地地址") String end) throws URISyntaxException, JsonProcessingException {

        // 1. 调用高德地图API获取路线数据
        Map<String, Object> routeData = fetchRouteData(start, end);
        if (routeData == null) {
            return "{\"error\":\"路线获取失败，请检查地址或网络\"}";
        }

        // 2. 解析路线数据并生成游玩规划
        Map<String, Object> result = generateTravelPlan(routeData);

        // 3. 返回结构化JSON结果（AI模型可直接解析）
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
    }

    */
/**
     * 调用高德地图API获取路线原始数据
     *//*

    private Map<String, Object> fetchRouteData(String start, String end) throws URISyntaxException {
        try {
            // 构建API请求（先将地址转换为坐标，或直接使用地址字符串，需根据高德API文档调整）
            URIBuilder uriBuilder = new URIBuilder("https://restapi.amap.com/v3/direction/driving");
            uriBuilder.addParameter("key", AMAP_API_KEY);
            uriBuilder.addParameter("origin", start);       // 直接使用地址字符串（需API支持）
            uriBuilder.addParameter("destination", end);     // 或先调用地理编码API获取坐标
            uriBuilder.addParameter("extensions", "all");
            uriBuilder.addParameter("strategy", "0");        // 速度优先

            String apiUrl = uriBuilder.toString();
            logger.info("调用高德API: {}", apiUrl);

            // 发送请求并获取响应
            String response = restTemplate.getForObject(apiUrl, String.class);
            if (response == null) {
                logger.error("API响应为空");
                return null;
            }

            // 解析响应（示例：简化处理，实际需用JSON库解析）
            return new HashMap<>() {{
                put("response", response);
                put("start", start);
                put("end", end);
            }};
        } catch (Exception e) {
            logger.error("API调用失败", e);
            return null;
        }
    }

    */
/**
     * 生成游玩规划（示例逻辑，需根据实际路线数据调整）
     *//*

    private Map<String, Object> generateTravelPlan(Map<String, Object> routeData) {
        // 实际场景中，需从routeData中提取途经点、距离、时间等信息
        // 此处为示例，假设已获取以下数据：
        return new HashMap<>() {{
            put("totalDistance", "300公里");
            put("totalTime", "3小时30分钟");
            put("waypoints", new String[]{"苏州", "无锡"});
            put("recommendations", new Object[]{
                    new HashMap<>() {{
                        put("city", "苏州");
                        put("attractions", "拙政园、平江路");
                        put("food", "松鼠鳜鱼、生煎包");
                        put("stayTime", "2小时");
                    }},
                    new HashMap<>() {{
                        put("city", "无锡");
                        put("attractions", "鼋头渚、灵山胜境");
                        put("food", "无锡小笼包、酱排骨");
                        put("stayTime", "1.5小时");
                    }}
            });
        }};
    }
}
*/
