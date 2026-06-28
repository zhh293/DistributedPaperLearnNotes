package org.example.springai1.ToolBack;

import org.example.springai1.Tools.CourseTool;
import org.example.springai1.query.CourseQuery;
import org.example.springai1.util.Course;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Service
public class ToolBackService {
    //todo toolback
    public List<ToolCallback> getToolCallList(CourseTool courseTool) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<? extends CourseTool> aClass = courseTool.getClass();
        Method queryCourse = aClass.getDeclaredMethod("queryCourse", CourseQuery.class);
   //     Object invoke = queryCourse.invoke(courseTool, new CourseQuery());
       /* if(!(invoke instanceof List)){
            return List.of();
        }*/
        ToolDefinition build = ToolDefinition.builder()
                .name("queryCourse")
                .description("根据条件查询课程")
                //构建对应的json数据
                .inputSchema("""
                        {
                        "type":"object",
                        "properties":{
                          "ticketNumber":{
                           "type":"string",
                           "description":"预定号，可以是纯数字"
                          },
                          "name":{
                          "type":"string",
                          "description":"真实人名"
                          }
                        },
                        "required":["ticketNumber","name"]
                        }
                        
                        """).build();
        MethodToolCallback build1 = MethodToolCallback.builder()
                .toolDefinition(build)
                .toolMethod(queryCourse)
                .toolObject(courseTool)
                .build();
        return List.of(build1);
    }
}
