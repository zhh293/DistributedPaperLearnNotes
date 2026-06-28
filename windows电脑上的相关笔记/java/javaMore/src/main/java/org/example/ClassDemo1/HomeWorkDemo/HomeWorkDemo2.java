package org.example.ClassDemo1.HomeWorkDemo;

import java.util.Scanner;

public class HomeWorkDemo2 {
    public static void main(String[] args) {
        Student student = new Student("张三","2019001");
        Question[] questions = new Question[3];
        questions[0] = new SingleChoice("1+1=?","2",null);
        questions[1] = new MultiChoice("1+1=?","1,2",null);
        questions[2] = new TrueFalse("1+1=2","true",null);
        ExamPaper examPaper = new ExamPaper(questions,10,3);
        for(Question question : questions){
            question.display();
            Scanner scanner = new Scanner(System.in);
            question.answer(scanner.next());
            int grade = question.grade(student,examPaper.getScore()/examPaper.getQuestionCount());
            examPaper.setScore(examPaper.getScore()-grade);
        }
        student.showGrade(examPaper);
    }
}
