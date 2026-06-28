package org.py.mymodule.submodule.service.impl;

import org.py.mymodule.submodule.entity.po.Course;
import org.py.mymodule.submodule.mapper.CourseMapper;
import org.py.mymodule.submodule.service.ICourseService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 学科表 服务实现类
 * </p>
 *
 * @author huge
 * @since 2025-07-01
 */
@Service
public class CourseServiceImpl extends ServiceImpl<CourseMapper, Course> implements ICourseService {

}
