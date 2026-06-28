package com.zhh.handsome.Demo4;

public class Main {
    public static void main(String[] args) {
        GuardedImage guardedImage = new GuardedImage();
        ImageDownLoad imageDownLoad = new ImageDownLoad(guardedImage, "http://example.com/image.jpg");
        imageDownLoad.start();
        try {
            System.out.println("主线程等待图片下载...");
            Object image = guardedImage.getImage(3000); // 等待3秒
            System.out.println("图片下载成功，处理图片：" + new String((byte[]) image));
        } catch (InterruptedException e) {
            System.out.println("等待图片下载超时或被中断");
        }
    }
}
