//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    public static void main(String[] args) {
        //TIP 当文本光标位于高亮显示的文本处时按 <shortcut actionId="ShowIntentionActions"/>
        // 查看 IntelliJ IDEA 建议如何修正。
        System.out.printf("Hello and welcome!");

        for (int i = 1; i <= 5; i++) {
            //TIP 按 <shortcut actionId="Debug"/> 开始调试代码。我们已经设置了一个 <icon src="AllIcons.Debugger.Db_set_breakpoint"/> 断点
            // 但您始终可以通过按 <shortcut actionId="ToggleLineBreakpoint"/> 添加更多断点。
            System.out.println("i = " + i);
        }
    }
}
//意思就是分两组之后这个name展示谁的，性别只有2种，姓名多个知道吧
//分组完之后相当于将数据库里面的字段只留下了你指定的那一个，所有人之按照这个标准归类划分，一行里面可能包含着多个人，所以再去考虑其他字段是毫无意义的，因为不同人的相同字段中的内容是不同的
//所以查询时只能查询指定字段，但是可以使用聚合函数，呜呜呜呜
//where是在分组之前进行过滤的，不满足where条件，不参与分组。而having是分组之后对结果进行过滤
//where不能对聚合函数进行判断，而having可以
//排序查询，多字段排序，当第一个字段值相同时，才会根据第二个字段进行排序，各个字段值之间使用逗号分隔
//分页查询 起始索引从零开始，起始索引=(查询页码-1)*每页显示记录数
//若查询的是第一页的数据，起始索引可以忽略
//一对多，一对一，多对多，一对多就是一个主表衍生出一堆子表，一对一实际上就是将一张大表进行拆分，形成了一一对应的关系，多对多就比较复杂了，比如一个学生可以选择多个课程，一个课程也可以被多个学生选择
//对于多对多，只在两个表中使用外键太过麻烦，于是建立一张中间表，中间表至少包含两个外键，分别关联两方主键

//物理外键已经被明令禁止，逻辑外键是主流
//苍穹外卖表结构设计





