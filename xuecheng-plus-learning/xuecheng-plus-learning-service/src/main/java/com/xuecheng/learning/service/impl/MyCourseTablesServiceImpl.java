package com.xuecheng.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.content.model.po.CoursePublish;
import com.xuecheng.learning.feignclient.ContentServiceClient;
import com.xuecheng.learning.mapper.XcChooseCourseMapper;
import com.xuecheng.learning.mapper.XcCourseTablesMapper;
import com.xuecheng.learning.model.dto.MyCourseTableParams;
import com.xuecheng.learning.model.dto.XcChooseCourseDto;
import com.xuecheng.learning.model.dto.XcCourseTablesDto;
import com.xuecheng.learning.model.po.XcChooseCourse;
import com.xuecheng.learning.model.po.XcCourseTables;
import com.xuecheng.learning.service.MyCourseTablesService;
import com.xuecheng.messagesdk.service.MqMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @author gushouye
 * @description 选课相关接口实现
 **/
@Service
@Slf4j
public class MyCourseTablesServiceImpl implements MyCourseTablesService {

    @Autowired
    private XcChooseCourseMapper chooseCourseMapper;
    @Autowired
    private XcCourseTablesMapper courseTablesMapper;
    @Autowired
    private ContentServiceClient contentServiceClient;
    @Autowired
    private MqMessageService mqMessageService;

    @Override
    @Transactional
    public XcChooseCourseDto addChooseCourse(String userId, Long courseId) {
        // 远程调用内容管理服务查询课程的收费规则
        CoursePublish coursepublish = contentServiceClient.getCoursepublish(courseId);
        XcChooseCourse xcChooseCourse = null;
        if (coursepublish == null) {
            // 课程不存在
            XueChengPlusException.cast("课程不存在");
        }
        // 收费规则
        String charge = coursepublish.getCharge();
        if (charge.equals("201000")) {
            // 如果是免费课程，会向选课表中添加选课记录，我的课程表中添加课程记录
            xcChooseCourse = addFreeCoruse(userId, coursepublish);// 选课记录表
            addCourseTabls(xcChooseCourse);// 我的课程表
        } else {
            // 如果是收费课程，会向选课表中添加选课记录
            xcChooseCourse = addChargeCoruse(userId, coursepublish);
        }
        // 判断学生的学习资格
        XcCourseTablesDto learningStatus = getLearningStatus(userId, courseId);
        // 返回选课记录
        XcChooseCourseDto xcChooseCourseDto = new XcChooseCourseDto();
        BeanUtils.copyProperties(xcChooseCourse, xcChooseCourseDto);
        xcChooseCourseDto.setLearnStatus(learningStatus.getLearnStatus());
        return xcChooseCourseDto;
    }


    @Override
    public XcCourseTablesDto getLearningStatus(String userId, Long courseId) {
        // 查询我的课程表，如果查不到，说明没有选课
        XcCourseTables xcCourseTables = getXcCourseTables(userId, courseId);
        XcCourseTablesDto xcCourseTablesDto = new XcCourseTablesDto();
        if (xcCourseTables == null) {
            // 没有选课
            xcCourseTablesDto.setLearnStatus("702002");
            return xcCourseTablesDto;
        }
        // 如果查到了，判断是否过期，如果过期，也不能继续学习，没有过期，可以继续学习
        boolean before = xcCourseTables.getValidtimeEnd().isBefore(LocalDateTime.now());
        if (before) {
            // 过期
            xcCourseTablesDto.setLearnStatus("702003");
            BeanUtils.copyProperties(xcCourseTables, xcCourseTablesDto);
            return xcCourseTablesDto;
        } else {
            // 没有过期
            xcCourseTablesDto.setLearnStatus("702001");
            BeanUtils.copyProperties(xcCourseTables, xcCourseTablesDto);
            return xcCourseTablesDto;
        }
    }

