package org.example.mcpserver;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CourseService {
    private static final Logger logger= LoggerFactory.getLogger(CourseService.class);
    private final List< Course> courses=new ArrayList<>();

    @Tool(name="getAllCourses", description = "Get all courses from getAllCourses")
    public List<Course> getAllCourses() {
        return courses;
    }

    @Tool(name="getCourseById", description = "Get  course by name from getCourseById")
    public List<Course> getCourseById(String id){
       return  courses;
    }
    @PostConstruct
    public void init() {
        courses.addAll(List.of(new Course("1","Spring Boot","https://www.bilibili.com/video/BV1Vf4y127N5/?spm_id_from=333.1387.favlist.content.click&vd_source=47177de32b036174546b48055e4a9354"),
                new Course("2","Spring","https://www.bilibili.com/video/BV1Ry4y1574R/?spm_id_from=333.1387.favlist.content.click")));
        logger.info("CourseService initialized");
    }

}
