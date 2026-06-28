package org.example.ClassDemo1.二维码;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

import java.nio.file.Path;
import java.nio.file.Paths;

public class Demo1 {
    public static void main(String[] args) {
        try {
            // 要编码的文本
            String content = "http://localhost:5173/";
            // 二维码图片保存的路径
            String filePath = "qrcode11.png";
            // 二维码的宽度和高度
            int width = 300;
            int height = 300;

            // 生成二维码
            BitMatrix bitMatrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, width, height);

            // 指定输出格式为PNG
            String format = "PNG";
            Path path = Paths.get(filePath);

            // 写入文件
            MatrixToImageWriter.writeToPath(bitMatrix, format, path);

            System.out.println("二维码生成成功，保存在: " + filePath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
