package org.example.ClassDemo1.HomeWorkDemo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Data
@AllArgsConstructor
public abstract class Question {
    private String content;
    private String answer;
    private String studentQuestion;
   abstract void display();
   abstract void answer(String answer);
   abstract int grade(Student  student,Integer EveryQuestionScore);

}
