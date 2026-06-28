package org.example.ClassDemo1.HomeWorkDemo;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class MultiChoice extends  Question{
    private boolean answer=false;
//    private String studentAnswer;
    public MultiChoice(String content, String answer,String studentQuestion) {
        super(content, answer,studentQuestion);
    }
    @Override
    public void display() {
        System.out.println("题目是"+getContent());
    }
    @Override
    public void answer(String answer) {
        super.setStudentQuestion(answer);
        if(answer.equals(super.getAnswer())){
            System.out.println("恭喜你，回答正确！");
            this.answer=true;
        }
        System.out.println("你的答案是：" + answer);
    }
    @Override
    public int grade(Student  student,Integer EveryQuestionScore) {
        if(!this.answer){
           //通过studentAnswer来判断扣分情况.这个很简单，比如说我们可以通过体量和总分算出每道题的分值，然后根据答得内容正确性给分即可
           return 10;
        }
        return 0;
    }
}
