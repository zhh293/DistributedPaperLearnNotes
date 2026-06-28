package org.example;

import java.util.Observable;

public class WebFluxProfile extends Observable {
    public static void main(String[] args) {
        //传统的web框架，基于servlet
        //webflux是一种非阻塞式异步框架，基于reactor，响应式编程
        WebFluxProfile  webFluxProfile = new WebFluxProfile();
        webFluxProfile.addObserver((o, arg)->{
            System.out.println("收到通知");
        });
        webFluxProfile.addObserver((o, arg)->{
            System.out.println("收到通知,准备改变");
        });
        webFluxProfile.setChanged();//数据变化
        webFluxProfile.notifyObservers();//通知观察者
    }
}

