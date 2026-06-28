package org.py.mymodule.submodule.service.impl;

import org.py.mymodule.submodule.entity.po.CourseReservation;
import org.py.mymodule.submodule.mapper.CourseReservationMapper;
import org.py.mymodule.submodule.service.ICourseReservationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author huge
 * @since 2025-07-01
 */
@Service
public class CourseReservationServiceImpl extends ServiceImpl<CourseReservationMapper, CourseReservation> implements ICourseReservationService {

}
