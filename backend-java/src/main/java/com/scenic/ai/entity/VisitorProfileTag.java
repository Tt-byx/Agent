package com.scenic.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("visitor_profile_tag")
public class VisitorProfileTag {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String tagName;
    private Integer tagScore;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