    @Override
    @Transactional
    public boolean saveChooseCourseSuccess(String chooseCourseId) {
        // 根据选课id查询选课记录
        XcChooseCourse xcChooseCourse = chooseCourseMapper.selectById(chooseCourseId);
        if (xcChooseCourse == null) {
            // 选课记录不存在
            log.error("选课记录不存在,{}", chooseCourseId);
            return false;
        }
        String status = xcChooseCourse.getStatus();
        // 只有当未支付时才更新为已支付
        if (status.equals("701002")) {
            // 更新选课记录为已支付
            xcChooseCourse.setStatus("701001");
            int i = chooseCourseMapper.updateById(xcChooseCourse);
            if (i <= 0) {
                log.error("更新选课记录失败,{}", xcChooseCourse);
                return false;
            }
            // 向我的课程表中添加课程记录
            XcCourseTables xcCourseTables = addCourseTabls(xcChooseCourse);
            if (xcCourseTables == null) {
                log.error("添加课程记录失败,{}", xcChooseCourse);
                return false;
            }
        }
        return true;
    }

    @Override
    public PageResult<XcCourseTables> mycoursetables(MyCourseTableParams params) {
        int page = params.getPage();
        int size = params.getSize();
        String userId = params.getUserId();
        // 分页查询我的课程表
        Page<XcCourseTables> xcCourseTablesPage = new Page<>(page, size);
        LambdaQueryWrapper<XcCourseTables> queryWrapper = new LambdaQueryWrapper<XcCourseTables>().eq(XcCourseTables::getUserId, userId);
        Page<XcCourseTables> xcCourseTablesPage1 = courseTablesMapper.selectPage(xcCourseTablesPage, queryWrapper);
        long total = xcCourseTablesPage1.getTotal();
        List<XcCourseTables> records = xcCourseTablesPage1.getRecords();
        PageResult<XcCourseTables> courseTablesResult = new PageResult<>(records, total, page, size);
        return courseTablesResult;
    }

    //添加免费课程,免费课程加入选课记录表、我的课程表
    public XcChooseCourse addFreeCoruse(String userId, CoursePublish coursepublish) {
        // 课程id
        Long courseId = coursepublish.getId();
        // 判断，如果存在免费的选课记录且选课状态为成功，则不允许再次添加，直接返回
        LambdaQueryWrapper<XcChooseCourse> queryWrapper = new LambdaQueryWrapper<XcChooseCourse>().eq(XcChooseCourse::getUserId, userId).eq(XcChooseCourse::getCourseId, coursepublish.getId())
                .eq(XcChooseCourse::getCourseId, courseId)
                .eq(XcChooseCourse::getOrderType, "700001") // 免费课程
                .eq(XcChooseCourse::getStatus, "701001");// 选课成功
        List<XcChooseCourse> xcChooseCourses = chooseCourseMapper.selectList(queryWrapper);
        if (xcChooseCourses.size() > 0) {
            return xcChooseCourses.get(0);
        }
        // 向选课记录表中添加选课记录
        XcChooseCourse xcChooseCourse = new XcChooseCourse();
        xcChooseCourse.setUserId(userId);
        xcChooseCourse.setCourseId(courseId);
        xcChooseCourse.setCourseName(coursepublish.getName());
        xcChooseCourse.setCompanyId(coursepublish.getCompanyId());
        xcChooseCourse.setOrderType("700001");// 免费课程
        xcChooseCourse.setCreateDate(LocalDateTime.now());
        xcChooseCourse.setCoursePrice(coursepublish.getPrice());
        xcChooseCourse.setValidDays(365);// 有效期365天
        xcChooseCourse.setStatus("701001");// 选课成功
        xcChooseCourse.setValidtimeStart(LocalDateTime.now());// 有效期开始时间
        xcChooseCourse.setValidtimeEnd(LocalDateTime.now().plusDays(365));// 有效期结束时间
        int insert = chooseCourseMapper.insert(xcChooseCourse);
        if (insert <= 0) {
            XueChengPlusException.cast("添加选课记录失败");
        }
        return xcChooseCourse;
    }

