package org.example.springai1.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.springai1.util.Course;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 学科表 Mapper 接口
 * </p>
 *
 * @author huge
 * @since 2025-07-01
 */
@Mapper
public interface CourseMapper extends BaseMapper<Course> {

}
