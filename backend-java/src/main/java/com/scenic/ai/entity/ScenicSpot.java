package com.scenic.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("scenic_spot")
public class ScenicSpot {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String category;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private String imageUrl;
    private String openTime;
    private String ticketInfo;
    private Integer sortOrder;

    /**
     * 1 = 正常（上架），0 = 下架。
     *
     * <p>取自 {@code schema.sql} 的列注释。注意：**当前代码里还没有任何地方读写它** ——
     * 景点数据是随知识库一起导入的静态数据，没有做「后台上下架」的功能。
     * 如果要实现景点管理，这个字段就是开关；在实现之前，不要假设它参与了查询过滤。
     */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
