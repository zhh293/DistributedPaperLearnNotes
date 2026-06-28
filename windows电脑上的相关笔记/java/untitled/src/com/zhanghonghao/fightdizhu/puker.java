package com.zhanghonghao.fightdizhu;

import java.util.ArrayList;
import java.util.Collections;

public class puker {
    //准备牌
    //静态代码块，随着类的加载而加载，而且只执行一次
    static ArrayList <String>list=new ArrayList<>();
    static{
        String []color={"♠","♥","♣","♦"};
        String []number={"3","4","5","6","7","8","9","J","Q","K","A","2"};

        for(String str:color){
            for(String str1:number){
                list.add(str+str1);
            }
        }
        list.add("大王");
        list.add("小王");
    }
    public puker() {
        //打乱
        Collections.shuffle(list);
        //发牌
        ArrayList<String>lorder=new ArrayList<>();
        ArrayList<String>player1=new ArrayList<>();
        ArrayList<String>player2=new ArrayList<>();
        ArrayList<String>player3=new ArrayList<>();
        for(int i=0;i<list.size();i++){
            String poker=list.get(i);
            if(i<=2){
                lorder.add(poker);
                continue;
            }
            if(i%3==0){
                player1.add(poker);
            }
            else if(i%3==1){
                player2.add(poker);
            }
            else{
                player3.add(poker);
            }
        }
        //看牌
        lookpoker("底牌",lorder);
        lookpoker("sb",player1);
        lookpoker("sb1",player2);
        lookpoker("sb2",player3);
    }
public void lookpoker(String name,ArrayList<String>player){
        System.out.printf(name+"  ");
        for(String str:player){
            System.out.printf(str+" ");
        }
        System.out.println();
}

}
