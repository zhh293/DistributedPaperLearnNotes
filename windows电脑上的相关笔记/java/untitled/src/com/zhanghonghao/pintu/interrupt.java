package com.zhanghonghao.pintu;

import java.util.Random;

public class interrupt {
    public static void main(String[] args) {
        int[]arrtemp={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
        Random random=new Random();
        for(int i=0;i<arrtemp.length;i++){
            int index=random.nextInt(arrtemp.length);
            int temp=arrtemp[i];
            arrtemp[i]=arrtemp[index];
            arrtemp[index]=temp;
        }
        for(int i=0;i<arrtemp.length;i++){
            System.out.print(arrtemp[i]+" ");
        }
        System.out.println();
        int [][]arr=new int[4][4];
        int h=0;
        for(int i=0;i<arr.length;i++){
            for(int j=0;j<arr[i].length;j++){
                arr[i][j]=arrtemp[h++];
            }
        }
        for(int i=0;i<arr.length;i++){
            for(int j=0;j<arr[i].length;j++){
                System.out.print(arr[i][j]+" ");
            }
        }
        System.out.println();
        for(int i=0;i<arrtemp.length;i++){
            arr[i/4][i%4]=arrtemp[i];
        }
        for(int i=0;i<arr.length;i++){
            for(int j=0;j<arr[i].length;j++){
                System.out.print(arr[i][j]+" ");
            }
        }
    }
}
