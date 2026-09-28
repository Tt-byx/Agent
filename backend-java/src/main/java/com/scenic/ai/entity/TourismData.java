package com.scenic.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("tourism_data")
public class TourismData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String touristId;
    private String nickname;
    private Integer age;
    private String gender;
    private String attractionName;
    private String attractionType;
    private LocalDate visitDate;
    private BigDecimal stayDuration;
    private BigDecimal ticketCost;
    private BigDecimal foodCost;
    private BigDecimal shoppingCost;
    private BigDecimal transportCost;
    private BigDecimal entertainmentCost;
    private BigDecimal totalCost;
    private Integer groupSize;
    private BigDecimal satisfaction;
    private LocalDateTime createdAt;
}
