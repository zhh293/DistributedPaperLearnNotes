package com.zhanghonghao.pintu;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

public class jianting extends JFrame implements MouseListener , KeyListener {
    public jianting() {
        //设置界面的宽高
        this.setSize(603,680);
        //设置界面的标题
        this.setTitle("拼图单机版 v1.0");
        //始终置顶
        this.setAlwaysOnTop(true);
        //出现在正中央
        this.setLocationRelativeTo(null);
        //设置关闭模式
        this.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        this.setLayout(null);
        //JButton jtb=new JButton("点击我");
        //jtb.setBounds(50,50,100,30);
        //jtb.addMouseListener(this);
        //this.getContentPane().add(jtb);
        //
        this.addKeyListener(this);
        this.setVisible(true);
    }
    @Override
    public void mouseClicked(MouseEvent e) {
        System.out.println("单击");
    }

    @Override
    public void mousePressed(MouseEvent e) {
        System.out.println("按下不松");
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        System.out.println("松开");
    }

    @Override
    public void mouseEntered(MouseEvent e) {
       System.out.println("划入");
    }

    @Override
    public void mouseExited(MouseEvent e) {
      System.out.println("鼠标划出");
    }

    @Override
    public void keyTyped(KeyEvent e) {

    }

    @Override
    public void keyPressed(KeyEvent e) {
        System.out.println("按下");
    }

    @Override
    public void keyReleased(KeyEvent e) {
      System.out.println("松开");
      int code=e.getKeyCode();
      System.out.println(code);
    }
}
