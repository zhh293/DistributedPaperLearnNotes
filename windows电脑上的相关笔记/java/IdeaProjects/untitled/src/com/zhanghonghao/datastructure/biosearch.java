package com.zhanghonghao.datastructure;

import java.util.Scanner;

public class biosearch {
    biosearch leftchild;
    biosearch rightchild;
    int data;
    public biosearch() {}
    public biosearch(int data) {
        this.data = data;
    }
    public  void setData(int data) {
        this.data = data;
    }
    public int getData() {
        return data;
    }
    public void createTree(biosearch leftchild, biosearch rightchild) {
        this.leftchild = leftchild;
        this.rightchild = rightchild;
    }
    public void printTree(biosearch biosearch) {
        if(biosearch.leftchild != null) {
            System.out.println(biosearch.leftchild.getData());
            printTree(biosearch.leftchild);
        }
        if(biosearch.rightchild != null) {
            System.out.println(biosearch.rightchild.getData());
            printTree(biosearch.rightchild);
        }
    }
}
