package com.zhanghonghao.normalclass.practice;

import java.util.Arrays;
import java.util.Comparator;

public class practice1 {
    public static void main(String[] args) {
        girlfriend g1=new girlfriend("zzz",18,186);
        girlfriend g2=new girlfriend("zhz",15,174);
        girlfriend g3=new girlfriend("zzh",15,180);
        girlfriend[]arr={g1,g2,g3};
        Arrays.sort(arr,new Comparator<girlfriend>() {
            public int compare(girlfriend o1, girlfriend o2) {
                if(o1.getAge()==o2.getAge()){
                    if(o1.getHeight()==o2.getHeight()){
                        int i=0;
                        while(true){
                            if(o1.getName().charAt(i)<o2.getName().charAt(i)){
                                return o1.getName().charAt(i)-o2.getName().charAt(i);
                            }

                            else if(o1.getName().charAt(i)>o2.getName().charAt(i)){
                                return o2.getName().charAt(i)-o1.getName().charAt(i);
                            }
                            else{
                                i++;
                            }
                        }
                    }
                    return o1.getHeight()-o2.getHeight();
                }
                return o1.getAge()-o2.getAge();
            }
        });
       for(int i=0;i<arr.length;i++){
           System.out.println(arr[i]);
       }
    }
}
