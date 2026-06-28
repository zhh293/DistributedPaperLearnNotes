package org.py.mymodule.submodule.service.impl;

import org.py.mymodule.submodule.entity.po.School;
import org.py.mymodule.submodule.mapper.SchoolMapper;
import org.py.mymodule.submodule.service.ISchoolService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 校区表 服务实现类
 * </p>
 *
 * @author huge
 * @since 2025-07-01
 */
@Service
public class SchoolServiceImpl extends ServiceImpl<SchoolMapper, School> implements ISchoolService {

}
