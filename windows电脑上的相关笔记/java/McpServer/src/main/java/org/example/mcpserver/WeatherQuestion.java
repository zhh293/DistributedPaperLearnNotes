package org.example.mcpserver;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;

@Data
public class WeatherQuestion {
    @ToolParam(description = "城市名称，可以精确到某个区/县")
    private String city;
    @ToolParam(description = "想要预测的日期，支持'今天'、'明天'、'后天'或YYYY-MM-DD格式")
    private String date;
    public WeatherQuestion(String city, String date){
        switch ( date){
            case "今天":
                this.date = "today";
                break;
            case "明天":
                this.date = "tomorrow";
                break;
            case "后天":
                this.date = "afterTomorrow";
                break;
                case "":
                this.date = LocalDateTime.now().toString();
                break;
            default:
                this.date = date;
                break;
        }
        this.city = city;
    }

}
