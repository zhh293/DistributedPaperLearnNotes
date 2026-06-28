import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
          //任意输入一个析取范式，计算并输出主析取范式
        System.out.println("请输入一个析取范式：");
        Scanner sc = new Scanner(System.in);
        String str = sc.nextLine();
        //这里默认格式正确
        String[] strs = str.split("\\|");
        StringBuilder sb = new StringBuilder();
        //分割完之后，
    }
}
