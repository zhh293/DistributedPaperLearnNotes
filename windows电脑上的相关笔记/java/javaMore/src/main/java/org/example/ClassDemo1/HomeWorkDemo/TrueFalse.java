package org.example.ClassDemo1.HomeWorkDemo;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class TrueFalse extends Question{
    private boolean answer;
//    private String studentQuestion;
    public TrueFalse(String content, String answer,String studentQuestion) {
        super(content, answer,studentQuestion);
    }
    @Override
    public void display() {
        System.out.println("题目是"+getContent());

    }
    @Override
    public void answer(String answer) {

    }
    @Override
    public int grade(Student  student,Integer EveryQuestionScore) {
       return  0;
    }
}
