package org.example.ClassDemo1.HomeWorkDemo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@Data
@NoArgsConstructor
public class Student {
    private String name;
    private String studyId;
    public void showGrade(ExamPaper examPaper){
        System.out.println("学生的姓名是"+name);
        System.out.println("学生的学号为"+studyId);
        System.out.println("学生的成绩为"+examPaper.getScore());
        for(Question question:examPaper.getQuestions()){
            System.out.println("这道题题目是"+question.getContent());
            System.out.println("学生的答案是"+question.getStudentQuestion());
        }
    }
}
