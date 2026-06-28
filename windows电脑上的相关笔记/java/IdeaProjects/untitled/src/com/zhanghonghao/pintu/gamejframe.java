package com.zhanghonghao.pintu;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.*;
import java.util.Random;

public class gamejframe extends javax.swing.JFrame implements ActionListener, KeyListener , MouseListener {
    int[]arrtemp={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
    int[]brrtemp={0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
    int [][]arr=new int[4][4];
    int [][]brr=new int[4][4];
    JButton jtb=new JButton();
    JButton jtb1=new JButton();
public gamejframe() {
    setjiemian();
    //初始化菜单
    setjmenu();
    initdata();
    initimage();

    this.setVisible(true);
}
     int x=0;
     int y=0;

    private void initdata() {
        Random random=new Random();
        for(int i=0;i<arrtemp.length;i++){
            int index=random.nextInt(arrtemp.length);
            int temp=arrtemp[i];
            arrtemp[i]=arrtemp[index];
            arrtemp[index]=temp;
        }
        int h=0;
        for(int i=0;i<arr.length;i++){
            for(int j=0;j<arr[i].length;j++){
                //记录空白方块所在位置
                if(arrtemp[h]==0){
                    x=i;y=j;
                    h++;
                }
                else{
                    arr[i][j]=arrtemp[h++];
                }

            }
        }
    }

    private void initimage() {
        win(arr);
        this.getContentPane().removeAll();
    //创建一个图片imageicon对象
        for(int i=0;i<=3;i++){
            for(int j=0;j<=3;j++){
                int num=arr[i][j]+1;
                ImageIcon sc=new ImageIcon("C:\\Users\\92819\\IdeaProjects\\untitled\\image1\\"+num+".jpg");
                JLabel l1=new JLabel(sc);
                //指定图片的位置
                l1.setBounds(j*108+83,i*108+110,108,108);
                l1.setBorder(new BevelBorder(BevelBorder.LOWERED));
                //this.add(l1);
                this.getContentPane().add(l1);
            }
        }
        JLabel p1=new JLabel(new ImageIcon("C:\\Users\\92819\\IdeaProjects\\untitled\\image1\\17.jpg"));
        p1.setBounds(40,40,508,560);
        this.getContentPane().add(p1);
        p1.setVisible(true);
        this.getContentPane().repaint();

    }

    private void setjiemian(gamejframe this) {
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
        //事件：某些操作，绑定监听：当发生某些事件时，执行某段代码，事件源：

        //jtb:组件对象，表示给哪个组件添加事件
        //
        jtb.setBounds(0,0,100,50);
        jtb.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
               Object source=e.getSource();
               if(source==jtb){
                   jtb.setSize(200,200);
               }
            }
        });
        jtb1.setBounds(100,0,100,50);
        jtb1.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Object source=e.getSource();
                if(source==jtb1){
                    Random r=new Random();
                    jtb1.setLocation(r.nextInt(500),r.nextInt(500));
                }
            }
        });
        //addactionlistener()需要传入一个实现actionlistener接口的对象，而这个接口引入之后需要重写方法，故传入this即可，下面重写方法就行
/*jtb.setBounds(0,0,100,50);
jtb.addActionListener(this);
jtb1.setBounds(100,0,100,50);
jtb1.addActionListener(this);*/

