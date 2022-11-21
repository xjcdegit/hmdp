package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;
    //最小时间戳
    private Long minTime;
    //偏移量
    private Integer offset;
}
