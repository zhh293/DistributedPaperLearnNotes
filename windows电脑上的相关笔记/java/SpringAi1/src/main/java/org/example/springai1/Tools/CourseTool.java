package org.example.springai1.Tools;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import lombok.RequiredArgsConstructor;
import org.example.springai1.query.CourseQuery;
import org.example.springai1.service.ICourseReservationService;
import org.example.springai1.service.ICourseService;
import org.example.springai1.service.ISchoolService;
import org.example.springai1.util.Course;
import org.example.springai1.util.CourseReservation;
import org.example.springai1.util.School;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.List;
@RequiredArgsConstructor
@Component
public class CourseTool {
    private final ICourseService courseService;
    private final ICourseReservationService courseService1;
    private final ISchoolService schoolService;
    @Tool(description = "根据条件查询课程")
    //之后可以使用springsecurity 进行权限控制，来控制接口可以被谁访问。。。。
    public List<Course> queryCourse(@ToolParam(description = "查询的条件")CourseQuery courseQuery){
        if(courseQuery==null){
            return List.of();
        }
        QueryChainWrapper<Course> le = courseService.query()
                .eq(courseQuery.getType() != null, "type", courseQuery.getType())
                .le(courseQuery.getEdu() != null, "edu", courseQuery.getEdu());
        if(courseQuery.getSorts()!=null&&!courseQuery.getSorts().isEmpty()){
            for(CourseQuery.Sort sort:courseQuery.getSorts()){
                courseService.query().orderBy(true,sort.getAsc(),sort.getField());
            }
        }
        return le.list();
    }
    @Tool(description = "查询学校")
    @PreAuthorize("hasAuthority('ADMIN')")  // 正确写法：单引号包裹权限名
    public List<School> querySchool(){
        return schoolService.list();
    }
    @Tool(description = "生成预约单")
    public Integer createCourseReservation(@ToolParam(description = "预约的课程")String courseId,@ToolParam(description = "学生姓名")String studentName,@ToolParam(description = "预约人联系方式")String contactInfo,@ToolParam(description = "预约的校区")String school,@ToolParam(description = "预约备注",required = false)String  remark){
        CourseReservation courseReservation = new CourseReservation();
        courseReservation.setCourse(courseId);
        courseReservation.setStudentName(studentName);
        courseReservation.setContactInfo(contactInfo);
        courseReservation.setSchool(school);
        courseReservation.setRemark(remark);
        courseService1.save(courseReservation);
        return courseReservation.getId();
    }
    /*
    你像这个函数的参数，是不是当用户询问的时候，ai会自动提取信息并且给参数赋值，然后查找数据库获取对应的数据
    是的，当用户提出课程预约相关问题时，AI 会按照流程收集必要信息（如课程名称、姓名、联系方式、校区等），并将这些信息作为参数调用createCourseReservation函数。函数执行后会将预约信息存入数据库并返回预约单 ID。

    举个例子，若用户表示 "我想预约北京校区的 Java 开发课程，我叫张三，电话是 13800138000"，AI 会提取：

    courseId: "Java 开发课程" 对应的 ID（需通过工具查询）
    studentName: "张三"
    contactInfo: "13800138000"
    school: "北京校区"
    remark: null（用户未提供备注）

    然后调用工具生成预约单，完成整个预约流程～(≧∇≦)ﾉ
    */
}