    //添加到我的课程表
    public XcCourseTables addCourseTabls(XcChooseCourse xcChooseCourse) {
        // 选课成功了，向我的课程表中添加课程记录
        String status = xcChooseCourse.getStatus();
        if (!status.equals("701001")) {
            // 选课失败，不添加
            XueChengPlusException.cast("选课失败，不添加到我的课程表");
        }
        XcCourseTables xcCourseTables = getXcCourseTables(xcChooseCourse.getUserId(), xcChooseCourse.getCourseId());
        if (xcCourseTables != null) {
            // 课程已经存在，不添加
            return xcCourseTables;
        }
        // 课程不存在，添加到我的课程表
        xcCourseTables = new XcCourseTables();
        BeanUtils.copyProperties(xcChooseCourse, xcCourseTables);
        xcCourseTables.setCourseId(xcChooseCourse.getCourseId()); // 记录选课表当中的主键
        xcCourseTables.setCourseType(xcChooseCourse.getOrderType()); // 课程类型
        xcCourseTables.setUpdateDate(LocalDateTime.now());
        int insert = courseTablesMapper.insert(xcCourseTables);
        if (insert <= 0) {
            XueChengPlusException.cast("添加到我的课程表失败");
        }
        return xcCourseTables;
    }


    //添加收费课程
    public XcChooseCourse addChargeCoruse(String userId, CoursePublish coursepublish) {
        // 课程id
        Long courseId = coursepublish.getId();
        // 判断，如果存在收费的选课记录且选课状态为待支付，则不允许再次添加，直接返回
        LambdaQueryWrapper<XcChooseCourse> queryWrapper = new LambdaQueryWrapper<XcChooseCourse>().eq(XcChooseCourse::getUserId, userId).eq(XcChooseCourse::getCourseId, coursepublish.getId())
                .eq(XcChooseCourse::getCourseId, courseId)
                .eq(XcChooseCourse::getOrderType, "700002") // 收费课程
                .eq(XcChooseCourse::getStatus, "701002");// 待支付
        List<XcChooseCourse> xcChooseCourses = chooseCourseMapper.selectList(queryWrapper);
        if (xcChooseCourses.size() > 0) {
            return xcChooseCourses.get(0);
        }
        // 向选课记录表中添加选课记录
        XcChooseCourse xcChooseCourse = new XcChooseCourse();
        xcChooseCourse.setUserId(userId);
        xcChooseCourse.setCourseId(courseId);
        xcChooseCourse.setCourseName(coursepublish.getName());
        xcChooseCourse.setCompanyId(coursepublish.getCompanyId());
        xcChooseCourse.setOrderType("700002");// 收费课程
        xcChooseCourse.setCreateDate(LocalDateTime.now());
        xcChooseCourse.setCoursePrice(coursepublish.getPrice());
        xcChooseCourse.setValidDays(365);// 有效期365天
        xcChooseCourse.setStatus("701002");// 待支付
        xcChooseCourse.setValidtimeStart(LocalDateTime.now());// 有效期开始时间
        xcChooseCourse.setValidtimeEnd(LocalDateTime.now().plusDays(365));// 有效期结束时间
        int insert = chooseCourseMapper.insert(xcChooseCourse);
        if (insert <= 0) {
            XueChengPlusException.cast("添加选课记录失败");
        }
        return xcChooseCourse;
    }

    /**
     * @param userId   用户id
     * @param courseId 课程id
     * @return com.xuecheng.learning.model.po.XcCourseTables
     * @description 根据课程和用户查询我的课程表中某一门课程
     */
    public XcCourseTables getXcCourseTables(String userId, Long courseId) {
        XcCourseTables xcCourseTables = courseTablesMapper.selectOne(new LambdaQueryWrapper<XcCourseTables>().eq(XcCourseTables::getUserId, userId).eq(XcCourseTables::getCourseId, courseId));
        return xcCourseTables;
    }

}
