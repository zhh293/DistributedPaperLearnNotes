package com.zhh.handsome.springaiandalibaba.实践.智能体实践;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class NeedTools {
    @Tool
    public  String getWeather(@ToolParam(description = "The city to get the weather for.") String city) {
        return "The weather in " + city + " is sunny.";
    }
    @Tool
    public String getTime() {
        return "The time is " + java.time.LocalTime.now();
    }
    @Tool
    public String getDate() {
        return "The date is " + java.time.LocalDate.now();
    }
    @Tool
    public String getDayOfWeek() {
        return "The day of the week is " + java.time.LocalDate.now().getDayOfWeek();
    }
}
