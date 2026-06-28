package com.zhanghonghao;

import com.zhanghonghao.pintu.gamejframe;
import com.zhanghonghao.pintu.jianting;
import com.zhanghonghao.pintu.loginjframe;
import com.zhanghonghao.pintu.registerjframe;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class APP {
    public static void main(String[] args) {
        new gamejframe();
        new loginjframe();
        new registerjframe();
        //new jianting();
    }
}
