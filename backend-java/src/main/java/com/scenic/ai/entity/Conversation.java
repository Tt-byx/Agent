package com.scenic.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话。
 *
 * <p>字段语义以 {@code resources/schema.sql} 为准（那里有完整的列注释），
 * 这里把**容易误解的枚举值**复述一遍 —— 否则读实体的人必须去翻 DDL 才知道
 * {@code status=0} 到底是「已结束」还是「已删除」。
 */
@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    /** 会话归属；游客会话为 null（首次有登录用户继续时由 ChatServiceImpl 回填） */
    private Long userId;

    private String title;

    /**
     * 1 = 进行中，0 = 已结束。
     *
     * <p>DDL 写的是「已结束」，但代码里 {@code ConversationController#delete} 是
     * {@code setStatus(0)} —— 也就是**软删除与结束共用这一个值**。
     * 列表查询统一用 {@code status=1} 过滤，所以两种语义在这里殊途同归；
     * 但如果将来要区分「用户主动结束」与「用户删除」，就得加新字段，不能复用这个。
     */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
