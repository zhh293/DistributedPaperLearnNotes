package org.example.ClassDemo1.用java控制本地电脑的进程文件;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class Demo1 {

    public class FileProcessUtil {
        //说白了，其实本质上就是操控cmd命令行来进行若干操作的，然后把这些写到@Tool工具类里面就成了一个个MCP服务器。。。。。。。。。牛逼，哈哈哈哈


        //加油加油!!!!!!!!!!!!!!!!

        // 打开指定文件（可用于打开Excel等各类文件）
        public static void openFile(String filePath) throws IOException {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // Windows系统
                pb = new ProcessBuilder("cmd.exe", "/c", "start", "\"\"", filePath);
            } else if (os.contains("mac")) {
                // MacOS系统
                pb = new ProcessBuilder("open", filePath);
            } else if (os.contains("nix") || os.contains("nux")) {
                // Linux系统
                pb = new ProcessBuilder("xdg-open", filePath);
            } else {
                throw new UnsupportedOperationException("不支持的操作系统");
            }

            pb.start();
        }

        // 打开Excel文件（专门针对Excel的方法）
        public static void openExcel(String excelFilePath) throws IOException {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // Windows系统 - 直接调用Excel程序
                pb = new ProcessBuilder("cmd.exe", "/c", "start", "excel.exe", "\"" + excelFilePath + "\"");
            } else if (os.contains("mac")) {
                // MacOS系统
                pb = new ProcessBuilder("open", "-a", "Microsoft Excel", excelFilePath);
            } else if (os.contains("nix") || os.contains("nux")) {
                // Linux系统 - 通常使用libreoffice打开
                pb = new ProcessBuilder("libreoffice", excelFilePath);
            } else {
                throw new UnsupportedOperationException("不支持的操作系统");
            }

            pb.start();
        }

        // 打开文件管理器并定位到指定文件
        public static void openFileManagerAtFile(String filePath) throws IOException {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // Windows系统
                pb = new ProcessBuilder("explorer.exe", "/select,", filePath);
            } else if (os.contains("mac")) {
                // MacOS系统
                pb = new ProcessBuilder("open", "-R", filePath);
            } else if (os.contains("nix") || os.contains("nux")) {
                // Linux系统（使用nautilus作为示例，不同发行版可能不同）
                pb = new ProcessBuilder("nautilus", "--select", filePath);
            } else {
                throw new UnsupportedOperationException("不支持的操作系统");
            }

            pb.start();
        }

        // 杀死指定进程（根据进程名）
        public static void killProcessByName(String processName) throws IOException {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                // Windows系统
                pb = new ProcessBuilder("taskkill", "/F", "/IM", processName + "*");
            } else if (os.contains("mac") || os.contains("nix") || os.contains("nux")) {
                // MacOS和Linux系统
                pb = new ProcessBuilder("pkill", "-f", processName);
            } else {
                throw new UnsupportedOperationException("不支持的操作系统");
            }

            pb.start();
        }


        // 根据端口号杀死进程
        public static void killProcessByPort(int port) throws IOException {
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                // Windows系统: 先找到占用端口的进程ID，再杀死该进程
                String command = "netstat -ano | findstr :" + port;
                Process process = Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", command});

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("LISTENING")) {
                            // 提取进程ID
                            String[] parts = line.trim().split("\\s+");
                            String pid = parts[parts.length - 1];
                            // 杀死进程
                            Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "taskkill /F /PID " + pid});
                            System.out.println("已杀死占用端口 " + port + " 的进程，PID: " + pid);
                        }
                    }
                }
            } else if (os.contains("mac") || os.contains("nix") || os.contains("nux")) {
                // Linux/Mac系统: 使用lsof查找端口并杀死进程
                String command = "lsof -i :" + port + " | grep LISTEN | awk '{print $2}' | xargs kill -9";
                Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", command});
                System.out.println("已杀死占用端口 " + port + " 的进程");
            } else {
                throw new UnsupportedOperationException("不支持的操作系统");
            }
        }

        // 示例用法
        public static void main(String[] args) {
            try {
                // 示例：打开一个Excel文件
                String excelPath = "C:/example.xlsx"; // Windows示例路径
                // String excelPath = "/Users/username/example.xlsx"; // Mac示例路径
                openExcel(excelPath);

                // 示例：打开任意文件
                // String filePath = "C:/document.pdf";
                // openFile(filePath);

                // 示例：在文件管理器中显示指定文件
                // openFileManagerAtFile(excelPath);

                // 示例：杀死Excel进程（谨慎使用）
                // Thread.sleep(5000); // 等待5秒后关闭
                // if (os.contains("win")) {
                //     killProcessByName("excel.exe");
                // } else if (os.contains("mac")) {
                //     killProcessByName("Microsoft Excel");
                // }


                // 示例：杀死占用8080端口的进程（谨慎使用）
                // Thread.sleep(5000);
                // killProcessByPort(8080);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}
