package com.zhh.handsome.Demo4;

public class ImageDownLoad extends Thread {
    // 持有结果持有者的引用（用于设置结果）
    private final GuardedImage guardedImage;
    // 模拟下载地址
    private final String imageUrl;

    public ImageDownLoad(GuardedImage guardedImage, String imageUrl) {
        this.guardedImage = guardedImage;
        this.imageUrl = imageUrl;
    }

    @Override
    public void run() {
        try {
            // 模拟下载耗时（2秒）
            System.out.println("开始下载图片：" + imageUrl);
            Thread.sleep(2000);

            // 模拟下载结果（实际场景是HTTP请求获取字节数组）
            byte[] mockImage = "模拟图片数据".getBytes();

            // 下载完成，设置结果并唤醒等待线程
            guardedImage.setImage(mockImage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("下载线程被中断");
        }
    }
}
