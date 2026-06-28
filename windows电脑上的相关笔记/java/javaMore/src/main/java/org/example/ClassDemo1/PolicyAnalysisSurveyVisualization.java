package org.example.ClassDemo1;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/*
public class httpclient {
    public static void main(String[] args) {
        */
/*CloseableHttpClient httpclient = HttpClients.createDefault();
        HttpGet httpget = new HttpGet("http://www.baidu.com");
        httpget.setHeader("User-Agent","Mozilla/5.0");*//*

        Double a = myPow(2.0,10);
        System.out.println(a);

    }
    public static double myPow(double x, int n) {
        //快速幂防止栈溢出
        if(n>=1){
            return myPow(x,n/2)*myPow(x,n-n/2);
        }else if(n<=-1){
            return 1/(myPow(x,(-1)*n/2)*myPow(x,n/2-n));
        }else{
            return 1.0;
        }
    }
}
*/
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

public class PolicyAnalysisSurveyVisualization extends Application {


    @Override
    public void start(Stage primaryStage) {
        // 创建坐标轴
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setLabel("功能需求");

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("需求提及率 (%)");
        yAxis.setTickUnit(10);
        yAxis.setLowerBound(0);
        yAxis.setUpperBound(100);

        // 创建柱状图
        BarChart<String, Number> barChart = new BarChart<>(xAxis, yAxis);
        barChart.setTitle("政策分析工具需求调研数据");
        barChart.setLegendVisible(false);

        // 创建数据系列
        XYChart.Series<String, Number> series = new XYChart.Series<>();

        // 添加数据点
        series.getData().add(createDataPoint("实时政策追踪", 78));
        series.getData().add(createDataPoint("个性化分析模型", 65));
        series.getData().add(createDataPoint("多部门协同功能", 59));
        series.getData().add(createDataPoint("政策风险预警", 53));
        series.getData().add(createDataPoint("政策效果模拟", 48));
        series.getData().add(createDataPoint("生成式AI摘要", 42));

        // 将数据添加到图表
        barChart.getData().add(series);

        // 设置柱状图样式
        barChart.setCategoryGap(20);
        barChart.setBarGap(5);

        // 创建布局并添加图表
        VBox vbox = new VBox(barChart);

        // 创建场景
        Scene scene = new Scene(vbox, 800, 600);

        // 设置窗口标题并显示
        primaryStage.setTitle("政策分析工具需求调研数据可视化");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    // 创建数据点并添加工具提示
    private XYChart.Data<String, Number> createDataPoint(String category, double value) {
        XYChart.Data<String, Number> dataPoint = new XYChart.Data<>(category, value);

        // 创建工具提示
        Tooltip tooltip = new Tooltip(String.format("提及率: %.1f%%\n有效问卷: 1200份", value));
        tooltip.setShowDelay(Duration.millis(100));
        tooltip.setStyle("-fx-font-size: 12px; -fx-background-color: rgba(0, 0, 0, 0.7);");

        // 将工具提示附加到数据点
        Tooltip.install(dataPoint.getNode(), tooltip);

        return dataPoint;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
interface I {
    void show();
}
