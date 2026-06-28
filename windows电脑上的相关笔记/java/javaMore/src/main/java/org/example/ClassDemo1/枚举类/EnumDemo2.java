package org.example.ClassDemo1.枚举类;

public class EnumDemo2 {
    public static void main(String[] args) {
        Orientation up = Orientation.UP;
        move(up);
    }
    public static  void move(Orientation oritation){
        switch (oritation){
            case UP:
                System.out.println("向上");
                break;
            case DOWN:
                System.out.println("向下");
                break;
            case LEFT:
                System.out.println("向左");
                break;
            case RIGHT:
                System.out.println("向右");
                break;
            default:
                System.out.println("无效");
        }
    }
}
enum Orientation{
    heihei,UP,DOWN,LEFT,RIGHT
}
