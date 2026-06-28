package org.example.ClassDemo1.HomeWorkDemo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@AllArgsConstructor
@Data
public  class ExamPaper {
    private Integer QuestionCount;
    private Integer Score;
    private Question[] Questions;
    public ExamPaper(Question[]questions, Integer score,Integer questionCount){
        this.Questions = questions;
        this.Score = score;
        this.QuestionCount = questionCount;
        //问题数为三到五个，使用随机数
    }

}


//new一个student之后，初始化这个学生的各种信息
//然后new一张试卷对象，这张试卷初始化的时候会随机生成若干道题，也就是new几个问题对象（选择，判断等等），这些题都要进行继承问题类
//然后学生调用display方法查看试卷中的问题，并且调用answer函数进行回答，最后调用grade方法，进行评分，最后展示出学生的所有大体情况和个人信息