this.getContentPane().add(jtb);
this.getContentPane().add(jtb1);
this.addKeyListener(this);
}
    private void setjmenu(gamejframe this) {
    JMenuBar menuBar = new JMenuBar();
    JMenu function=new JMenu("功能");
    JMenu about=new JMenu("关于我们");
    //创建条目对象
    JMenu change=new JMenu("更换图片");
    JMenuItem replay=new JMenuItem("重新游戏");
    JMenuItem relogin=new JMenuItem("重新登陆");
    JMenuItem CLOSE=new JMenuItem("关闭游戏");

    JMenuItem account=new JMenuItem("公众号");

    JMenuItem beauty=new JMenuItem("美女");
    JMenuItem animal=new JMenuItem("动物");
    JMenuItem sport=new JMenuItem("运动");
    change.add(beauty);
    change.add(animal);
    change.add(sport);
    beauty.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            Object source=e.getSource();
            if(source==beauty){
                initdata();
                initimage();
            }
        }
    });
    animal.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            Object source=e.getSource();
            if(source==animal){
                initdata();
                initimage();
            }
        }
    });
    sport.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            Object source=e.getSource();
            if(source==sport){
                initdata();
                initimage();
            }
        }
    });
    function.add(change);
    function.add(replay);
    function.add(relogin);
    function.add(CLOSE);
    about.add(account);
    menuBar.add(function);
    menuBar.add(about);
    this.setJMenuBar(menuBar);
    replay.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            Object source=e.getSource();
            if(source==replay){
                initdata();
                initimage();
            }
        }
    });
    CLOSE.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            Object source=e.getSource();
            if(source==CLOSE){
                System.exit(0);
            }
        }
    });
    relogin.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            Object source=e.getSource();
            if(source==relogin){
                new loginjframe().setVisible(true);
                gamejframe.this.setVisible(false);
            }
        }
    });
    account.addActionListener(new ActionListener() {
        public void actionPerformed(ActionEvent e) {
            Object source=e.getSource();
            if(source==account){
                JDialog jd=new JDialog();
                ImageIcon sc=new ImageIcon("C:\\Users\\92819\\IdeaProjects\\untitled\\image1\\17.jpg");
                JLabel l1=new JLabel(sc);
                l1.setBounds(20,20,254,280);
                jd.setBounds(40,40,508,560);
                jd.getContentPane().add(l1);
                jd.setVisible(true);
            }
        }
    });
}
public void actionPerformed(ActionEvent e) {
   Object source= e.getSource();
   if(source==jtb){
   jtb.setSize(200,200);
   }else if(source==jtb1){
       Random r=new Random();
       jtb1.setLocation(r.nextInt(500),r.nextInt(500));
   }
}

    @Override
    public void keyTyped(KeyEvent e) {

    }

    @Override
    public void keyPressed(KeyEvent e) {
         int code=e.getKeyCode();
         if(code==65){
             int h=0;
             this.getContentPane().removeAll();
             for(int i=0;i<arr.length;i++){
                 for(int j=0;j<arr[i].length;j++){
                     brr[i][j]=brrtemp[h++];
                     ImageIcon sc=new ImageIcon("C:\\Users\\92819\\IdeaProjects\\untitled\\image1\\"+brr[i][j]+".jpg");
                     JLabel l1=new JLabel(sc);
                     l1.setBounds(j*108+83,i*108+110,108,108);
                     l1.setBorder(new BevelBorder(BevelBorder.LOWERED));
                     this.getContentPane().add(l1);
                 }
             }
             JLabel p1=new JLabel(new ImageIcon("C:\\Users\\92819\\IdeaProjects\\untitled\\image1\\17.jpg"));
             p1.setBounds(40,40,508,560);
             this.getContentPane().add(p1);
             p1.setVisible(true);
             this.getContentPane().repaint();
         }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == 37) { // 左
            if (y > 0) { // 检查是否可以向左移动
                arr[x][y] = arr[x][y - 1];
                arr[x][y - 1] = 0;
                y--;
                initimage(); // 更新图片显示
            }
        } else if (code == 38) { // 上
            if (x < arr.length - 1) { // 检查是否可以向上移动
                arr[x][y] = arr[x + 1][y];
                arr[x + 1][y] = 0;
                x++;
                initimage(); // 更新图片显示
            }
        } else if (code == 39) { // 右
            if (y < arr[0].length - 1) { // 检查是否可以向右移动
                arr[x][y] = arr[x][y + 1];
                arr[x][y + 1] = 0;
                y++;
                initimage(); // 更新图片显示
            }
        } else if (code == 40) { // 下
            if (x > 0) { // 检查是否可以向下移动
                arr[x][y] = arr[x - 1][y];
                arr[x - 1][y] = 0;
                x--;
                initimage(); // 更新图片显示
            }
        }
        if(code==65){
            initimage();
        }
        if(code==87){
            int h=0;
            this.getContentPane().removeAll();
            for(int i=0;i<arr.length;i++){
                for(int j=0;j<arr[i].length;j++){
                    brr[i][j]=brrtemp[h++];
                    ImageIcon sc=new ImageIcon("C:\\Users\\92819\\IdeaProjects\\untitled\\image1\\"+brr[i][j]+".jpg");
                    JLabel l1=new JLabel(sc);
                    l1.setBounds(j*108+83,i*108+110,108,108);
                    l1.setBorder(new BevelBorder(BevelBorder.LOWERED));
                    this.getContentPane().add(l1);
                }
            }
            JLabel p1=new JLabel(new ImageIcon("C:\\Users\\92819\\IdeaProjects\\untitled\\image1\\17.jpg"));
            p1.setBounds(40,40,508,560);
            this.getContentPane().add(p1);
            p1.setVisible(true);
            this.getContentPane().repaint();
            System.out.println("胜利了");
        }

    }
    public void win(int [][]arr){
        int found=0;
        int h=0;
        for(int i=0;i<arr.length;i++){
            for(int j=0;j<arr[i].length;j++){
                if(arr[i][j]!=brrtemp[h]){
                    found=1;
                    break;
                }
                h++;
            }
        }
        if(found==0){
            System.out.println("胜利了oooo");
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) {

    }

    @Override
    public void mousePressed(MouseEvent e) {

    }

    @Override
    public void mouseReleased(MouseEvent e) {

    }

    @Override
    public void mouseEntered(MouseEvent e) {

    }

    @Override
    public void mouseExited(MouseEvent e) {

    }
}
