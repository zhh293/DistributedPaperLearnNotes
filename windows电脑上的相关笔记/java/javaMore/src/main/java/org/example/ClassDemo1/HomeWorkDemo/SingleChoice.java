package org.example.ClassDemo1.HomeWorkDemo;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SingleChoice extends  Question{
    private boolean answer;
//    private String studentQuestion;
    public SingleChoice(String content, String answer,String studentQuestion) {
        super(content, answer,studentQuestion);
    }
    @Override
    public void display() {
        System.out.println("单选题：" + getContent());
    }
    @Override
    public void answer(String answer) {
        System.out.println("答案：" + answer);
    }
    @Override
    public int grade(Student  student,Integer EveryQuestionScore) {
        return 0;
    }
}
