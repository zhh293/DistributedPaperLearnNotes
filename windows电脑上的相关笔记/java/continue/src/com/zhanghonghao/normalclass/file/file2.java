package com.zhanghonghao.normalclass.file;

import java.io.File;

public class file2 {
    public static void main(String[] args) {
        //获取并遍历
        File F=new File("E:\\java\\lianxi");
        //listFiles,获取文件夹里的所有内容，并且把内容放在数组里
        File[] files=F.listFiles();
        for(int i=0;i<files.length;i++){
            System.out.println(files[i].getAbsoluteFile());
        }
        //细节：当调用者file表示的路径不存在时，返回null，file表示的路径是文件时，也返回null
        //当是一个空文件夹时，返回一个长度为零的数组
        //文件夹有内容时，所有文件和文件夹的路径，包括隐藏文件，都会放在数组中返回
        //当文件夹需要权限时，也只能返回null
    }
}
